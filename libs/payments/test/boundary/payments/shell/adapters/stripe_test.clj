(ns boundary.payments.shell.adapters.stripe-test
  "Integration tests for the Stripe payment provider adapter.
   All tests run without network access — only local HMAC and JSON parsing are exercised."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest testing is]]
            [boundary.payments.ports :as ports]
            [boundary.payments.shell.adapters.stripe :as stripe])
  (:import [java.nio.charset StandardCharsets]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

;; =============================================================================
;; Test helpers
;; =============================================================================

(def ^:private test-secret "whsec_test_signing_secret_32chars!")

(def ^:private provider
  (stripe/->StripePaymentProvider "sk_test_key" test-secret))

(defn- compute-signature
  "Replicate the Stripe HMAC-SHA256 signature computation for use in tests."
  [raw-body timestamp secret]
  (let [signed-payload (str timestamp "." raw-body)
        mac            (Mac/getInstance "HmacSHA256")
        key            (SecretKeySpec. (.getBytes ^String secret StandardCharsets/UTF_8)
                                       "HmacSHA256")]
    (.init mac key)
    (apply str (map #(format "%02x" (bit-and % 0xff))
                    (.doFinal mac (.getBytes ^String signed-payload StandardCharsets/UTF_8))))))

(defn- stripe-sig-header [body timestamp secret]
  (str "t=" timestamp ",v1=" (compute-signature body timestamp secret)))

(def ^:private test-timestamp "1700000000")

(def ^:private paid-event
  {:type "payment_intent.succeeded"
   :data {:object {:id       "pi_test_123"
                   :metadata {:checkout_id "cs_test_abc"}}}})

;; =============================================================================
;; verify-webhook-signature
;; =============================================================================

(deftest verify-webhook-signature-test
  (let [body (json/generate-string paid-event)]

    (testing "returns true for a valid signature — lowercase header key"
      (let [sig (stripe-sig-header body test-timestamp test-secret)]
        (is (true? (ports/verify-webhook-signature
                    provider body {"stripe-signature" sig})))))

    (testing "returns true for a valid signature — titlecase header key"
      (let [sig (stripe-sig-header body test-timestamp test-secret)]
        (is (true? (ports/verify-webhook-signature
                    provider body {"Stripe-Signature" sig})))))

    (testing "returns false when signature is wrong"
      (let [sig (stripe-sig-header body test-timestamp "wrong-secret-00000000000000000")]
        (is (false? (ports/verify-webhook-signature
                     provider body {"stripe-signature" sig})))))

    (testing "returns false when Stripe-Signature header is absent"
      (is (false? (ports/verify-webhook-signature provider body {}))))

    (testing "returns false when header is nil"
      (is (false? (ports/verify-webhook-signature provider body {"stripe-signature" nil}))))

    (testing "returns false when body is empty and signature does not match"
      (let [sig (stripe-sig-header body test-timestamp test-secret)]
        (is (false? (ports/verify-webhook-signature
                     provider "" {"stripe-signature" sig})))))))

;; =============================================================================
;; process-webhook — event type mapping
;; =============================================================================

(deftest process-webhook-event-types-test
  (testing "payment_intent.succeeded → :payment.paid"
    (let [body (json/generate-string {:type "payment_intent.succeeded"
                                      :data {:object {:id "pi_1" :metadata {}}}})
          result (ports/process-webhook provider body {})]
      (is (= :payment.paid (:event-type result)))))

  (testing "payment_intent.payment_failed → :payment.failed"
    (let [body (json/generate-string {:type "payment_intent.payment_failed"
                                      :data {:object {:id "pi_2" :metadata {}}}})
          result (ports/process-webhook provider body {})]
      (is (= :payment.failed (:event-type result)))))

  (testing "payment_intent.canceled → :payment.cancelled"
    (let [body (json/generate-string {:type "payment_intent.canceled"
                                      :data {:object {:id "pi_3" :metadata {}}}})
          result (ports/process-webhook provider body {})]
      (is (= :payment.cancelled (:event-type result)))))

  (testing "payment_intent.amount_capturable_updated → :payment.authorized"
    (let [body (json/generate-string {:type "payment_intent.amount_capturable_updated"
                                      :data {:object {:id "pi_4" :metadata {}}}})
          result (ports/process-webhook provider body {})]
      (is (= :payment.authorized (:event-type result)))))

  (testing "unknown event type throws ex-info with :internal-error"
    (let [body (json/generate-string {:type "charge.succeeded"
                                      :data {:object {}}})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Unhandled Stripe event type"
           (ports/process-webhook provider body {})))
      (try
        (ports/process-webhook provider body {})
        (catch clojure.lang.ExceptionInfo e
          (is (= :internal-error (:type (ex-data e)))))))))

;; =============================================================================
;; process-webhook — field extraction
;; =============================================================================

(deftest process-webhook-field-extraction-test
  (let [body   (json/generate-string paid-event)
        result (ports/process-webhook provider body {})]

    (testing "extracts provider-payment-id from data.object.id"
      (is (= "pi_test_123" (:provider-payment-id result))))

    (testing "extracts provider-checkout-id from data.object.metadata.checkout_id"
      (is (= "cs_test_abc" (:provider-checkout-id result))))

    (testing "payload contains the full parsed event"
      (is (= "payment_intent.succeeded" (get-in result [:payload :type]))))

    (testing "accepts a pre-parsed map body"
      (let [result (ports/process-webhook provider paid-event {})]
        (is (= :payment.paid (:event-type result)))
        (is (= "pi_test_123" (:provider-payment-id result)))))))
