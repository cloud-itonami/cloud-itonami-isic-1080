(ns feedops.governor
  "FeedOps Governor -- the independent compliance layer that earns the
  FeedOpsAdvisor the right to commit. The LLM has no notion of:
    - Whether a batch's steam-conditioning temperature reached the
      product's minimum required temperature immediately before the
      pellet die
    - Whether the batch's post-pellet cooling time (die-exit
      temperature down through near-ambient in the counter-flow
      cooler) exceeded the product's maximum allowed window
    - Whether the batch's finished-product moisture content exceeds
      the product's maximum allowable level
    - Whether the batch's mycotoxin (aflatoxin) contamination exceeds
      the product's maximum allowable level
    - Whether an assayed guaranteed-analysis nutrient's deviation from
      its declared label value exceeds the product's maximum
      allowable deviation
    - Whether the batch's elapsed time since production exceeded the
      product's maximum shelf-life hours (a use-by-date violation)
    - Whether foreign material (metal/glass/dense-plastic fragments)
      was detected in the batch
    - Whether the metal-detector inspection equipment calibration is
      current
    - Whether final product weight variance is acceptable
    - Whether medicated-feed cross-contact declaration is complete and
      accurate
    - Whether plant sanitation/cross-contamination-control score is passed
    - Whether the batch's packaging integrity (bag seal / bulk-container
      closure) is intact
    - Whether an open food-safety concern has been resolved
    - Whether the plant/batch record was independently verified and
      registered before any proposal is made against it

  This MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  Unlike direct mixing/pelletizing-line control (NEVER done by this
  actor -- mixing, pelletizing, cooling, and packaging-line operation
  remain exclusive to plant staff), the Governor operates on batch
  metadata: provenance, processing parameters, sanitation records, and
  food-safety flags. This is plant-operations coordination, not
  process control.

  CRITICAL: Any proposal involving food-safety concerns (e.g.
  mycotoxin contamination, medicated-feed cross-contact) ALWAYS
  escalates to human operator for final sign-off. The LLM's confidence
  is never sufficient for food-safety decisions.

  Hard violations (always HOLD, no override):
    1. Operation outside the closed allowlist (includes any proposal
       that would touch mixing/pelletizing-line control or food-safety
       certification)
    2. Proposal asserting an `:effect` other than `:propose`
    3. Plant/batch record not independently verified/registered before
       any proposal is made against it
    4. No jurisdiction citation (jurisdiction unknown -> can't verify reqs)
    5. Evidence incomplete (missing required-evidence per jurisdiction)
    6. Steam-conditioning temperature below the product's minimum
       required temperature
    7. Post-pellet cooling time exceeds the product's maximum window
    8. Moisture content exceeds the product's maximum allowable level
    9. Mycotoxin (aflatoxin) contamination exceeds the product's
       maximum allowable level
   10. Nutrient-content deviation from the guaranteed-analysis label
       exceeds the product's maximum allowable deviation
   11. Foreign material detected (metal/glass/dense-plastic fragments)
   12. Metal-detector calibration overdue
   13. Weight variance excessive (bagging/loading line drift risk)
   14. Medicated-feed cross-contact mismatch (labelling / food-safety
       violation)
   15. Plant sanitation/cross-contamination-control score insufficient
   16. Packaging integrity (bag seal / bulk-container closure)
       compromised
   17. Shelf life exceeded (elapsed time since production beyond the
       product's maximum shelf-life hours)
   18. Food-safety flag unresolved (open concern, escalate required)
   19. Batch already processed (double-commit guard)
   20. Shipment already finalized (double-commit guard)

  Soft gates (always escalate for human):
    - Low confidence
    - Real actuation (`:log-production-batch`, `:coordinate-shipment`)
    - `:flag-food-safety-concern` (never auto-resolved by confidence alone)

  This design mirrors `chocops.governor` (ISIC 1073) and
  `mealops.governor` (ISIC 1075) but specializes on prepared-animal-
  feed-specific concerns: mixing/pelletizing critical-control-points
  (steam-conditioning temperature, post-pellet cooling time, moisture,
  mycotoxin/aflatoxin contamination, guaranteed-analysis nutrient
  accuracy, medicated-feed drug carryover) -- rather than
  confectionery-specific tempering-curve/cadmium/viscosity processing
  safety or prepared-meal-specific cook-chill/cook-freeze HACCP
  critical-control-points."
  (:require [feedops.facts :as facts]
            [feedops.registry :as registry]
            [feedops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Logging a batch into production records (`:log-production-batch`) and
  coordinating shipment of finished product (`:coordinate-shipment`) are
  the two real-world actuation events this actor performs. Both require
  plant operator sign-off."
  #{:log-production-batch :coordinate-shipment})

(def always-escalate-ops
  "Operations that always require human sign-off, even when the Governor's
  hard checks are clean and confidence is high: the two high-stakes
  actuation events (`high-stakes`) plus `:flag-food-safety-concern` --
  a food-safety concern (e.g. mycotoxin contamination, medicated-feed
  cross-contact) is never auto-resolved by advisor confidence alone,
  it always needs a human look."
  (conj high-stakes :flag-food-safety-concern))

(def allowed-ops
  "Closed allowlist of proposal operations this actor may ever make. Any
  proposal for an operation outside this set -- most importantly direct
  mixing/pelletizing-line control (mixer, conditioner, pellet mill,
  cooler, bagging/loading line) or food-safety certification
  authority -- is a hard, permanent block: this actor coordinates
  plant operations, it does not operate equipment and it does not
  certify food safety."
  #{:log-production-batch :schedule-maintenance :flag-food-safety-concern :coordinate-shipment})

;; ────────────────────────── Checks ──────────────────────────

(defn- op-not-allowed-violations
  "HARD, permanent block: any proposal outside the closed operation
  allowlist (e.g. direct mixing/pelletizing-line control, or a
  food-safety certification action) is refused unconditionally --
  this actor has no authority to make such a proposal at all, let
  alone commit it."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこのactorの許可された提案種別 (log-production-batch/"
                  "schedule-maintenance/flag-food-safety-concern/coordinate-shipment) "
                  "に含まれない -- 混合/ペレット成形ライン制御やfood-safety認証権限はこのactorに無い")}]))

(defn- effect-not-propose-violations
  "HARD invariant: this actor's proposals are always `:effect :propose` --
  it never claims direct write/actuation authority for itself. A proposal
  asserting any other effect is refused unconditionally."
  [_request proposal]
  (when-let [effect (:effect proposal)]
    (when (not= effect :propose)
      [{:rule :effect-not-propose
        :detail (str "この actor の提案は :propose 以外の :effect を持てない (got " effect ")")}])))

(defn- batch-not-registered-violations
  "HARD invariant: a plant/batch record must be independently verified/
  registered in the store BEFORE ANY proposal (not just shipment
  coordination) can be made against it -- this actor coordinates
  operations for an already-registered batch, it never invents or
  self-registers one from an unverified proposal."
  [{:keys [op subject]} st]
  (when (contains? allowed-ops op)
    (when-not (store/production-batch st subject)
      [{:rule :batch-not-registered
        :detail (str subject " はプラントに登録されたバッチ記録が無い -- 提案は進められない")}])))

(defn- spec-basis-violations
  "A proposal with no jurisdiction citation is a HARD violation -- never
  invent a jurisdiction's food-safety requirements."
  [{:keys [op]} proposal]
  (when (contains?
         #{:log-production-batch :coordinate-shipment :flag-food-safety-concern}
         op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :jurisdiction) (nil? (:jurisdiction value))))
        [{:rule :no-spec-basis
          :detail "公式仕様の引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:log-production-batch`, verify the batch's evidence checklist is
  complete per jurisdiction requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when-not (and b
                     (facts/required-evidence-satisfied?
                      (:jurisdiction b)
                      (:evidence-checklist b)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(raw-material-intake-record/mixing-uniformity-test/conditioning-temp-log/cooling-time-log/moisture-test/mycotoxin-test等)が充足していない状態での提案"}]))))

(defn- conditioning-temp-below-minimum-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  steam-conditioning temperature reached the product's minimum
  required temperature via
  `registry/conditioning-temp-below-minimum?`. Evaluated
  UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:conditioning-temp-c b)
                 (registry/conditioning-temp-below-minimum?
                  (:conditioning-temp-c b)
                  (:conditioning-temp-min-c p)))
        [{:rule :conditioning-temp-below-minimum
          :detail (str subject " の調質(コンディショニング)温度(" (:conditioning-temp-c b)
                      "℃)が製品規格の最低温度(" (:conditioning-temp-min-c p)
                      "℃)を下回る -- バッチ登録提案は進められない")}]))))

