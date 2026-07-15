(ns feedops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [feedops.governor :as governor]))

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private six-hours-ago (- now-ms (* 6 60 60 1000)))
(def ^:private two-days-ago (- now-ms (* 48 60 60 1000)))

(def ^:private clean-batch
  {:product-type :feed/poultry-broiler-pellet
   :jurisdiction :us/fda-cvm
   :conditioning-temp-c 85.0
   :cooling-time-minutes 15.0
   :moisture-pct 11.0
   :mycotoxin-ppb 50.0
   :nutrient-deviation-pct 5.0
   :shelf-life-hours-elapsed 24.0
   :foreign-material-detected? false
   :metal-detector-last-calibration-date six-hours-ago
   :weight-variance-grams 10
   :declared-medicated-status #{}
   :cross-contact-risk #{}
   :sanitation-score 85
   :packaging-compromised? false
   :evidence-checklist [:raw-material-intake-record :mixing-uniformity-test :conditioning-temp-log :cooling-time-log
                        :moisture-test :mycotoxin-test :nutrient-assay :medicated-status-declaration :weight-check
                        :packaging-integrity-check]})

;; ──────────────────────── Batch Registration (generalized) ──────────────────────

(deftest batch-not-registered-violation-test
  (testing "log-production-batch against an unregistered batch is a hard violation"
    (let [req {:op :log-production-batch :subject "batch-ghost"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop {})]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :batch-not-registered) (:violations result)))))

  (testing "schedule-maintenance against an unregistered batch is also a hard violation"
    (let [req {:op :schedule-maintenance :subject "batch-ghost"}
          prop {:cites [] :value {} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop {})]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :batch-not-registered) (:violations result)))))

  (testing "a registered batch does not trigger this rule"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :schedule-maintenance :subject batch-id}
          prop {:cites [] :value {} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :batch-not-registered) (:violations result)))))))

;; ──────────────────────── Hard Violations ──────────────────────

