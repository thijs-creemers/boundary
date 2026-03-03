(ns boundary.external.core.stripe-test
  (:require [boundary.external.core.stripe :as stripe]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; build-payment-intent-params
;; =============================================================================

(deftest build-payment-intent-params-test
  ^:unit
  (testing "minimum fields"
    (let [params (stripe/build-payment-intent-params {:amount 1000 :currency "eur"})]
      (is (= "1000" (get params "amount")))
      (is (= "eur"  (get params "currency")))
      (is (nil? (get params "description")))))

  (testing "with description"
    (let [params (stripe/build-payment-intent-params
                  {:amount 500 :currency "usd" :description "Order #42"})]
      (is (= "Order #42" (get params "description")))))

  (testing "with customer-id"
    (let [params (stripe/build-payment-intent-params
                  {:amount 500 :currency "usd" :customer-id "cus_123"})]
      (is (= "cus_123" (get params "customer")))))

  (testing "with metadata flattened to form params"
    (let [params (stripe/build-payment-intent-params
                  {:amount 100 :currency "eur" :metadata {:order-id "42" :env "test"}})]
      (is (= "42"   (get params "metadata[order-id]")))
      (is (= "test" (get params "metadata[env]"))))))

;; =============================================================================
;; parse-payment-intent
;; =============================================================================

(deftest parse-payment-intent-test
  ^:unit
  (testing "parses camelCase keys to kebab-case"
    (let [body   {"id"            "pi_123"
                  "status"        "requires_payment_method"
                  "amount"        1000
                  "currency"      "eur"
                  "client_secret" "pi_123_secret_abc"
                  "created"       1700000000
                  "metadata"      {"order" "1"}}
          result (stripe/parse-payment-intent body)]
      (is (= "pi_123"                    (:id result)))
      (is (= "requires_payment_method"   (:status result)))
      (is (= 1000                        (:amount result)))
      (is (= "eur"                       (:currency result)))
      (is (= "pi_123_secret_abc"         (:client-secret result)))
      (is (instance? java.util.Date      (:created-at result)))
      (is (= {"order" "1"}               (:metadata result)))))

  (testing "epoch timestamp converted to Date"
    (let [result (stripe/parse-payment-intent {"id" "x" "status" "s" "amount" 0
                                               "currency" "usd" "created" 0})]
      (is (= 0 (.getTime (:created-at result)))))))

;; =============================================================================
;; parse-error-response
;; =============================================================================

(deftest parse-error-response-test
  ^:unit
  (testing "extracts error fields"
    (let [body   {"error" {"message" "Your card was declined."
                           "type"    "card_error"
                           "code"    "card_declined"}}
          result (stripe/parse-error-response body 402)]
      (is (= "Your card was declined." (:message result)))
      (is (= "card_error"              (:type result)))
      (is (= "card_declined"           (:code result)))
      (is (= 402                       (:status-code result)))))

  (testing "defaults when error key missing"
    (let [result (stripe/parse-error-response {} 500)]
      (is (= "Unknown Stripe error" (:message result)))
      (is (= "api_error"            (:type result))))))

;; =============================================================================
;; format-amount
;; =============================================================================

(deftest format-amount-test
  ^:unit
  (testing "EUR cents to display"
    (is (= "€ 10.00" (stripe/format-amount 1000 "eur"))))

  (testing "USD cents to display"
    (is (= "$ 1.50" (stripe/format-amount 150 "usd"))))

  (testing "JPY zero-decimal"
    (let [result (stripe/format-amount 1000 "jpy")]
      (is (.contains result "1000"))))

  (testing "unknown currency uses uppercase code"
    (let [result (stripe/format-amount 500 "chf")]
      (is (.contains result "CHF")))))

;; =============================================================================
;; verify-stripe-signature
;; =============================================================================

(deftest verify-stripe-signature-test
  ^:unit
  (let [secret  "whsec_test_secret"
        payload "{\"id\":\"evt_test\"}"
        epoch   1700000000]

    (testing "valid signature returns {:valid? true}"
      ;; Compute a valid signature manually to use in assertion
      (let [ts-str  (str epoch)
            signed  (str ts-str "." payload)
            ;; Use the same HMAC logic as the implementation
            key-bytes (.getBytes secret "UTF-8")
            mac       (doto (javax.crypto.Mac/getInstance "HmacSHA256")
                        (.init (javax.crypto.spec.SecretKeySpec. key-bytes "HmacSHA256")))
            hex       (apply str (map #(format "%02x" (bit-and % 0xff))
                                      (.doFinal mac (.getBytes signed "UTF-8"))))
            sig-header (str "t=" ts-str ",v1=" hex)
            result     (stripe/verify-stripe-signature payload sig-header secret epoch)]
        (is (true? (:valid? result)))))

    (testing "expired timestamp returns {:valid? false}"
      (let [old-epoch (- epoch 400) ; 400s ago, > 300s tolerance
            ts-str    (str old-epoch)
            signed    (str ts-str "." payload)
            key-bytes (.getBytes secret "UTF-8")
            mac       (doto (javax.crypto.Mac/getInstance "HmacSHA256")
                        (.init (javax.crypto.spec.SecretKeySpec. key-bytes "HmacSHA256")))
            hex       (apply str (map #(format "%02x" (bit-and % 0xff))
                                      (.doFinal mac (.getBytes signed "UTF-8"))))
            sig-header (str "t=" ts-str ",v1=" hex)
            result     (stripe/verify-stripe-signature payload sig-header secret epoch)]
        (is (false? (:valid? result)))
        (is (some? (:error result)))))

    (testing "wrong secret returns {:valid? false}"
      (let [ts-str    (str epoch)
            signed    (str ts-str "." payload)
            key-bytes (.getBytes "wrong-secret" "UTF-8")
            mac       (doto (javax.crypto.Mac/getInstance "HmacSHA256")
                        (.init (javax.crypto.spec.SecretKeySpec. key-bytes "HmacSHA256")))
            hex       (apply str (map #(format "%02x" (bit-and % 0xff))
                                      (.doFinal mac (.getBytes signed "UTF-8"))))
            sig-header (str "t=" ts-str ",v1=" hex)
            result     (stripe/verify-stripe-signature payload sig-header secret epoch)]
        (is (false? (:valid? result)))))

    (testing "malformed header returns {:valid? false}"
      (let [result (stripe/verify-stripe-signature payload "bad-header" secret epoch)]
        (is (false? (:valid? result)))))))