(defn- cooling-time-exceeds-max-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  post-pellet cooling time did not exceed the product's maximum
  window via `registry/cooling-time-exceeds-max?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:cooling-time-minutes b)
                 (registry/cooling-time-exceeds-max?
                  (:cooling-time-minutes b)
                  (:cooling-time-max-minutes p)))
        [{:rule :cooling-time-exceeds-max
          :detail (str subject " の冷却時間(" (:cooling-time-minutes b)
                      "分)が製品規格上限(" (:cooling-time-max-minutes p)
                      "分)を超過 -- バッチ登録提案は進められない")}]))))

(defn- moisture-content-exceeds-max-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  finished-product moisture content does not exceed the product's
  maximum via `registry/moisture-content-exceeds-max?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:moisture-pct b)
                 (registry/moisture-content-exceeds-max?
                  (:moisture-pct b)
                  (:max-moisture-pct p)))
        [{:rule :moisture-content-exceeds-max
          :detail (str subject " の水分含量(" (:moisture-pct b)
                      "%)が製品規格上限(" (:max-moisture-pct p)
                      "%)を超過 -- バッチ登録提案は進められない")}]))))

(defn- mycotoxin-level-exceeds-max-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  mycotoxin (aflatoxin) contamination does not exceed the product's
  maximum via `registry/mycotoxin-level-exceeds-max?`. Evaluated
  UNCONDITIONALLY -- mycotoxin contamination is one of the most
  common root causes of animal-feed food-safety incidents and, for
  dairy rations, carries directly into the human food chain via milk."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:mycotoxin-ppb b)
                 (registry/mycotoxin-level-exceeds-max?
                  (:mycotoxin-ppb b)
                  (:max-mycotoxin-ppb p)))
        [{:rule :mycotoxin-level-exceeds-max
          :detail (str subject " のマイコトキシン(アフラトキシン)濃度(" (:mycotoxin-ppb b)
                      "ppb)が製品規格上限(" (:max-mycotoxin-ppb p)
                      "ppb)を超過 -- バッチ登録提案は進められない")}]))))

