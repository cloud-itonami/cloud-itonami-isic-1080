(ns feedops.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [feedops.registry :as registry]))

;; ──────────────────────── Conditioning Temperature ──────────────────────

(deftest conditioning-temp-below-minimum-test
  (testing "conditioning temp at minimum returns false (no violation)"
    (is (false? (registry/conditioning-temp-below-minimum? 80.0 80.0))))

  (testing "conditioning temp above minimum returns false"
    (is (false? (registry/conditioning-temp-below-minimum? 90.0 80.0))))

  (testing "conditioning temp below minimum returns true (violation)"
    (is (true? (registry/conditioning-temp-below-minimum? 65.0 80.0)))))

;; ──────────────────────── Post-Pellet Cooling Time ──────────────────────

(deftest cooling-time-exceeds-max-test
  (testing "cooling time within max returns false (no violation)"
    (is (false? (registry/cooling-time-exceeds-max? 10.0 20.0))))

  (testing "cooling time at max returns false"
    (is (false? (registry/cooling-time-exceeds-max? 20.0 20.0))))

  (testing "cooling time exceeding max returns true (violation)"
    (is (true? (registry/cooling-time-exceeds-max? 45.0 20.0)))))

;; ──────────────────────── Moisture Content ──────────────────────

(deftest moisture-content-exceeds-max-test
  (testing "moisture content within max returns false (no violation)"
    (is (false? (registry/moisture-content-exceeds-max? 9.0 12.5))))

  (testing "moisture content at max returns false"
    (is (false? (registry/moisture-content-exceeds-max? 12.5 12.5))))

  (testing "moisture content exceeding max returns true (violation)"
    (is (true? (registry/moisture-content-exceeds-max? 15.0 12.5)))))

;; ──────────────────────── Mycotoxin (Aflatoxin) Level ──────────────────────

(deftest mycotoxin-level-exceeds-max-test
  (testing "mycotoxin level within max returns false (no violation)"
    (is (false? (registry/mycotoxin-level-exceeds-max? 40.0 100.0))))

  (testing "mycotoxin level at max returns false"
    (is (false? (registry/mycotoxin-level-exceeds-max? 100.0 100.0))))

  (testing "mycotoxin level exceeding max returns true (violation)"
    (is (true? (registry/mycotoxin-level-exceeds-max? 150.0 100.0)))))

;; ──────────────────────── Nutrient Deviation ──────────────────────

(deftest nutrient-deviation-exceeds-max-test
  (testing "nutrient deviation within max returns false (no violation)"
    (is (false? (registry/nutrient-deviation-exceeds-max? 3.0 10.0))))

  (testing "nutrient deviation at max returns false"
    (is (false? (registry/nutrient-deviation-exceeds-max? 10.0 10.0))))

  (testing "nutrient deviation exceeding max returns true (violation)"
    (is (true? (registry/nutrient-deviation-exceeds-max? 15.0 10.0)))))

;; ──────────────────────── Shelf Life ──────────────────────

(deftest shelf-life-exceeded-test
  (testing "elapsed hours within max returns false (no violation)"
    (is (false? (registry/shelf-life-exceeded? 24.0 2160.0))))

  (testing "elapsed hours at max returns false"
    (is (false? (registry/shelf-life-exceeded? 2160.0 2160.0))))

  (testing "elapsed hours exceeding max returns true (violation)"
    (is (true? (registry/shelf-life-exceeded? 3000.0 2160.0)))))

;; ──────────────────────── Foreign Material ──────────────────────

(deftest foreign-material-detected-test
  (testing "no detection returns false"
    (is (false? (registry/foreign-material-detected? false)))
    (is (false? (registry/foreign-material-detected? nil))))

  (testing "detection returns true"
    (is (true? (registry/foreign-material-detected? true)))))

;; ──────────────────────── Metal Detector Calibration ──────────────────────

(deftest metal-detector-calibration-overdue-test
  (testing "recent calibration returns false (no violation)"
    ;; Assume calibrated 6 hours ago (within the 24-hour shift-based interval)
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          six-hours-ago (- now (* 6 60 60 1000))]
      (is (false? (registry/metal-detector-calibration-overdue? six-hours-ago now)))))

  (testing "overdue calibration returns true (violation)"
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          two-days-ago (- now (* 48 60 60 1000))]
      (is (true? (registry/metal-detector-calibration-overdue? two-days-ago now))))))

;; ──────────────────────── Weight Variance ──────────────────────

(deftest weight-variance-excessive-test
  (testing "variance within tolerance returns false (no violation)"
    (is (false? (registry/weight-variance-excessive? 15 20))))

  (testing "variance at tolerance returns false"
    (is (false? (registry/weight-variance-excessive? 20 20))))

  (testing "variance exceeding tolerance returns true (violation)"
    (is (true? (registry/weight-variance-excessive? 21 20)))))

;; ──────────────────────── Medicated-Feed Cross-Contact ──────────────────────

(deftest medicated-feed-cross-contact-test
  (testing "no cross-contact risk returns false (no risk) regardless of declaration"
    (is (false? (registry/medicated-feed-cross-contact? #{} #{}))))

  (testing "cross-contact risk fully covered by declaration returns false (no risk)"
    (is (false? (registry/medicated-feed-cross-contact? #{:monensin :lasalocid} #{:monensin :lasalocid}))))

  (testing "declaring more than the actual risk set is conservative and returns false"
    (is (false? (registry/medicated-feed-cross-contact? #{:monensin} #{:monensin :lasalocid :narasin}))))

  (testing "cross-contact risk not fully covered by declaration returns true (risk)"
    (is (true? (registry/medicated-feed-cross-contact? #{:monensin :lasalocid} #{:monensin})))))

;; ──────────────────────── Sanitation Score ──────────────────────

(deftest sanitation-score-insufficient-test
  (testing "score at minimum returns false (no violation)"
    (is (false? (registry/sanitation-score-insufficient? 75 75))))

  (testing "score above minimum returns false"
    (is (false? (registry/sanitation-score-insufficient? 85 75))))

  (testing "score below minimum returns true (violation)"
    (is (true? (registry/sanitation-score-insufficient? 74 75)))))

;; ──────────────────────── Packaging Integrity ──────────────────────

(deftest packaging-integrity-compromised-test
  (testing "no compromise returns false"
    (is (false? (registry/packaging-integrity-compromised? false)))
    (is (false? (registry/packaging-integrity-compromised? nil))))

  (testing "compromised packaging returns true"
    (is (true? (registry/packaging-integrity-compromised? true)))))
