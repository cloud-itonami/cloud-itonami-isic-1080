(ns feedops.facts
  "Reference facts for prepared-animal-feed manufacturing (mixing and
  pelletizing lines): product-type processing parameters (steam-
  conditioning temperature / post-pellet cooling time / moisture /
  mycotoxin / nutrient-deviation / shelf-life windows), jurisdiction
  evidence-checklist requirements. This namespace contains pure lookup
  functions for regulatory/feed-safety compliance checks -- the
  Governor calls these to independently validate proposals; the
  advisor's confidence is never sufficient on its own."
  (:require [clojure.set :as set]))

(def product-types
  "Valid prepared-animal-feed product categories and their safe
  mixing/pelletizing processing windows. `conditioning-temp-min-c` is
  the minimum steam-conditioning temperature the mash must reach
  immediately before the pellet die (the feed-mill analogue of a
  pasteurization/lethality step -- US FDA/AAFCO and Codex Alimentarius
  CAC/RCP feed-hygiene guidance treat sustained conditioning at or
  above ~80C as an effective Salmonella-reduction step for compound
  feed). `cooling-time-max-minutes` is the maximum time allowed to
  bring the hot, freshly-extruded pellet down to near-ambient
  temperature in the counter-flow cooler before bagging/bulk loading
  -- pellets left too long in a warm, still-moist state after cooling
  risk condensation-driven mould growth and, for high-fat rations, a
  self-heating/fire hazard. `max-moisture-pct` is the maximum
  allowable finished-product moisture content -- above this threshold
  the feed's water activity is high enough to support mould growth
  (and downstream mycotoxin production) during storage. `max-
  mycotoxin-ppb` is this product's maximum allowable aflatoxin
  contamination in parts per billion; the ceiling is deliberately
  species/class-specific because aflatoxin B1 carries through a
  lactating dairy cow into milk as aflatoxin M1 (a stricter
  human-food-chain concern), so dairy rations use a materially lower
  action level than finishing swine or broiler rations (reference
  values loosely follow published FDA CVM aflatoxin action-level
  guidance for animal feed, rounded for this actor's own facts
  table). `max-nutrient-deviation-pct` is the maximum allowable
  relative deviation of an assayed guaranteed-analysis nutrient (e.g.
  crude protein) from its declared label value -- exceeding it is a
  feed-labelling/guaranteed-analysis compliance failure, not merely a
  quality miss. `max-shelf-life-hours` is the maximum time from
  production to use the product may be held before fat oxidation
  (rancidity) and vitamin degradation exhaust the safety/quality
  margin."
  {:feed/poultry-broiler-pellet
   {:id :feed/poultry-broiler-pellet
    :name "配合飼料ペレット(ブロイラー用)"
    :conditioning-temp-min-c 80.0
    :cooling-time-max-minutes 20.0
    :max-moisture-pct 12.5
    :max-mycotoxin-ppb 100.0
    :max-nutrient-deviation-pct 10.0
    :max-shelf-life-hours 2160.0}

   :feed/swine-grower-pellet
   {:id :feed/swine-grower-pellet
    :name "配合飼料ペレット(肥育豚用)"
    :conditioning-temp-min-c 82.0
    :cooling-time-max-minutes 20.0
    :max-moisture-pct 13.0
    :max-mycotoxin-ppb 200.0
    :max-nutrient-deviation-pct 10.0
    :max-shelf-life-hours 2160.0}

   :feed/dairy-cattle-tmr-pellet
   {:id :feed/dairy-cattle-tmr-pellet
    :name "配合飼料ペレット(乳用牛TMR用)"
    :conditioning-temp-min-c 78.0
    :cooling-time-max-minutes 25.0
    :max-moisture-pct 13.5
    ;; Dairy rations carry the strictest aflatoxin ceiling in this
    ;; table: aflatoxin B1 metabolizes into aflatoxin M1 and carries
    ;; through into milk, a direct human-food-chain exposure route
    ;; that finishing-swine/broiler rations do not share.
    :max-mycotoxin-ppb 20.0
    :max-nutrient-deviation-pct 8.0
    :max-shelf-life-hours 1440.0}

   :feed/aquaculture-pellet
   {:id :feed/aquaculture-pellet
    :name "配合飼料ペレット(養殖魚用)"
    ;; Aquafeed extrusion runs hotter than simple pelleting (starch
    ;; gelatinization for water-stable/floating pellets).
    :conditioning-temp-min-c 90.0
    :cooling-time-max-minutes 15.0
    :max-moisture-pct 10.0
    ;; Salmonid and other farmed-fish species are unusually
    ;; aflatoxin-sensitive, so this actor's reference ceiling is as
    ;; strict as the dairy ration's.
    :max-mycotoxin-ppb 20.0
    :max-nutrient-deviation-pct 8.0
    :max-shelf-life-hours 4320.0}})

