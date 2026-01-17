(ns notification.retry-test
  "Tests for retry backoff logic."
  (:require [clojure.test :refer [deftest testing is]]
            [notification.shared.retry :as retry]))

;; =============================================================================
;; Backoff Calculation Tests
;; =============================================================================

(deftest calculate-backoff-test
  (testing "first attempt has delay around base (with jitter)"
    (let [delay (retry/calculate-backoff 0 1000 60000)]
      (is (>= delay 1000))
      (is (< delay 1200))))  ;; Allow 10% jitter + some margin
  
  (testing "second attempt has delay around 2x base"
    (let [delay (retry/calculate-backoff 1 1000 60000)]
      (is (>= delay 2000))
      (is (< delay 2400))))
  
  (testing "third attempt has delay around 4x base"
    (let [delay (retry/calculate-backoff 2 1000 60000)]
      (is (>= delay 4000))
      (is (< delay 4800))))
  
  (testing "respects max delay"
    (let [delay (retry/calculate-backoff 10 1000 60000)]
      (is (<= delay 60000))))
  
  (testing "exponential growth"
    (let [delays (map #(retry/calculate-backoff % 1000 60000) (range 5))]
      ;; Check exponential pattern (each roughly 2x previous)
      (is (< (nth delays 0) (nth delays 1)))
      (is (< (nth delays 1) (nth delays 2)))
      (is (< (nth delays 2) (nth delays 3)))
      (is (< (nth delays 3) (nth delays 4))))))

;; =============================================================================
;; Retry Decision Tests
;; =============================================================================

(deftest should-retry?-test
  (testing "should retry on first failure"
    (is (true? (retry/should-retry? 1 3 :delivery-failed))))
  
  (testing "should retry on second failure"
    (is (true? (retry/should-retry? 2 3 :delivery-failed))))
  
  (testing "should not retry at max attempts"
    (is (false? (retry/should-retry? 3 3 :delivery-failed))))
  
  (testing "should not retry beyond max attempts"
    (is (false? (retry/should-retry? 5 3 :delivery-failed))))
  
  (testing "should not retry non-retryable errors"
    (is (false? (retry/should-retry? 1 3 :invalid-recipient)))
    (is (false? (retry/should-retry? 1 3 :template-not-found)))))

;; =============================================================================
;; Retry State Tests
;; =============================================================================

(deftest create-retry-state-test
  (testing "creates state with defaults"
    (let [state (retry/create-retry-state)]
      (is (= 0 (:attempts state)))
      (is (nil? (:last-error state)))
      (is (nil? (:next-retry-at state))))))

(deftest retry-exhausted?-test
  (testing "not exhausted when attempts below max"
    (is (false? (retry/retry-exhausted? 1 3)))
    (is (false? (retry/retry-exhausted? 2 3))))
  
  (testing "exhausted when attempts reach or exceed max"
    (is (true? (retry/retry-exhausted? 3 3)))
    (is (true? (retry/retry-exhausted? 5 3)))))
