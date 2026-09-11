(ns feedops.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [feedops.facts :as facts]))

;; ──────────────────────── Product Type Lookups ──────────────────────

(deftest product-type-by-id-test
  (testing "poultry broiler pellet product type exists"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (some? p))
      (is (= (:id p) :feed/poultry-broiler-pellet))
      (is (= (:conditioning-temp-min-c p) 80.0))
      (is (= (:cooling-time-max-minutes p) 20.0))))

  (testing "swine grower pellet product type exists"
    (let [p (facts/product-type-by-id :feed/swine-grower-pellet)]
      (is (some? p))
      (is (= (:max-moisture-pct p) 13.0))
      (is (= (:max-mycotoxin-ppb p) 200.0))))

  (testing "dairy cattle TMR pellet product type exists"
    (let [p (facts/product-type-by-id :feed/dairy-cattle-tmr-pellet)]
      (is (some? p))
      ;; Dairy rations carry the strictest aflatoxin ceiling of the
      ;; product catalog (aflatoxin M1 milk carry-through).
      (is (= (:max-mycotoxin-ppb p) 20.0))
      (is (= (:max-nutrient-deviation-pct p) 8.0))))

  (testing "aquaculture pellet product type exists"
    (let [p (facts/product-type-by-id :feed/aquaculture-pellet)]
      (is (some? p))
      (is (= (:conditioning-temp-min-c p) 90.0))
      (is (= (:max-moisture-pct p) 10.0))
      (is (= (:max-shelf-life-hours p) 4320.0))))

  (testing "nonexistent product type returns nil"
    (is (nil? (facts/product-type-by-id :feed/nonexistent)))))

;; ──────────────────────── Jurisdiction Lookups ──────────────────────

(deftest jurisdiction-by-id-test
  (testing "US FDA-CVM jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :us/fda-cvm)]
      (is (some? j))
      (is (some #{:mycotoxin-test} (:required-evidence j)))))

  (testing "EU feed-hygiene jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :eu/feed-hygiene)]
      (is (some? j))
      (is (some #{:medicated-status-declaration} (:required-evidence j)))))

  (testing "JP MAFF jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :jp/maff)]
      (is (some? j))
      (is (some #{:packaging-integrity-check} (:required-evidence j)))))

  (testing "nonexistent jurisdiction returns nil"
    (is (nil? (facts/jurisdiction-by-id :xx/unknown)))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest required-evidence-satisfied-test
  (testing "complete evidence checklist passes"
    (let [j (facts/jurisdiction-by-id :us/fda-cvm)
          evidence [:raw-material-intake-record :mixing-uniformity-test :conditioning-temp-log :cooling-time-log
                    :moisture-test :mycotoxin-test :nutrient-assay :medicated-status-declaration :weight-check
                    :packaging-integrity-check]]
      (is (true? (facts/required-evidence-satisfied? j evidence)))))

  (testing "incomplete evidence fails"
    (let [j (facts/jurisdiction-by-id :us/fda-cvm)
          evidence [:raw-material-intake-record :mixing-uniformity-test]]
      (is (false? (facts/required-evidence-satisfied? j evidence)))))

  (testing "accepts a raw jurisdiction id in place of a resolved map"
    (let [evidence [:raw-material-intake-record :mixing-uniformity-test :conditioning-temp-log :cooling-time-log
                    :moisture-test :mycotoxin-test :nutrient-assay :medicated-status-declaration :weight-check
                    :packaging-integrity-check]]
      (is (true? (facts/required-evidence-satisfied? :us/fda-cvm evidence))))))

;; ──────────────────────── Processing Safety Predicates ──────────────────────

(deftest conditioning-temp-meets-minimum-test
  (testing "conditioning temp at or above minimum passes"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (true? (facts/conditioning-temp-meets-minimum? 80.0 p)))
      (is (true? (facts/conditioning-temp-meets-minimum? 90.0 p)))))

  (testing "conditioning temp below minimum fails"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (false? (facts/conditioning-temp-meets-minimum? 65.0 p))))))

(deftest cooling-time-within-max-test
  (testing "cooling time at or below max passes"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (true? (facts/cooling-time-within-max? 20.0 p)))
      (is (true? (facts/cooling-time-within-max? 10.0 p)))))

  (testing "cooling time above max fails"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (false? (facts/cooling-time-within-max? 45.0 p))))))

(deftest moisture-content-within-max-test
  (testing "moisture content at or below max passes"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (true? (facts/moisture-content-within-max? 12.5 p)))
      (is (true? (facts/moisture-content-within-max? 9.0 p)))))

  (testing "moisture content above max fails"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (false? (facts/moisture-content-within-max? 15.0 p))))))

(deftest mycotoxin-level-within-max-test
  (testing "mycotoxin level at or below max passes"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (true? (facts/mycotoxin-level-within-max? 100.0 p)))
      (is (true? (facts/mycotoxin-level-within-max? 40.0 p)))))

  (testing "mycotoxin level above max fails"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (false? (facts/mycotoxin-level-within-max? 150.0 p)))))

  (testing "dairy ration's stricter ceiling fails at a level the broiler ration would pass"
    (let [dairy (facts/product-type-by-id :feed/dairy-cattle-tmr-pellet)]
      (is (false? (facts/mycotoxin-level-within-max? 50.0 dairy))))))

(deftest nutrient-deviation-within-max-test
  (testing "nutrient deviation at or below max passes"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (true? (facts/nutrient-deviation-within-max? 10.0 p)))
      (is (true? (facts/nutrient-deviation-within-max? 3.0 p)))))

  (testing "nutrient deviation above max fails"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (false? (facts/nutrient-deviation-within-max? 15.0 p))))))

(deftest shelf-life-within-max-test
  (testing "elapsed hours at or below max passes"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (true? (facts/shelf-life-within-max? 2160.0 p)))
      (is (true? (facts/shelf-life-within-max? 24.0 p)))))

  (testing "elapsed hours above max fails"
    (let [p (facts/product-type-by-id :feed/poultry-broiler-pellet)]
      (is (false? (facts/shelf-life-within-max? 3000.0 p))))))
