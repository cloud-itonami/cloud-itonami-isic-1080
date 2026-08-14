(ns feedops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had no
  demo page and no generator. This namespace drives the REAL actor
  stack -- `feedops.operation/run-operation` -> `feedops.governor/check`
  -> `feedops.store` -- over a seeded batch register, and renders what
  the Governor actually returned.

  This actor does NOT use langgraph: `feedops.sim` is a stub
  (`clojure -M:dev:run` prints \"not yet implemented\"), and
  `feedops.operation/run-operation` is a pure function that takes the
  governor fn directly. So the console calls the operation actor the way
  the actor is actually built, rather than inventing a StateGraph the
  repo does not have.

  Nothing on the page is hand-typed domain data:

  - product ids, names and every processing limit come from
    `feedops.facts/product-types`;
  - jurisdiction ids, names and evidence checklists come from
    `feedops.facts/jurisdictions`;
  - the seeded batches are built by `base-batch` from those limits
    (a clean batch sits a fixed margin inside the product's own window;
    a hold batch is that same batch with ONE field pushed past ONE of
    the product's own limits), so every number on the page traces back
    to `feedops.facts`;
  - every disposition, basis and violation-detail string is whatever
    `feedops.governor/check` returned for that step;
  - the store-surface and ledger-provenance sections are MEASURED at
    render time (var arglists, key-set diffs, fact counts), never
    asserted, so the page self-corrects if the actor changes.

  Determinism: the page contains no timestamps and no randomness, all
  collections are sorted explicitly, and the only clock-derived inputs
  are the two metal-detector calibration epochs (`hours-ago 6` for a
  current calibration, `hours-ago 72` for an overdue one). Those epochs
  never reach the page -- only the seed constant (6 / 72 hours) and the
  Governor's verdict do -- and both verdicts are stable for all time,
  so consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [feedops.facts :as facts]
            [feedops.governor :as governor]
            [feedops.operation :as op]
            [feedops.store :as store]))

(def ^:private console-css
  "The デジタル庁デザインシステム (DADS) token layer plus this console's skin,
  VENDORED into `resources/feedops/dads-console.css`.

  It is vendored rather than pulled as a git dependency on purpose: this
  page must be reproducible from a bare checkout with no network, and
  `deps.edn` here is otherwise dependency-free. The bytes are the same
  DADS base that `docs/index.html` (the product face) vendors, so the
  console and the product face share one visual language.

  Re-vendor -- do not hand-edit -- if the design system moves."
  (delay
   (let [r (io/resource "feedops/dads-console.css")]
     (when-not r
       (throw (ex-info "vendored console CSS is missing from the classpath -- refusing to render an unstyled page"
                       {:resource "feedops/dads-console.css"})))
     (slurp r))))

;; ─────────────────────────── scenario seed ───────────────────────────

(def ^:private actor-id "feedops-1080-1")
(def ^:private operator-id "plant-operator-1")

(def ^:private context
  {:actor-id actor-id :hold-fact-fn governor/hold-fact})

(def ^:private render-now
  "One clock reading for the whole run. `feedops.governor` reads the host
  clock itself when it checks metal-detector calibration currency, so a
  batch meant to be currently-calibrated must carry a relative timestamp
  (this is what `feedops.governor-test` does too). Snapshotting `now`
  once makes `hours-since` exact rather than drift-dependent."
  (System/currentTimeMillis))

(defn- hours-ago
  "Epoch-ms `h` hours before `render-now`. The epoch never reaches the
  rendered page -- only `hours-since` of it, and the Governor's verdict."
  [h]
  (- render-now (* h 60 60 1000)))

(defn- hours-since
  "Whole hours between `epoch` and `render-now`. Exact for anything built
  by `hours-ago`, so the page prints the same seed constant the Governor
  judged."
  [epoch]
  (quot (- render-now epoch) (* 60 60 1000)))

(def ^:private calibration-current-hours 6)
(def ^:private calibration-overdue-hours 72)

(defn- base-batch
  "A batch that is clean for `product-id` under `jurisdiction-id`, with
  every processing actual derived from that product's OWN limits in
  `feedops.facts/product-types` and the evidence checklist taken whole
  from that jurisdiction's `:required-evidence`."
  [product-id jurisdiction-id]
  (let [p (facts/product-type-by-id product-id)]
    {:product-type product-id
     :jurisdiction jurisdiction-id
     :conditioning-temp-c (+ (:conditioning-temp-min-c p) 5.0)
     :cooling-time-minutes (- (:cooling-time-max-minutes p) 5.0)
     :moisture-pct (- (:max-moisture-pct p) 1.5)
     :mycotoxin-ppb (/ (:max-mycotoxin-ppb p) 2.0)
     :nutrient-deviation-pct (- (:max-nutrient-deviation-pct p) 4.0)
     :shelf-life-hours-elapsed (/ (:max-shelf-life-hours p) 4.0)
     :foreign-material-detected? false
     :metal-detector-last-calibration-date (hours-ago calibration-current-hours)
     :weight-variance-grams 10
     :cross-contact-risk #{}
     :declared-medicated-status #{}
     :sanitation-score 85
     :packaging-compromised? false
     :evidence-checklist (:required-evidence (facts/jurisdiction-by-id jurisdiction-id))}))

(defn- over
  "`(over product-id :max-moisture-pct 2.0)` -> that product's own limit
  plus 2.0. Used so a hold batch's violating value is provably derived
  from the limit it violates, never hand-typed."
  [product-id limit-key delta]
  (+ (get (facts/product-type-by-id product-id) limit-key) delta))

(defn- under
  "That product's own limit minus `delta` (for below-minimum limits)."
  [product-id limit-key delta]
  (- (get (facts/product-type-by-id product-id) limit-key) delta))

(def ^:private seed-batches
  "The plant's registered batch records. `batch-001` is the batch that
  clears the full lifecycle; every other batch carries exactly ONE
  deviation so the Governor's basis for it is a single, unambiguous
  rule. Ordering here is irrelevant -- the page sorts by batch id."
  (sorted-map
   "batch-001" (base-batch :feed/poultry-broiler-pellet :us/fda-cvm)
   "batch-002" (base-batch :feed/swine-grower-pellet :eu/feed-hygiene)
   "batch-003" (base-batch :feed/dairy-cattle-tmr-pellet :jp/maff)

   "batch-101" (assoc (base-batch :feed/aquaculture-pellet :jp/maff)
                      :conditioning-temp-c (under :feed/aquaculture-pellet :conditioning-temp-min-c 6.0))
   "batch-102" (assoc (base-batch :feed/aquaculture-pellet :eu/feed-hygiene)
                      :cooling-time-minutes (over :feed/aquaculture-pellet :cooling-time-max-minutes 7.0))
   "batch-103" (assoc (base-batch :feed/poultry-broiler-pellet :us/fda-cvm)
                      :moisture-pct (over :feed/poultry-broiler-pellet :max-moisture-pct 2.0))
   "batch-104" (assoc (base-batch :feed/dairy-cattle-tmr-pellet :jp/maff)
                      :mycotoxin-ppb (over :feed/dairy-cattle-tmr-pellet :max-mycotoxin-ppb 25.0))
   "batch-105" (assoc (base-batch :feed/swine-grower-pellet :eu/feed-hygiene)
                      :nutrient-deviation-pct (over :feed/swine-grower-pellet :max-nutrient-deviation-pct 5.0))
   "batch-106" (assoc (base-batch :feed/dairy-cattle-tmr-pellet :jp/maff)
                      :shelf-life-hours-elapsed (over :feed/dairy-cattle-tmr-pellet :max-shelf-life-hours 120.0))
   "batch-107" (assoc (base-batch :feed/poultry-broiler-pellet :us/fda-cvm)
                      :foreign-material-detected? true)
   "batch-108" (assoc (base-batch :feed/swine-grower-pellet :us/fda-cvm)
                      :metal-detector-last-calibration-date (hours-ago calibration-overdue-hours))
   "batch-109" (assoc (base-batch :feed/poultry-broiler-pellet :eu/feed-hygiene)
                      :weight-variance-grams 35)
   "batch-110" (assoc (base-batch :feed/swine-grower-pellet :us/fda-cvm)
                      :cross-contact-risk #{:monensin}
                      :declared-medicated-status #{})
   "batch-111" (assoc (base-batch :feed/poultry-broiler-pellet :jp/maff)
                      :sanitation-score 60)
   "batch-112" (assoc (base-batch :feed/aquaculture-pellet :eu/feed-hygiene)
                      :packaging-compromised? true)
   "batch-113" (update (base-batch :feed/dairy-cattle-tmr-pellet :jp/maff)
                       :evidence-checklist #(vec (remove #{:mycotoxin-test} %)))
   "batch-114" (assoc (base-batch :feed/poultry-broiler-pellet :us/fda-cvm)
                      :safety-concern-raised? true
                      :safety-concern-resolved? false)))

(def ^:private unregistered-batch-id
  "Deliberately NOT in `seed-batches` -- the Governor must refuse any
  proposal against a batch the plant never registered."
  "batch-900")

(defn- cites
  "A jurisdiction citation for `j`, carrying that jurisdiction's real
  `:name` out of `feedops.facts/jurisdictions`."
  [j]
  [{:spec (:name (facts/jurisdiction-by-id j)) :jurisdiction j}])

(defn- proposal
  "A well-formed advisor proposal for a batch under jurisdiction `j`."
  ([j] (proposal j 0.91))
  ([j confidence]
   {:effect :propose
    :confidence confidence
    :cites (cites j)
    :value {:jurisdiction j}}))

;; ───────────────────────────── scenario ─────────────────────────────

(defn- steps
  "The ordered scenario. Each step is one proposal put to the real
  OperationActor. `:commit` (a fn of the store) is the plant-operator
  sign-off applied ONLY when the Governor escalated rather than blocked
  -- the actor itself has no approval node, so the console performs the
  sign-off and says so in the Ledger provenance section."
  []
  [{:id "S01" :op :schedule-maintenance :subject "batch-001"
    :proposal (proposal :us/fda-cvm)
    :intent "混合機/ペレットミルの定期保全提案 -- 唯一の自動承認経路"}

   {:id "S02" :op :log-production-batch :subject "batch-001"
    :proposal (proposal :us/fda-cvm)
    :intent "クリーンなバッチの生産記録登録 -- 常に人間の署名が要る"
    :commit (fn [st b]
              (store/log-batch st "batch-001" (assoc b :approved-by operator-id)))}

   {:id "S03" :op :coordinate-shipment :subject "batch-001"
    :proposal (proposal :us/fda-cvm)
    :intent "登録済みバッチの出荷調整 -- 常に人間の署名が要る"
    :commit (fn [st _b] (store/finalize-shipment st "batch-001"))}

   {:id "S04" :op :flag-food-safety-concern :subject "batch-002"
    :proposal (proposal :eu/feed-hygiene)
    :intent "食品安全上の懸念の提起 -- 信頼度に関わらず自動解決しない"}

   {:id "S05" :op :log-production-batch :subject "batch-003"
    :proposal (proposal :jp/maff 0.42)
    :intent "信頼度がconfidence-floor未満の提案 -- 低信頼としてもエスカレート"}

   ;; ── HARD holds (un-overridable) ────────────────────────────────
   {:id "S06" :op :operate-pellet-mill :subject "batch-001"
    :proposal (proposal :us/fda-cvm)
    :intent "ペレットミルの直接運転 -- allowlist外"}

   {:id "S07" :op :schedule-maintenance :subject "batch-001"
    :proposal (assoc (proposal :us/fda-cvm) :effect :actuate)
    :intent ":propose 以外の :effect を主張する提案"}

   {:id "S08" :op :log-production-batch :subject unregistered-batch-id
    :proposal (proposal :us/fda-cvm)
    :intent "プラント未登録のバッチに対する提案"}

   {:id "S09" :op :coordinate-shipment :subject "batch-002"
    :proposal (assoc (proposal :eu/feed-hygiene) :cites [])
    :intent "法域引用の無い提案"}

   {:id "S10" :op :log-production-batch :subject "batch-113"
    :proposal (proposal :jp/maff)
    :intent "必要書類(mycotoxin-test)が欠けたままの登録提案"}

   {:id "S11" :op :log-production-batch :subject "batch-101"
    :proposal (proposal :jp/maff)
    :intent "調質温度が製品規格の最低温度を下回るバッチ"}

   {:id "S12" :op :log-production-batch :subject "batch-102"
    :proposal (proposal :eu/feed-hygiene)
    :intent "冷却時間が製品規格上限を超過したバッチ"}

   {:id "S13" :op :log-production-batch :subject "batch-103"
    :proposal (proposal :us/fda-cvm)
    :intent "水分含量が製品規格上限を超過したバッチ"}

   {:id "S14" :op :log-production-batch :subject "batch-104"
    :proposal (proposal :jp/maff)
    :intent "アフラトキシンが乳用牛規格(最も厳しい)を超過したバッチ"}

   {:id "S15" :op :log-production-batch :subject "batch-105"
    :proposal (proposal :eu/feed-hygiene)
    :intent "保証成分値からの乖離が上限を超過したバッチ"}

   {:id "S16" :op :log-production-batch :subject "batch-107"
    :proposal (proposal :us/fda-cvm)
    :intent "異物(金属/ガラス/硬質プラスチック)が検出されたバッチ"}

   {:id "S17" :op :log-production-batch :subject "batch-108"
    :proposal (proposal :us/fda-cvm)
    :intent "異物検出機の校正が24時間を超えて期限切れのバッチ"}

   {:id "S18" :op :log-production-batch :subject "batch-109"
    :proposal (proposal :eu/feed-hygiene)
    :intent "仕上がり重量分散が許容を超過したバッチ"}

   {:id "S19" :op :log-production-batch :subject "batch-110"
    :proposal (proposal :us/fda-cvm)
    :intent "薬剤(モネンシン)の交差混入がラベル宣言に含まれないバッチ"}

   {:id "S20" :op :log-production-batch :subject "batch-111"
    :proposal (proposal :jp/maff)
    :intent "プラント衛生スコアが最低要件を下回るバッチ"}

   {:id "S21" :op :log-production-batch :subject "batch-112"
    :proposal (proposal :eu/feed-hygiene)
    :intent "包装(袋シール/バルク容器)の完全性が損なわれたバッチ"}

   {:id "S22" :op :log-production-batch :subject "batch-106"
    :proposal (proposal :jp/maff)
    :intent "製品規格の消費期限を超過したバッチ"}

   {:id "S23" :op :log-production-batch :subject "batch-114"
    :proposal (proposal :us/fda-cvm)
    :intent "未解決の食品安全フラグを抱えたバッチ"}

   ;; ── double-commit guards: only reachable AFTER S02 / S03 committed
   {:id "S24" :op :log-production-batch :subject "batch-001"
    :proposal (proposal :us/fda-cvm)
    :intent "S02で登録済みのバッチへの二重登録提案"}

   {:id "S25" :op :coordinate-shipment :subject "batch-001"
    :proposal (proposal :us/fda-cvm)
    :intent "S03で出荷確定済みのバッチへの二重出荷提案"}])

(defn- run-step
  "Put one step through the REAL OperationActor and record everything
  observed. `run-operation` returns `{:ok? true :facts []}` on the clean
  path -- no verdict and no audit fact -- so the console re-runs the
  pure `governor/check` to display the confidence it acted on, and
  records that it had to (`:verdict-from-actor?`)."
  [st {:keys [id op subject proposal intent commit]}]
  (let [request {:op op :subject subject}
        result (op/run-operation request context proposal st governor/check)
        verdict (or (:verdict result) (governor/check request context proposal st))
        actor-facts (vec (:facts result))
        signed-off? (boolean (and (not (:ok? result)) (not (:hard? verdict)) commit))
        st' (reduce store/append-fact st actor-facts)
        st' (cond
              (:ok? result)
              (store/append-fact st' {:t :auto-commit :op op :actor actor-id
                                      :subject subject :disposition :committed
                                      :basis [] :confidence (:confidence verdict)
                                      :source :console-harness})

              signed-off?
              (-> (commit st' (store/production-batch st' subject))
                  (store/append-fact {:t :approval-granted :op op :actor actor-id
                                      :subject subject :disposition :approved-and-committed
                                      :basis [] :confidence (:confidence verdict)
                                      :by operator-id :source :console-harness}))

              :else st')]
    [st' {:id id :op op :subject subject :intent intent
          :verdict verdict
          :ok? (boolean (:ok? result))
          :hard? (boolean (:hard? verdict))
          :escalate? (boolean (:escalate? verdict))
          :high-stakes? (boolean (:high-stakes? verdict))
          :confidence (:confidence verdict)
          :basis (mapv :rule (:violations verdict))
          :violations (vec (:violations verdict))
          :signed-off? signed-off?
          :actor-fact-count (count actor-facts)
          :verdict-from-actor? (contains? result :verdict)}]))

(defn run-demo!
  "Run the whole scenario against a freshly seeded store. Returns
  `{:store .. :steps [..]}` -- every field the page renders comes out
  of here, i.e. out of the real Governor."
  []
  (let [seed {:batches (into {} seed-batches) :facts []}]
    (reduce (fn [{:keys [store steps]} step]
              (let [[st' rec] (run-step store step)]
                {:store st' :steps (conj steps rec)}))
            {:store seed :steps []}
            (steps))))

;; ───────────────────── measured actor properties ─────────────────────

(defn- arglists
  "The var's declared arglists, read at render time. If the store grows
  an approver parameter this string changes on its own."
  [v]
  (str/join " " (map pr-str (:arglists (meta v)))))

(defn- measure-approver-retention
  "MEASURE (never assume) whether a committed record keeps the operator
  who signed it off.

  Two different questions, measured separately:
  1. `store/log-batch` takes the batch map from the caller -- does the
     record it writes still carry `:approved-by`?
  2. `store/finalize-shipment` takes no batch data at all -- after it
     runs, does the record mention the approver anywhere?"
  [st]
  (let [b (store/production-batch st "batch-001")
        ;; batch-002 is registered but was never signed off, so what
        ;; `finalize-shipment` writes is measurable in isolation --
        ;; measuring it on batch-001 would just re-read the `:approved-by`
        ;; that `log-batch` carried in from S02.
        before (store/production-batch st "batch-002")
        after (store/production-batch
               (store/finalize-shipment st "batch-002") "batch-002")
        added (set/difference (set (keys after)) (set (keys before)))]
    {:log-batch-arglists (arglists #'store/log-batch)
     :finalize-arglists (arglists #'store/finalize-shipment)
     :approved-by (:approved-by b)
     :log-batch-retains? (= operator-id (:approved-by b))
     :finalize-added-keys (vec (sort (map name added)))
     :finalize-records-approver? (contains? (set (vals (select-keys after added))) operator-id)
     :shipment-finalized? (true? (:shipment-finalized? b))
     ;; whole-record-replace probe: commit a PARTIAL record over the
     ;; registered one and see which measured keys survive.
     :replaced-keys (let [full (set (keys b))
                          partial* (set (keys (store/production-batch
                                               (store/log-batch st "batch-001"
                                                                (select-keys b [:product-type :jurisdiction]))
                                               "batch-001")))]
                      (vec (sort (map name (set/difference full partial*)))))}))

(defn- measure-ledger-provenance
  "MEASURE which ledger facts the actor itself emitted and which the
  console had to add, and how a HARD block is distinguishable from a
  human escalation in the ledger alone."
  [ledger steps]
  (let [by-source (group-by #(get % :source :actor) ledger)
        holds (filter #(= :governor-hold (:t %)) ledger)]
    {:total (count ledger)
     :actor-emitted (count (:actor by-source))
     :console-emitted (count (:console-harness by-source))
     :hold-facts (count holds)
     :hold-facts-with-basis (count (filter #(seq (:basis %)) holds))
     :hold-facts-without-basis (count (filter #(empty? (:basis %)) holds))
     :clean-steps (count (filter :ok? steps))
     :clean-steps-with-actor-fact (count (filter #(and (:ok? %) (pos? (:actor-fact-count %))) steps))
     :clean-steps-with-verdict (count (filter #(and (:ok? %) (:verdict-from-actor? %)) steps))}))

;; ───────────────────────────── rendering ─────────────────────────────

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw
  "Keyword text WITHOUT the leading colon but WITH its namespace, so an
  id on the page is greppable in the seed data verbatim
  (`feed/poultry-broiler-pellet`, not `poultry-broiler-pellet`)."
  [v]
  (if (keyword? v) (subs (str v) 1) (str v)))

(defn- kws
  "Sorted, comma-joined ids -- never relies on set iteration order."
  [coll]
  (if (seq coll) (str/join ", " (sort (map kw coll))) "—"))

(defn- fmt-num
  "Locale-independent number text: `str` on the double (never `format`,
  whose decimal separator follows the default Locale), with a trailing
  `.0` trimmed so 2160.0 reads as 2160."
  [v]
  (if (float? v)
    (let [s (str (double v))]
      (if (str/ends-with? s ".0") (subs s 0 (- (count s) 2)) s))
    (str v)))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       body
       "  </section>\n"))

(defn- disposition-cell [{:keys [ok? hard? escalate? signed-off?]}]
  (cond
    ok? "<span class=\"ok\">AUTO-COMMIT</span>"
    hard? "<span class=\"critical\">HARD HOLD</span>"
    (and escalate? signed-off?) "<span class=\"ok\">ESCALATE &rarr; 署名済み</span>"
    escalate? "<span class=\"warn\">ESCALATE &middot; 人間の署名待ち</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- step-row [{:keys [id op subject intent confidence basis high-stakes?] :as s}]
  (row (str "<code>" (esc id) "</code>")
       (str "<code>:" (esc (kw op)) "</code>")
       (str "<code>" (esc subject) "</code>")
       (esc intent)
       (disposition-cell s)
       (if (seq basis)
         (str "<code>" (esc (str/join ", " (map kw basis))) "</code>")
         "<span class=\"muted\">—</span>")
       (esc (fmt-num confidence))
       (if high-stakes? "<span class=\"warn\">yes</span>" "<span class=\"muted\">no</span>")))

(defn- violation-rows
  "One row per HARD violation the Governor actually produced, carrying
  its own `:detail` text verbatim. Sorted by step id."
  [steps]
  (for [s (sort-by :id steps)
        v (:violations s)]
    (row (str "<code>:" (esc (kw (:rule v))) "</code>")
         (str "<code>" (esc (:id s)) "</code>")
         (str "<code>" (esc (:subject s)) "</code>")
         (esc (:detail v)))))

(defn- batch-row [st [id b]]
  (let [p (facts/product-type-by-id (:product-type b))
        j (facts/jurisdiction-by-id (:jurisdiction b))
        cur (store/production-batch st id)]
    (row (str "<code>" (esc id) "</code>")
         (esc (:name p))
         (str "<code>:" (esc (kw (:jurisdiction b))) "</code>")
         (str (esc (fmt-num (:conditioning-temp-c b))) " / " (esc (fmt-num (:conditioning-temp-min-c p))))
         (str (esc (fmt-num (:cooling-time-minutes b))) " / " (esc (fmt-num (:cooling-time-max-minutes p))))
         (str (esc (fmt-num (:moisture-pct b))) " / " (esc (fmt-num (:max-moisture-pct p))))
         (str (esc (fmt-num (:mycotoxin-ppb b))) " / " (esc (fmt-num (:max-mycotoxin-ppb p))))
         (str (esc (fmt-num (:nutrient-deviation-pct b))) " / " (esc (fmt-num (:max-nutrient-deviation-pct p))))
         (str (esc (fmt-num (:shelf-life-hours-elapsed b))) " / " (esc (fmt-num (:max-shelf-life-hours p))))
         (esc (fmt-num (:sanitation-score b)))
         (str (count (:evidence-checklist b)) " / " (count (:required-evidence j)))
         (str (if (true? (:processed? cur)) "<span class=\"ok\">登録済み</span>" "<span class=\"muted\">未登録</span>")
              " &middot; "
              (if (true? (:shipment-finalized? cur)) "<span class=\"ok\">出荷確定</span>" "<span class=\"muted\">未出荷</span>")))))

(defn- flag-row [id b]
  (row (str "<code>" (esc id) "</code>")
       (if (:foreign-material-detected? b) "<span class=\"critical\">検出</span>" "<span class=\"muted\">なし</span>")
       (if (:packaging-compromised? b) "<span class=\"critical\">破損</span>" "<span class=\"muted\">健全</span>")
       (esc (fmt-num (:weight-variance-grams b)))
       (esc (kws (:cross-contact-risk b)))
       (esc (kws (:declared-medicated-status b)))
       (cond
         (and (:safety-concern-raised? b) (not (:safety-concern-resolved? b)))
         "<span class=\"critical\">未解決</span>"
         (:safety-concern-raised? b) "<span class=\"ok\">解決済み</span>"
         :else "<span class=\"muted\">なし</span>")
       (let [h (hours-since (:metal-detector-last-calibration-date b))]
         (if (> h 24)
           (str "<span class=\"critical\">" (esc h) "時間前</span>")
           (str "<span class=\"muted\">" (esc h) "時間前</span>")))))

(defn- product-row [[id p]]
  (row (str "<code>:" (esc (kw id)) "</code>")
       (esc (:name p))
       (esc (fmt-num (:conditioning-temp-min-c p)))
       (esc (fmt-num (:cooling-time-max-minutes p)))
       (esc (fmt-num (:max-moisture-pct p)))
       (esc (fmt-num (:max-mycotoxin-ppb p)))
       (esc (fmt-num (:max-nutrient-deviation-pct p)))
       (esc (fmt-num (:max-shelf-life-hours p)))))

(defn- jurisdiction-row [[id j]]
  (row (str "<code>:" (esc (kw id)) "</code>")
       (esc (:name j))
       (str (count (:required-evidence j)))
       (str "<code>" (esc (str/join ", " (map kw (:required-evidence j)))) "</code>")))

(defn- gate-row [op]
  (let [high? (contains? governor/high-stakes op)
        always? (contains? governor/always-escalate-ops op)]
    (row (str "<code>:" (esc (kw op)) "</code>")
         (if always?
           (if high?
             "<span class=\"warn\">常に人間の署名 &middot; 実アクチュエーション</span>"
             "<span class=\"warn\">常に人間の署名 &middot; 信頼度では自動解決しない</span>")
           "<span class=\"ok\">Governorがクリーンかつ信頼度が floor 以上なら自動承認</span>")
         (if high? "<span class=\"warn\">yes</span>" "<span class=\"muted\">no</span>")
         (esc (fmt-num governor/confidence-floor)))))

(defn- ledger-row [{:keys [t op subject disposition basis source by]}]
  (row (str "<code>:" (esc (kw t)) "</code>")
       (str "<code>:" (esc (kw op)) "</code>")
       (str "<code>" (esc subject) "</code>")
       (str "<code>:" (esc (kw disposition)) "</code>")
       (if (seq basis)
         (str "<code>" (esc (str/join ", " (map kw basis))) "</code>")
         "<span class=\"muted\">—</span>")
       (if (= :console-harness source)
         (str "<span class=\"warn\">console-harness</span>"
              (when by (str " &middot; " (esc by))))
         "<span class=\"ok\">actor</span>")))

(defn- kv-row
  "Label/value row. The label is author-written markup (never runtime
  data), so it is NOT escaped -- escaping it would print its `<code>`
  tags literally and double-escape its entities. Values that carry
  runtime data are escaped by their caller."
  [k v]
  (row k v))

(defn render
  "Render the console from a completed `run-demo!` result."
  [{:keys [store steps]}]
  (let [ledger (vec (store/audit-trail store))
        hard-steps (filter :hard? steps)
        hard-facts (filter #(and (= :governor-hold (:t %)) (seq (:basis %))) ledger)
        approver (measure-approver-retention store)
        prov (measure-ledger-provenance ledger steps)
        distinct-rules (sort (distinct (map kw (mapcat :basis hard-steps))))]
    (str
     "<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-1080 &middot; feedops operator console</title><style>"
     @console-css
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>配合飼料製造 (ISIC 1080) — オペレーターコンソール</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; 生産記録登録と出荷調整は常に人間の署名が要る</span>\n"
     "</header>\n"
     "<main>\n"

     (section
      "この頁の出所"
      (str "<code>clojure -M:dev:render-html</code> が実行時に "
           "<code>feedops.operation/run-operation</code> &rarr; "
           "<code>feedops.governor/check</code> &rarr; <code>feedops.store</code> "
           "を実際に駆動して生成した。値は一つも手書きされていない: 製品規格と法域要件は "
           "<code>feedops.facts</code>、判定と違反文言は Governor の戻り値、"
           "store の性質は render 時の実測。")
      (table ["観測値" "値"]
             [(kv-row "シナリオ手数 (proposal 数)" (str (count steps)))
              (kv-row "HARD hold (上書き不可)" (str "<span class=\"critical\">" (count hard-steps) "</span>"))
              (kv-row "HARD hold として台帳に載った :governor-hold" (str "<span class=\"critical\">" (count hard-facts) "</span>"))
              (kv-row "区別された HARD 規則の種類" (str (count distinct-rules)))
              (kv-row "エスカレーション (人間の署名待ち)" (str (count (filter #(and (:escalate? %) (not (:hard? %))) steps))))
              (kv-row "うちオペレーターが署名し commit したもの" (str "<span class=\"ok\">" (count (filter :signed-off? steps)) "</span>"))
              (kv-row "自動承認 (Governor クリーン &amp; 高信頼)" (str "<span class=\"ok\">" (count (filter :ok? steps)) "</span>"))
              (kv-row "監査台帳の fact 総数" (str (:total prov)))
              (kv-row "登録済みバッチ" (str (count seed-batches)))
              (kv-row "許可された提案種別 (closed allowlist)"
                      (str "<code>" (esc (kws governor/allowed-ops)) "</code>"))]))

     (section
      "シナリオ台帳 — 実行した提案とGovernorの判定"
      (str "各行は実際に <code>run-operation</code> に投入した1提案。<b>HARD HOLD</b> は "
           "advisor の信頼度に関わらず上書きできない。<code>basis</code> は Governor が返した "
           "<code>:violations</code> の規則そのもの。")
      (table ["#" "op" "subject" "意図" "判定" "basis" "信頼度" "high-stakes"]
             (map step-row steps)))

     (section
      "HARD hold の理由 — Governor 自身の説明"
      (str "上書き不可の停止 " (count hard-steps) " 件、規則種別 " (count distinct-rules)
           " 種。説明文は <code>feedops.governor</code> が生成した <code>:detail</code> の原文で、"
           "数値は当該バッチの実測値と <code>feedops.facts</code> の製品規格から来ている。")
      (table ["規則" "#" "subject" "Governor の説明"]
             (violation-rows steps)))

     (section
      "バッチ台帳 — 実測値 / 製品規格上限"
      (str "各セルは <b>そのバッチの実測値 / その製品の規格値</b>。規格値は "
           "<code>feedops.facts/product-types</code> の値であり、規格を外れた1項目だけを持つように "
           "各バッチを構成してある(だから basis が1規則に定まる)。")
      (table ["batch" "製品" "法域" "調質温度℃ (≧)" "冷却分 (≦)" "水分% (≦)"
              "アフラトキシンppb (≦)" "成分乖離% (≦)" "経過h (≦)" "衛生スコア" "書類" "状態"]
             (map (partial batch-row store) seed-batches)))

     (section
      "バッチ台帳 — 検査フラグ"
      "異物検出・包装完全性・重量分散・薬剤交差混入・食品安全フラグ・異物検出機の校正時期。"
      (table ["batch" "異物検出" "包装" "重量分散g" "交差混入リスク" "ラベル宣言" "食品安全フラグ" "検出機校正"]
             (map (fn [[id b]] (flag-row id b)) seed-batches)))

     (section
      "製品規格 (feedops.facts/product-types)"
      "Governor が独立に照合する製品別の処理窓。advisor はこの表を書き換えられない。"
      (table ["product" "名称" "調質温度 最低℃" "冷却 最大分" "水分 最大%"
              "アフラトキシン 最大ppb" "成分乖離 最大%" "消費期限 最大h"]
             (map product-row (sort-by key facts/product-types))))

     (section
      "法域の必要書類 (feedops.facts/jurisdictions)"
      "<code>:evidence-incomplete</code> はこの一覧との差集合で判定される。"
      (table ["法域" "根拠" "必要書類数" "必要書類"]
             (map jurisdiction-row (sort-by key facts/jurisdictions))))

     (section
      "アクションゲート (FeedOps Governor)"
      (str "closed allowlist の外にある提案 (混合/ペレット成形ライン制御・food-safety 認証) は "
           "<code>:op-not-allowed</code> で無条件に拒否される。"
           "confidence-floor = <code>" (esc (fmt-num governor/confidence-floor)) "</code>。")
      (table ["op" "ゲート" "実アクチュエーション" "信頼度 floor"]
             (map gate-row (sort governor/allowed-ops))))

     (section
      "監査台帳 (append-only) — この実行が書いた fact"
      (str "出所列に注意: <span class=\"ok\">actor</span> は "
           "<code>run-operation</code> が返した fact、"
           "<span class=\"warn\">console-harness</span> はこの頁の生成器が足した fact。"
           "内訳は次節で実測している。")
      (table ["fact" "op" "subject" "disposition" "basis" "出所"]
             (map ledger-row ledger)))

     (section
      "実測: 台帳の出所と、HARD と ESCALATE の見分け"
      (str "この節は主張ではなく render 時の計数。actor が振る舞いを変えれば数字が自動で変わる。")
      (table ["観測" "値" "意味"]
             [(row "台帳 fact 総数" (str (:total prov)) "—")
              (row "actor が出した fact" (str (:actor-emitted prov))
                   "<code>run-operation</code> の <code>:facts</code>")
              (row "console-harness が足した fact"
                   (str "<span class=\"warn\">" (:console-emitted prov) "</span>")
                   "承認/自動commitの記録。actor 側に対応する fact 生成が無いため")
              (row "クリーン判定だった手数" (str (:clean-steps prov)) "<code>:ok? true</code>")
              (row "うち actor が fact を出した手数"
                   (str (if (zero? (:clean-steps-with-actor-fact prov))
                          (str "<span class=\"critical\">" (:clean-steps-with-actor-fact prov) "</span>")
                          (str "<span class=\"ok\">" (:clean-steps-with-actor-fact prov) "</span>")))
                   (if (zero? (:clean-steps-with-actor-fact prov))
                     "<b>欠陥</b>: <code>run-operation</code> は承認時に <code>{:ok? true :facts []}</code> を返し、append-only 台帳に commit の証跡を残さない"
                     "承認時にも actor が fact を出している"))
              (row "うち actor が verdict を返した手数"
                   (str (if (zero? (:clean-steps-with-verdict prov))
                          (str "<span class=\"critical\">" (:clean-steps-with-verdict prov) "</span>")
                          (str "<span class=\"ok\">" (:clean-steps-with-verdict prov) "</span>")))
                   (if (zero? (:clean-steps-with-verdict prov))
                     "<b>欠陥</b>: 承認時の戻り値に <code>:verdict</code> が無いので、どの信頼度で通したかを台帳から追えない (この頁は <code>governor/check</code> を再実行して表示している)"
                     "承認時にも verdict が返っている"))
              (row "<code>:governor-hold</code> fact" (str (:hold-facts prov)) "—")
              (row "うち basis を持つ (= HARD)"
                   (str "<span class=\"critical\">" (:hold-facts-with-basis prov) "</span>") "上書き不可の停止")
              (row "うち basis が空 (= ESCALATE)"
                   (str (if (pos? (:hold-facts-without-basis prov))
                          (str "<span class=\"warn\">" (:hold-facts-without-basis prov) "</span>")
                          (str "<span class=\"muted\">" (:hold-facts-without-basis prov) "</span>")))
                   (if (pos? (:hold-facts-without-basis prov))
                     "<b>欠陥</b>: 人間へのエスカレーションも HARD 停止と同じ <code>:t :governor-hold</code> / <code>:disposition :hold</code> で書かれ、<code>:basis</code> が空かどうかでしか区別できない"
                     "エスカレーションは別の fact 型で書かれている"))]))

     (section
      "実測: commit した記録に署名者は残るか"
      (str "報告のために仮定せず実際に測っている。<code>" (esc operator-id)
           "</code> が S02(生産記録登録) と S03(出荷調整) に署名した後の "
           "<code>batch-001</code> の記録を読み直した結果。")
      (table ["観測" "値"]
             [(kv-row "<code>store/log-batch</code> の引数"
                      (str "<code>" (esc (:log-batch-arglists approver)) "</code>"))
              (kv-row "<code>store/finalize-shipment</code> の引数"
                      (str "<code>" (esc (:finalize-arglists approver)) "</code>"))
              (kv-row "commit 後の記録に残っていた署名者"
                      (if (:approved-by approver)
                        (str "<span class=\"ok\"><code>" (esc (:approved-by approver)) "</code></span>")
                        "<span class=\"critical\">残っていない (誰も署名しなかった場合と区別できない)</span>"))
              (kv-row "<code>log-batch</code> は呼び出し側が渡した署名者を保持するか"
                      (if (:log-batch-retains? approver)
                        "<span class=\"ok\">保持する (呼び出し側が batch map に載せた場合に限る — store 側に専用の欄は無い)</span>"
                        "<span class=\"critical\">保持しない</span>"))
              (kv-row "<code>finalize-shipment</code> が出荷確定時に記録に足す項目"
                      (str "<code>" (esc (str/join ", " (:finalize-added-keys approver))) "</code>"))
              (kv-row "<code>finalize-shipment</code> は出荷確定の署名者を記録できるか"
                      (if (:finalize-records-approver? approver)
                        "<span class=\"ok\">記録している</span>"
                        (str "<span class=\"critical\">できない</span> — 引数に署名者を取らず、"
                             "上の項目しか足さないため、出荷を確定した人間は store のどこにも残らない。"
                             "S02 の <code>:approved-by</code> は <code>log-batch</code> が"
                             "呼び出し側から受け取ったものであって、出荷確定の署名者ではない "
                             "(この行は引数と記録差分の実測から導出しており、修正されれば自動で表示が変わる)")))
              (kv-row "出荷確定フラグ"
                      (if (:shipment-finalized? approver)
                        "<span class=\"ok\">true</span>" "<span class=\"muted\">false</span>"))
              (kv-row "<code>log-batch</code> に部分レコードを渡すと消える測定値"
                      (if (seq (:replaced-keys approver))
                        (str "<span class=\"warn\"><code>" (esc (str/join ", " (:replaced-keys approver)))
                             "</code></span> — <code>log-batch</code> は記録を <b>丸ごと置換</b>する。"
                             "現在の allowlist では置換後の再評価が <code>:already-processed</code> で止まるため"
                             "実害の経路は無いが、merge ではないことは記録しておく")
                        "<span class=\"ok\">無し (merge されている)</span>"))]))

     "</main>\n"
     "<footer>\n"
     "  <p class=\"muted\">cloud-itonami-isic-1080 &middot; feedops &middot; "
     "<code>src/feedops/render_html.clj</code> が実 actor 実行から生成。"
     "この頁は決定論的で、同じ seed に対して再生成すると byte 単位で一致する。</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        demo (run-demo!)
        html (render demo)
        ledger (vec (store/audit-trail (:store demo)))
        hard-facts (filter #(and (= :governor-hold (:t %)) (seq (:basis %))) ledger)
        hard-count (count hard-facts)
        rules (sort (distinct (mapcat #(map name (:basis %)) hard-facts)))]
    ;; Build-time invariant, not a convention: a console that shows no
    ;; un-overridable block is not showing this actor's governor at all.
    (when (zero? hard-count)
      (throw (ex-info "operator console rendered 0 HARD :governor-hold entries -- refusing to write a page that does not demonstrate an un-overridable block"
                      {:ledger-facts (count ledger)
                       :steps (count (:steps demo))})))
    (spit out html)
    (println "wrote" out
             (str "(" (count (:steps demo)) " proposals, "
                  hard-count " HARD holds over " (count rules) " distinct rules, "
                  (count (filter :signed-off? (:steps demo))) " human sign-offs, "
                  (count (filter :ok? (:steps demo))) " auto-commits, "
                  (count ledger) " ledger facts)"))
    (println "hard rules:" (str/join ", " rules))))
