(ns boundary.payments.core.provider-test
  "Unit tests for pure payment provider helper functions."
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.payments.core.provider :as provider]))

;; =============================================================================
;; cents->euro
;; =============================================================================

(deftest cents->euro-test
  (testing "converts whole euro amounts"
    (is (= "1.00"   (provider/cents->euro 100)))
    (is (= "10.00"  (provider/cents->euro 1000)))
    (is (= "119.00" (provider/cents->euro 11900))))

  (testing "converts fractional euro amounts"
    (is (= "0.01"  (provider/cents->euro 1)))
    (is (= "0.99"  (provider/cents->euro 99)))
    (is (= "19.99" (provider/cents->euro 1999))))

  (testing "handles zero"
    (is (= "0.00" (provider/cents->euro 0))))

  (testing "returns a string"
    (is (string? (provider/cents->euro 4900)))))

;; =============================================================================
;; normalize-event-type — Mollie
;; =============================================================================

(deftest normalize-event-type-mollie-test
  (testing "maps Mollie paid status"
    (is (= :payment.paid (provider/normalize-event-type "paid" :mollie))))

  (testing "maps Mollie authorized status"
    (is (= :payment.authorized (provider/normalize-event-type "authorized" :mollie))))

  (testing "maps Mollie failed status"
    (is (= :payment.failed (provider/normalize-event-type "failed" :mollie))))

  (testing "maps Mollie canceled (American spelling)"
    (is (= :payment.cancelled (provider/normalize-event-type "canceled" :mollie))))

  (testing "maps Mollie cancelled (British spelling)"
    (is (= :payment.cancelled (provider/normalize-event-type "cancelled" :mollie))))

  (testing "returns nil for unknown Mollie status"
    (is (nil? (provider/normalize-event-type "open" :mollie)))
    (is (nil? (provider/normalize-event-type "pending" :mollie)))
    (is (nil? (provider/normalize-event-type "" :mollie)))))

;; =============================================================================
;; normalize-event-type — Stripe
;; =============================================================================

(deftest normalize-event-type-stripe-test
  (testing "maps payment_intent.succeeded"
    (is (= :payment.paid (provider/normalize-event-type "payment_intent.succeeded" :stripe))))

  (testing "maps payment_intent.payment_failed"
    (is (= :payment.failed (provider/normalize-event-type "payment_intent.payment_failed" :stripe))))

  (testing "maps payment_intent.canceled"
    (is (= :payment.cancelled (provider/normalize-event-type "payment_intent.canceled" :stripe))))

  (testing "maps payment_intent.amount_capturable_updated"
    (is (= :payment.authorized (provider/normalize-event-type "payment_intent.amount_capturable_updated" :stripe))))

  (testing "returns nil for unknown Stripe event"
    (is (nil? (provider/normalize-event-type "charge.succeeded" :stripe)))
    (is (nil? (provider/normalize-event-type "customer.created" :stripe)))
    (is (nil? (provider/normalize-event-type "" :stripe)))))

;; =============================================================================
;; normalize-event-type — unknown provider
;; =============================================================================

(deftest normalize-event-type-unknown-provider-test
  (testing "returns nil for unknown provider"
    (is (nil? (provider/normalize-event-type "paid" :unknown-psp)))
    (is (nil? (provider/normalize-event-type "paid" nil)))))

;; =============================================================================
;; mollie-status->event-type (convenience wrapper)
;; =============================================================================

(deftest mollie-status->event-type-test
  (testing "delegates to normalize-event-type :mollie"
    (is (= :payment.paid      (provider/mollie-status->event-type "paid")))
    (is (= :payment.failed    (provider/mollie-status->event-type "failed")))
    (is (= :payment.cancelled (provider/mollie-status->event-type "canceled")))
    (is (= :payment.cancelled (provider/mollie-status->event-type "cancelled")))
    (is (nil?                  (provider/mollie-status->event-type "open")))))

;; =============================================================================
;; stripe-event->event-type (convenience wrapper)
;; =============================================================================

(deftest stripe-event->event-type-test
  (testing "delegates to normalize-event-type :stripe"
    (is (= :payment.paid       (provider/stripe-event->event-type "payment_intent.succeeded")))
    (is (= :payment.failed     (provider/stripe-event->event-type "payment_intent.payment_failed")))
    (is (= :payment.cancelled  (provider/stripe-event->event-type "payment_intent.canceled")))
    (is (= :payment.authorized (provider/stripe-event->event-type "payment_intent.amount_capturable_updated")))
    (is (nil?                   (provider/stripe-event->event-type "checkout.session.completed")))))
