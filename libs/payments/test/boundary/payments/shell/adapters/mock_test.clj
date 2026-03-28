(ns boundary.payments.shell.adapters.mock-test
  "Integration tests for the Mock payment provider adapter."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [boundary.payments.ports :as ports]
            [boundary.payments.shell.adapters.mock :as mock]))

(def ^:private provider (mock/->MockPaymentProvider))

(def ^:private base-request
  {:amount-cents 4900
   :currency     "EUR"
   :description  "Test product"
   :redirect-url "https://example.com/done"})

;; =============================================================================
;; create-checkout-session
;; =============================================================================

(deftest ^:integration create-checkout-session-test
  (testing "returns checkout-url and provider-checkout-id"
    (let [result (ports/create-checkout-session provider base-request)]
      (is (string? (:checkout-url result)))
      (is (string? (:provider-checkout-id result)))))

  (testing "checkout-url is a local mock path"
    (let [{:keys [checkout-url]} (ports/create-checkout-session provider base-request)]
      (is (str/starts-with? checkout-url "/web/payment/mock-return?checkout-id="))))

  (testing "provider-checkout-id is a UUID string"
    (let [{:keys [provider-checkout-id]} (ports/create-checkout-session provider base-request)]
      (is (re-matches #"[0-9a-f-]{36}" provider-checkout-id))))

  (testing "each call generates a unique checkout-id"
    (let [id1 (:provider-checkout-id (ports/create-checkout-session provider base-request))
          id2 (:provider-checkout-id (ports/create-checkout-session provider base-request))]
      (is (not= id1 id2))))

  (testing "includes payment-id in URL when present in metadata"
    (let [result (ports/create-checkout-session provider
                                                (assoc base-request :metadata {:payment-id "order-42"}))]
      (is (str/includes? (:checkout-url result) "&payment-id=order-42"))))

  (testing "omits payment-id from URL when metadata is absent"
    (let [result (ports/create-checkout-session provider base-request)]
      (is (not (str/includes? (:checkout-url result) "payment-id")))))

  (testing "omits payment-id from URL when metadata has no :payment-id key"
    (let [result (ports/create-checkout-session provider
                                                (assoc base-request :metadata {:order-ref "xyz"}))]
      (is (not (str/includes? (:checkout-url result) "payment-id"))))))

;; =============================================================================
;; get-payment-status
;; =============================================================================

(deftest ^:integration get-payment-status-test
  (testing "always returns :paid status"
    (let [result (ports/get-payment-status provider "mock-checkout-123")]
      (is (= :paid (:status result)))))

  (testing "provider-payment-id is derived from checkout id"
    (let [result (ports/get-payment-status provider "mock-checkout-123")]
      (is (= "mock-payment-mock-checkout-123" (:provider-payment-id result))))))

;; =============================================================================
;; verify-webhook-signature
;; =============================================================================

(deftest ^:integration verify-webhook-signature-test
  (testing "always returns true regardless of body or headers"
    (is (true? (ports/verify-webhook-signature provider "" {})))
    (is (true? (ports/verify-webhook-signature provider "anything" {"Stripe-Signature" "wrong"})))
    (is (true? (ports/verify-webhook-signature provider nil nil)))))

;; =============================================================================
;; process-webhook
;; =============================================================================

(deftest ^:integration process-webhook-test
  (testing "returns :payment.paid event type"
    (let [result (ports/process-webhook provider "{}" {})]
      (is (= :payment.paid (:event-type result)))))

  (testing "extracts checkout-id from JSON string body"
    (let [result (ports/process-webhook provider
                                        "{\"checkout-id\": \"abc-123\"}" {})]
      (is (= "abc-123" (:provider-checkout-id result)))
      (is (str/starts-with? (:provider-payment-id result) "mock-payment-abc-123"))))

  (testing "processes a map body directly"
    (let [result (ports/process-webhook provider {:checkout-id "map-456"} {})]
      (is (= "map-456" (:provider-checkout-id result)))))

  (testing "generates a random id when checkout-id is absent"
    (let [result (ports/process-webhook provider "{}" {})]
      (is (string? (:provider-checkout-id result)))
      (is (string? (:provider-payment-id result)))))

  (testing "does not throw on invalid JSON"
    (is (map? (ports/process-webhook provider "not-json{{{" {}))))

  (testing "payload contains the parsed body"
    (let [result (ports/process-webhook provider "{\"foo\": \"bar\"}" {})]
      (is (= {:foo "bar"} (:payload result))))))
