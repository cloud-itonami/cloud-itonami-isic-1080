(ns feedops.phase
  "Phase machine: the states a prepared-animal-feed (mixing and
  pelletizing) production batch transits through.

  State machine:
    :intake -> :mixing -> :pelletizing -> :cooling -> :package ->
    :inspect -> :audit -> :archived

  `:intake` is raw-material receiving (grain, protein meal,
  vitamin/mineral premix, medication where applicable); `:mixing` is
  batch mixing of raw ingredients to a homogeneous mash; `:pelletizing`
  is the steam-conditioning + die-extrusion step -- never directly
  controlled by this actor, mixing/pelletizing-line control remains
  exclusive to plant staff; `:cooling` is counter-flow cooling of the
  hot pellet down to near-ambient temperature -- never directly
  controlled by this actor; `:package` is bagging or bulk loading of
  the finished feed -- never directly controlled by this actor;
  `:inspect` is metal-detector and packaging-integrity inspection;
  `:audit` is compliance audit; `:archived` is the terminal state.

  Each transition can accept a proposal and yield an audit fact.")

(def all-phases
  "All valid phases in the prepared-animal-feed production workflow."
  [:intake :mixing :pelletizing :cooling :package :inspect :audit :archived])

(def phase-sequence
  "Ordered phases representing normal batch progression."
  [:intake :mixing :pelletizing :cooling :package :inspect :audit :archived])

(defn valid-phase?
  "Check if a phase is valid."
  [phase]
  (contains? (set all-phases) phase))

(defn- index-of
  "Portable (Clojure/ClojureScript) index lookup -- `.indexOf` is a
  JVM-only `java.util.List` method that ClojureScript's PersistentVector
  does not implement, so it is avoided here even though `phase-sequence`
  is a plain vector. Returns -1 when `x` is not found, matching
  `java.util.List/indexOf`'s contract."
  [coll x]
  (or (first (keep-indexed (fn [i v] (when (= v x) i)) coll)) -1))

(defn can-transition?
  "Check if a transition from one phase to another is valid
  (must be forward-only in the sequence, no backtracking). Always returns a
  boolean (never nil), including when either phase is invalid."
  [from-phase to-phase]
  (boolean
   (and (valid-phase? from-phase) (valid-phase? to-phase)
        (let [from-idx (index-of phase-sequence from-phase)
              to-idx (index-of phase-sequence to-phase)]
          (and (>= from-idx 0) (>= to-idx 0) (< from-idx to-idx))))))