(defn- nutrient-deviation-exceeds-max-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that an assayed
  guaranteed-analysis nutrient's deviation from its declared label
  value does not exceed the product's maximum via
  `registry/nutrient-deviation-exceeds-max?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:nutrient-deviation-pct b)
                 (registry/nutrient-deviation-exceeds-max?
                  (:nutrient-deviation-pct b)
                  (:max-nutrient-deviation-pct p)))
        [{:rule :nutrient-deviation-exceeds-max
          :detail (str subject " の保証成分値からの乖離(" (:nutrient-deviation-pct b)
                      "%)が製品規格上限(" (:max-nutrient-deviation-pct p)
                      "%)を超過 -- バッチ登録提案は進められない")}]))))

(defn- foreign-material-detected-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the batch's own
  foreign-material-detection result via `registry/foreign-material-
  detected?`. A detection on THIS batch's own inspection is a hard,
  physical-hazard block -- distinct from `food-safety-flag-unresolved-
  violations` below, which covers a separately-raised, not-yet-resolved
  concern."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (registry/foreign-material-detected? (:foreign-material-detected? b)))
        [{:rule :foreign-material-detected
          :detail (str subject " で異物(金属/ガラス/硬質プラスチック混入)が検出された -- バッチ登録提案は進められない")}]))))

(defn- now-epoch-ms
  "Current time in epoch milliseconds, portable across Clojure/
  ClojureScript. Isolated to this single call site so the rest of the
  namespace (and all of `feedops.registry`) stays free of host-clock
  calls."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn- metal-detector-calibration-overdue-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the
  metal-detection inspection equipment's calibration is current
  (recalibration required every 24 hours)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:metal-detector-last-calibration-date b)
                 (registry/metal-detector-calibration-overdue? (:metal-detector-last-calibration-date b) (now-epoch-ms)))
        [{:rule :metal-detector-calibration-overdue
          :detail (str subject " の異物検出機(金属探知機)校正が期限切れ -- バッチ登録提案は進められない")}]))))

(defn- weight-variance-excessive-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the weight variance."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:weight-variance-grams b)
                 (registry/weight-variance-excessive? (:weight-variance-grams b) 20))
        [{:rule :weight-variance-excessive
          :detail (str subject " の重量分散(" (:weight-variance-grams b)
                      "g)が許容範囲(20g)を超過 -- バッチ登録提案は進められない")}]))))

(defn- medicated-feed-cross-contact-violations
  "For `:log-production-batch`, INDEPENDENTLY verify medicated-feed
  cross-contact declaration completeness via
  `registry/medicated-feed-cross-contact?` -- a common recall reason
  in mixed-ration feed mills running medicated and non-medicated
  batches on shared mixing/pelletizing equipment (e.g. ionophore
  carryover toxic to non-target species such as equids)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:cross-contact-risk b)
                 (registry/medicated-feed-cross-contact? (:cross-contact-risk b) (:declared-medicated-status b)))
        [{:rule :medicated-feed-cross-contact
          :detail (str subject " の薬剤混入(cross-contact)宣言が不完全 -- バッチ登録提案は進められない")}]))))

(defn- sanitation-score-insufficient-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the plant's
  sanitation/cross-contamination-control score meets minimum
  requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:sanitation-score b)
                 (registry/sanitation-score-insufficient? (:sanitation-score b) 75))
        [{:rule :sanitation-score-insufficient
          :detail (str subject " のプラント衛生/交差汚染管理スコア(" (:sanitation-score b)
                      ")が最低要件(75)を下回る -- バッチ登録提案は進められない")}]))))

(defn- packaging-integrity-compromised-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the batch's
  packaging integrity inspection result (bag seal / bulk-container
  closure) via `registry/packaging-integrity-compromised?`. Compromised
  packaging undermines both the moisture-content safety assumptions
  and the batch's shelf-life calculation."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (registry/packaging-integrity-compromised? (:packaging-compromised? b)))
        [{:rule :packaging-integrity-compromised
          :detail (str subject " の包装(袋シール/バルクコンテナ)の完全性が損なわれている -- バッチ登録提案は進められない")}]))))

(defn- shelf-life-exceeded-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  elapsed time since production has not exceeded the product's
  maximum shelf-life hours via `registry/shelf-life-exceeded?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:shelf-life-hours-elapsed b)
                 (registry/shelf-life-exceeded?
                  (:shelf-life-hours-elapsed b)
                  (:max-shelf-life-hours p)))
        [{:rule :shelf-life-exceeded
          :detail (str subject " の経過時間(" (:shelf-life-hours-elapsed b)
                      "時間)が製品規格の消費期限(" (:max-shelf-life-hours p)
                      "時間)を超過 -- バッチ登録提案は進められない")}]))))