(deftest spec-basis-violation-test
  (testing "proposal with no jurisdiction citation is a hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [] :value {:jurisdiction nil}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :no-spec-basis) (:violations result)))))

  (testing "proposal with proper citation passes spec basis check"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Conditioning Temperature Violations ──────────────────────

(deftest conditioning-temp-violation-test
  (testing "batch with conditioning temp below the product's minimum triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :conditioning-temp-c 65.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :conditioning-temp-below-minimum) (:violations result)))))

  (testing "batch with conditioning temp at/above minimum passes"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Post-Pellet Cooling Time Violations ──────────────────────

(deftest cooling-time-violation-test
  (testing "batch with cooling time exceeding the product's maximum triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :cooling-time-minutes 45.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :cooling-time-exceeds-max) (:violations result))))))

;; ──────────────────────── Moisture Content Violations ──────────────────────

(deftest moisture-content-violation-test
  (testing "batch with moisture content exceeding the product's window triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :moisture-pct 15.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :moisture-content-exceeds-max) (:violations result)))))

  (testing "aquaculture pellet uses a much lower moisture window than the broiler pellet"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch
                                            :product-type :feed/aquaculture-pellet
                                            :conditioning-temp-c 92.0
                                            :cooling-time-minutes 10.0
                                            :mycotoxin-ppb 10.0
                                            :nutrient-deviation-pct 3.0
                                            :moisture-pct 11.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :moisture-content-exceeds-max) (:violations result))))))

;; ──────────────────────── Mycotoxin (Aflatoxin) Violations ──────────────────────

(deftest mycotoxin-level-violation-test
  (testing "batch with mycotoxin level exceeding the product's maximum triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :mycotoxin-ppb 150.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :mycotoxin-level-exceeds-max) (:violations result)))))

  (testing "dairy ration's stricter aflatoxin ceiling triggers hard violation at a level the broiler ration would pass"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch
                                            :product-type :feed/dairy-cattle-tmr-pellet
                                            :conditioning-temp-c 79.0
                                            :cooling-time-minutes 20.0
                                            :nutrient-deviation-pct 3.0
                                            :mycotoxin-ppb 50.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :mycotoxin-level-exceeds-max) (:violations result))))))

;; ──────────────────────── Nutrient Deviation Violations ──────────────────────

(deftest nutrient-deviation-violation-test
  (testing "batch with nutrient deviation exceeding the product's maximum triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :nutrient-deviation-pct 15.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :nutrient-deviation-exceeds-max) (:violations result))))))

;; ──────────────────────── Foreign Material Violations ──────────────────────

(deftest foreign-material-violation-test
  (testing "batch with detected foreign material triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :foreign-material-detected? true)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :foreign-material-detected) (:violations result))))))

;; ──────────────────────── Metal Detector Calibration Violations ──────────────────────

(deftest metal-detector-calibration-violation-test
  (testing "batch with overdue metal-detector calibration triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :metal-detector-last-calibration-date two-days-ago)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :metal-detector-calibration-overdue) (:violations result))))))

;; ──────────────────────── Weight Variance Violations ──────────────────────

(deftest weight-variance-violation-test
  (testing "batch with excessive weight variance triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :weight-variance-grams 30)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :weight-variance-excessive) (:violations result))))))

;; ──────────────────────── Medicated-Feed Cross-Contact Violations ──────────────────────

(deftest medicated-feed-cross-contact-violation-test
  (testing "cross-contact risk without a matching declaration triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :cross-contact-risk #{:monensin :lasalocid} :declared-medicated-status #{})}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :medicated-feed-cross-contact) (:violations result)))))

  (testing "cross-contact risk WITH a complete declaration passes"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch :cross-contact-risk #{:monensin :lasalocid} :declared-medicated-status #{:monensin :lasalocid})}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :medicated-feed-cross-contact) (:violations result)))))))

;; ──────────────────────── Sanitation Score Violations ──────────────────────

(deftest sanitation-score-violation-test
  (testing "batch with insufficient sanitation score triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :sanitation-score 60)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :sanitation-score-insufficient) (:violations result))))))

;; ──────────────────────── Packaging Integrity Violations ──────────────────────

(deftest packaging-integrity-violation-test
  (testing "batch with compromised packaging triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :packaging-compromised? true)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :packaging-integrity-compromised) (:violations result))))))

;; ──────────────────────── Shelf Life Violations ──────────────────────

(deftest shelf-life-violation-test
  (testing "batch with elapsed time exceeding the product's shelf life triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :shelf-life-hours-elapsed 3000.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :shelf-life-exceeded) (:violations result))))))

;; ──────────────────────── Food-Safety Flag Violations ──────────────────────

(deftest food-safety-flag-unresolved-violation-test
  (testing "batch with an unresolved food-safety flag triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch
                                            :safety-concern-raised? true
                                            :safety-concern-resolved? false)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :food-safety-flag-unresolved) (:violations result)))))

  (testing "batch with a resolved food-safety flag does not trigger this rule"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch
                                            :safety-concern-raised? true
                                            :safety-concern-resolved? true)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :food-safety-flag-unresolved) (:violations result)))))))

;; ──────────────────────── Escalation (Low Confidence) ──────────────────────

(deftest low-confidence-escalation-test
  (testing "low confidence proposal escalates even when hard checks pass"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :schedule-maintenance :subject batch-id}
          prop {:cites [{:spec "Equipment-Manual"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.5}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── High Stakes Escalation ──────────────────────

(deftest high-stakes-escalation-test
  (testing "log-production-batch escalates even when all checks pass"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.95}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── Already Processed Violation ──────────────────────

(deftest already-processed-violation-test
  (testing "batch already processed triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :feed/poultry-broiler-pellet
                            :processed? true}}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "FDA-CVM-21-CFR-507"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :already-processed) (:violations result))))))

;; ──────────────────────── Already Shipment Finalized Violation ──────────────────────

(deftest already-shipment-finalized-violation-test
  (testing "batch with a shipment already finalized triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :feed/poultry-broiler-pellet
                            :shipment-finalized? true}}}
          req {:op :coordinate-shipment :subject batch-id}
          prop {:cites [{:spec "Shipment-Manual"}] :value {:jurisdiction :us/fda-cvm} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :already-shipment-finalized) (:violations result))))))
