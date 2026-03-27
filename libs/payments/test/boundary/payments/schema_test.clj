(ns boundary.payments.schema-test
  "Unit tests for payment Malli schemas."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [boundary.payments.schema :as schema]))

;; =============================================================================
;; CheckoutRequest
;; =============================================================================

(deftest checkout-request-schema-test
  (testing "accepts a valid checkout request"
    (is (m/validate schema/CheckoutRequest
                    {:amount-cents 4900
                     :currency     "EUR"
                     :description  "Test product"
                     :redirect-url "https://example.com/done"})))

  (testing "accepts optional fields"
    (is (m/validate schema/CheckoutRequest
                    {:amount-cents  11900
                     :currency      "USD"
                     :description   "Premium plan"
                     :redirect-url  "https://example.com/return"
                     :webhook-url   "https://example.com/webhook"
                     :metadata      {:order-id "abc-123"}})))

  (testing "rejects missing amount-cents"
    (is (not (m/validate schema/CheckoutRequest
                         {:currency    "EUR"
                          :description "Test"
                          :redirect-url "https://example.com"}))))

  (testing "rejects zero amount-cents (must be pos-int)"
    (is (not (m/validate schema/CheckoutRequest
                         {:amount-cents 0
                          :currency    "EUR"
                          :description "Test"
                          :redirect-url "https://example.com"}))))

  (testing "rejects non-integer amount-cents"
    (is (not (m/validate schema/CheckoutRequest
                         {:amount-cents 49.0
                          :currency    "EUR"
                          :description "Test"
                          :redirect-url "https://example.com"}))))

  (testing "rejects currency shorter than 3 chars"
    (is (not (m/validate schema/CheckoutRequest
                         {:amount-cents 100
                          :currency    "EU"
                          :description "Test"
                          :redirect-url "https://example.com"}))))

  (testing "rejects currency longer than 3 chars"
    (is (not (m/validate schema/CheckoutRequest
                         {:amount-cents 100
                          :currency    "EURO"
                          :description "Test"
                          :redirect-url "https://example.com"})))))

;; =============================================================================
;; CheckoutResult
;; =============================================================================

(deftest checkout-result-schema-test
  (testing "accepts a valid checkout result"
    (is (m/validate schema/CheckoutResult
                    {:checkout-url         "https://psp.example.com/pay/abc"
                     :provider-checkout-id "cs_test_abc123"})))

  (testing "rejects missing checkout-url"
    (is (not (m/validate schema/CheckoutResult
                         {:provider-checkout-id "cs_test_abc123"}))))

  (testing "rejects missing provider-checkout-id"
    (is (not (m/validate schema/CheckoutResult
                         {:checkout-url "https://psp.example.com/pay/abc"})))))

;; =============================================================================
;; PaymentStatusResult
;; =============================================================================

(deftest payment-status-result-schema-test
  (testing "accepts all valid statuses"
    (doseq [status [:pending :paid :failed :cancelled]]
      (is (m/validate schema/PaymentStatusResult {:status status})
          (str "should accept status " status))))

  (testing "accepts optional provider-payment-id"
    (is (m/validate schema/PaymentStatusResult
                    {:status              :paid
                     :provider-payment-id "pi_abc123"})))

  (testing "rejects unknown status"
    (is (not (m/validate schema/PaymentStatusResult {:status :unknown}))))

  (testing "rejects missing status"
    (is (not (m/validate schema/PaymentStatusResult {})))))

;; =============================================================================
;; WebhookResult
;; =============================================================================

(deftest webhook-result-schema-test
  (testing "accepts all valid event types"
    (doseq [event-type [:payment.paid :payment.failed :payment.cancelled :payment.authorized]]
      (is (m/validate schema/WebhookResult
                      {:event-type event-type :payload {}})
          (str "should accept event-type " event-type))))

  (testing "accepts optional fields"
    (is (m/validate schema/WebhookResult
                    {:event-type            :payment.paid
                     :provider-payment-id   "pi_abc"
                     :provider-checkout-id  "cs_abc"
                     :payload               {:id "evt_abc"}})))

  (testing "rejects unknown event-type"
    (is (not (m/validate schema/WebhookResult
                         {:event-type :payment.refunded :payload {}}))))

  (testing "rejects missing payload"
    (is (not (m/validate schema/WebhookResult {:event-type :payment.paid}))))

  (testing "rejects missing event-type"
    (is (not (m/validate schema/WebhookResult {:payload {}})))))