(defn- food-safety-flag-unresolved-violations
  "An unresolved food-safety flag is a HARD, un-overridable hold.
  Food-safety concerns (e.g. mycotoxin contamination, medicated-feed
  cross-contact) raised during production or inspection MUST be
  resolved before the batch can be logged. Evaluated UNCONDITIONALLY
  at `:log-production-batch`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and (true? (:safety-concern-raised? b))
                 (not (true? (:safety-concern-resolved? b))))
        [{:rule :food-safety-flag-unresolved
          :detail (str subject " は未解決の食品安全フラグがある -- バッチ登録提案は進められない")}]))))

(defn- already-processed-violations
  "For `:log-production-batch`, refuse to process the SAME batch twice, off
  a dedicated `:processed?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (when (store/batch-already-processed? st subject)
      [{:rule :already-processed
        :detail (str subject " は既に登録済み")}])))

(defn- already-shipment-finalized-violations
  "For `:coordinate-shipment`, refuse to finalize the SAME batch's shipment
  twice, off a dedicated `:shipment-finalized?` fact."
  [{:keys [op subject]} st]
  (when (= op :coordinate-shipment)
    (when (store/batch-shipment-finalized? st subject)
      [{:rule :already-shipment-finalized
        :detail (str subject " は既に出荷確定済み")}])))

(defn check
  "Censors a FeedOpsAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}.

  Stakes (high-stakes actuation vs. always-escalate) are read off the
  REQUEST's `:op` -- not off the proposal -- since the operation being
  proposed (not the advisor's self-reported stake) is what determines
  whether a human must sign off."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowed-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (batch-not-registered-violations request st)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (conditioning-temp-below-minimum-violations request st)
                           (cooling-time-exceeds-max-violations request st)
                           (moisture-content-exceeds-max-violations request st)
                           (mycotoxin-level-exceeds-max-violations request st)
                           (nutrient-deviation-exceeds-max-violations request st)
                           (foreign-material-detected-violations request st)
                           (metal-detector-calibration-overdue-violations request st)
                           (weight-variance-excessive-violations request st)
                           (medicated-feed-cross-contact-violations request st)
                           (sanitation-score-insufficient-violations request st)
                           (packaging-integrity-compromised-violations request st)
                           (shelf-life-exceeded-violations request st)
                           (food-safety-flag-unresolved-violations request st)
                           (already-processed-violations request st)
                           (already-shipment-finalized-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        actuation? (boolean (high-stakes (:op request)))
        escalate-op? (boolean (always-escalate-ops (:op request)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not escalate-op?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? escalate-op?))
     :high-stakes? actuation?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