(defn product-type-by-id [id]
  (get product-types id))

(def jurisdictions
  "Prepared-animal-feed manufacturing jurisdictions and their
  evidence-checklist requirements."
  {:us/fda-cvm
   {:id :us/fda-cvm
    :name "United States (FDA Center for Veterinary Medicine -- 21 CFR 507 FSMA Preventive Controls for Animal Food / 21 CFR 225-226 Medicated Feed GMP)"
    :required-evidence
    [:raw-material-intake-record
     :mixing-uniformity-test
     :conditioning-temp-log
     :cooling-time-log
     :moisture-test
     :mycotoxin-test
     :nutrient-assay
     :medicated-status-declaration
     :weight-check
     :packaging-integrity-check]}

   :eu/feed-hygiene
   {:id :eu/feed-hygiene
    :name "European Union (Regulation (EC) No 183/2005 Feed Hygiene & Regulation (EC) No 767/2009 Labelling)"
    :required-evidence
    [:raw-material-intake-record
     :mixing-uniformity-test
     :conditioning-temp-log
     :cooling-time-log
     :moisture-test
     :mycotoxin-test
     :nutrient-assay
     :medicated-status-declaration
     :weight-check
     :packaging-integrity-check]}

   :jp/maff
   {:id :jp/maff
    :name "日本 (飼料の安全性の確保及び品質の改善に関する法律・農林水産省)"
    :required-evidence
    [:raw-material-intake-record
     :mixing-uniformity-test
     :conditioning-temp-log
     :cooling-time-log
     :moisture-test
     :mycotoxin-test
     :nutrient-assay
     :medicated-status-declaration
     :weight-check
     :packaging-integrity-check]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

(defn required-evidence-satisfied?
  "Verify that every item in the jurisdiction's `:required-evidence` list
  is present in `evidence`. `jurisdiction` may be a resolved jurisdiction
  map (as returned by `jurisdiction-by-id`) or a raw jurisdiction id --
  both call conventions are in use (tests pass a resolved map; the
  Governor passes the raw id straight off batch metadata)."
  [jurisdiction evidence]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (if-not j
      false
      (set/subset? (set (:required-evidence j)) (set evidence)))))

(defn conditioning-temp-meets-minimum?
  "Positive-sense convenience predicate: does `celsius` meet or exceed
  `product`'s minimum required steam-conditioning temperature?"
  [celsius product]
  (boolean
   (and (some? product)
        (>= celsius (:conditioning-temp-min-c product)))))

(defn cooling-time-within-max?
  "Positive-sense convenience predicate: did the batch stay at or below
  `product`'s maximum allowed post-pellet cooling time?"
  [minutes product]
  (boolean
   (and (some? product)
        (<= minutes (:cooling-time-max-minutes product)))))

(defn moisture-content-within-max?
  "Positive-sense convenience predicate: does `pct` stay at or below
  `product`'s maximum allowable finished-product moisture content?"
  [pct product]
  (boolean
   (and (some? product)
        (<= pct (:max-moisture-pct product)))))

(defn mycotoxin-level-within-max?
  "Positive-sense convenience predicate: does `ppb` stay at or below
  `product`'s maximum allowable mycotoxin (aflatoxin) contamination?"
  [ppb product]
  (boolean
   (and (some? product)
        (<= ppb (:max-mycotoxin-ppb product)))))

(defn nutrient-deviation-within-max?
  "Positive-sense convenience predicate: does `deviation-pct` (the
  assayed nutrient's relative deviation from its declared
  guaranteed-analysis value) stay at or below `product`'s maximum
  allowable deviation?"
  [deviation-pct product]
  (boolean
   (and (some? product)
        (<= deviation-pct (:max-nutrient-deviation-pct product)))))

(defn shelf-life-within-max?
  "Positive-sense convenience predicate: has the batch stayed at or
  below `product`'s maximum shelf-life hours since production?"
  [hours-elapsed product]
  (boolean
   (and (some? product)
        (<= hours-elapsed (:max-shelf-life-hours product)))))
