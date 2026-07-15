(ns feedops.registry
  "Pure validation functions for prepared-animal-feed (mixing and
  pelletizing) manufacturing production parameters. These are called
  by the Governor to independently verify physical/operational
  feed-safety critical-control-point constraints -- the advisor's
  confidence is NOT sufficient to override these checks.

  All functions here are pure arithmetic/set/boolean predicates with no
  host-clock or I/O calls, so this namespace stays trivially portable
  across Clojure/ClojureScript. Callers that need the current time (see
  `metal-detector-calibration-overdue?`) obtain it themselves via a
  `:clj`/`:cljs` reader-conditional at the call site (see
  `feedops.governor`)."
  (:require [clojure.set :as set]))

(defn conditioning-temp-below-minimum?
  "Independently verify that the batch's actual steam-conditioning
  temperature did not fall below the product's minimum required
  temperature immediately before the pellet die. Below the product's
  minimum indicates the conditioning step may not have achieved
  sufficient pathogen (e.g. Salmonella) reduction -- a hard
  feed-safety hazard, never overridable by advisor confidence."
  [actual-celsius min-celsius]
  (< actual-celsius min-celsius))

(defn cooling-time-exceeds-max?
  "Independently verify that the batch's actual post-pellet cooling
  time (from die-exit temperature down to near-ambient in the
  counter-flow cooler) did not exceed the product's maximum allowed
  window. Exceeding the window risks condensation-driven mould growth
  and, for higher-fat rations, a self-heating/fire hazard from
  residual heat in bagged or bulk-stored product."
  [actual-minutes max-minutes]
  (> actual-minutes max-minutes))

(defn moisture-content-exceeds-max?
  "Independently verify that the batch's actual finished-product
  moisture content does not exceed the product's maximum allowable
  level. Excess moisture supports mould growth (and downstream
  mycotoxin production) during storage and transport."
  [actual-pct max-pct]
  (> actual-pct max-pct))

(defn mycotoxin-level-exceeds-max?
  "Independently verify that the batch's actual mycotoxin (aflatoxin)
  contamination does not exceed the product's maximum allowable level
  in parts per billion. The ceiling is species/class-specific --
  dairy and aquaculture rations carry materially lower action levels
  than finishing-swine/broiler rations because of human-food-chain
  carry-through (aflatoxin M1 in milk) or species sensitivity."
  [actual-ppb max-ppb]
  (> actual-ppb max-ppb))

(defn nutrient-deviation-exceeds-max?
  "Independently verify that the batch's actual relative deviation of
  an assayed guaranteed-analysis nutrient (e.g. crude protein) from
  its declared label value does not exceed the product's maximum
  allowable deviation. Exceeding it is a feed-labelling/guaranteed-
  analysis compliance failure, not merely a quality miss."
  [actual-deviation-pct max-deviation-pct]
  (> actual-deviation-pct max-deviation-pct))

(defn shelf-life-exceeded?
  "Independently verify that the batch's elapsed time since production
  has not exceeded the product's maximum shelf-life hours. Exceeding
  shelf life is a use-by-date violation -- fat oxidation (rancidity)
  and vitamin degradation have exhausted the product's safety/quality
  margin."
  [actual-hours-elapsed max-hours]
  (> actual-hours-elapsed max-hours))

(defn foreign-material-detected?
  "Independently verify a batch's foreign-material-detection result
  (metal, glass, or dense-plastic fragments caught by metal-detector
  inspection on the finished-pellet line). Any detection is a genuine
  physical hazard -- this predicate simply coerces the raw fact to a
  boolean so the Governor's check functions stay uniform in shape with
  every other independently-verified physical constraint in this
  namespace."
  [actual-detected?]
  (boolean actual-detected?))

(defn metal-detector-calibration-overdue?
  "Independently verify that the metal-detection inspection equipment
  on the finished-pellet/bagging line was calibrated within the last
  24 hours. Feed mills run frequent, shift-based recalibration on
  high-throughput continuous lines, so this actor's reference interval
  is daily. `last-calibration-epoch-ms` and `now-epoch-ms` are both
  epoch milliseconds -- callers obtain `now` via a `:clj`/`:cljs`
  reader-conditional, keeping this namespace free of any host-clock
  call."
  [last-calibration-epoch-ms now-epoch-ms]
  (> (- now-epoch-ms last-calibration-epoch-ms)
     (* 24 60 60 1000)))

(defn weight-variance-excessive?
  "Independently verify that a batch's finished-product weight variance
  (drift from target bag/bulk-load fill weight, in grams) does not
  exceed the maximum tolerance. Excessive variance indicates the
  bagging/loading line is out of calibration or the fill weight was
  measured incorrectly -- also a labelled-net-quantity compliance
  concern."
  [actual-variance-grams max-variance-grams]
  (> actual-variance-grams max-variance-grams))

(defn medicated-feed-cross-contact?
  "True when the batch's actual medicated-feed cross-contact risk set
  (e.g. drug carryover of an ionophore such as monensin or lasalocid,
  or a coccidiostat, present on shared mixing/pelletizing equipment
  from a prior medicated run) is not fully covered by
  `declared-medicated-status` (under-declaration risk -- a genuine
  feed-safety hazard: some medications are toxic to non-target species
  even at low carryover levels, e.g. ionophores to equids). Declaring
  MORE than the actual risk set is conservative and never a risk."
  [cross-contact-risk declared-medicated-status]
  (boolean
   (seq (set/difference (set cross-contact-risk) (set declared-medicated-status)))))

(defn sanitation-score-insufficient?
  "Independently verify that the plant's pre-production sanitation/
  pest-control score meets the minimum required. Score is 0-100,
  assessed by a third-party auditor against feed-safety sanitation and
  cross-contamination-control standards -- a significant concern for
  mixed-species feed mills running multiple rations and medications on
  shared equipment."
  [actual-score min-score-required]
  (< actual-score min-score-required))

(defn packaging-integrity-compromised?
  "Independently verify a batch's packaging integrity inspection
  result (bag seal or bulk-container closure). Compromised packaging
  allows moisture ingress that undermines the moisture-content and
  shelf-life calculations entirely. This predicate simply coerces the
  raw fact to a boolean so the Governor's check functions stay uniform
  in shape."
  [actual-compromised?]
  (boolean actual-compromised?))
