(ns feedops.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [feedops.phase :as phase]))

;; ──────────────────────── Phase Validity ──────────────────────

(deftest valid-phase-test
  (testing "intake is valid"
    (is (true? (phase/valid-phase? :intake))))

  (testing "pelletizing is valid"
    (is (true? (phase/valid-phase? :pelletizing))))

  (testing "archived is valid"
    (is (true? (phase/valid-phase? :archived))))

  (testing "invalid phase returns false"
    (is (false? (phase/valid-phase? :invalid)))))

;; ──────────────────────── Phase Transitions ──────────────────────

(deftest can-transition-test
  (testing "intake -> mixing is valid (forward progression)"
    (is (true? (phase/can-transition? :intake :mixing))))

  (testing "intake -> cooling is valid (skip mixing/pelletizing)"
    (is (true? (phase/can-transition? :intake :cooling))))

  (testing "pelletizing -> intake is invalid (backward)"
    (is (false? (phase/can-transition? :pelletizing :intake))))

  (testing "cooling -> archived is valid (forward to end)"
    (is (true? (phase/can-transition? :cooling :archived))))

  (testing "archived -> intake is invalid (backward from end)"
    (is (false? (phase/can-transition? :archived :intake))))

  (testing "same phase is invalid"
    (is (false? (phase/can-transition? :cooling :cooling))))

  (testing "invalid phases return false"
    (is (false? (phase/can-transition? :invalid :cooling)))
    (is (false? (phase/can-transition? :cooling :invalid)))))
