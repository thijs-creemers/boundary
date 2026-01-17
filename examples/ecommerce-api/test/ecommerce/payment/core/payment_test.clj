(ns ecommerce.payment.core.payment-test
  (:require [clojure.test :refer [deftest is testing]]
            [ecommerce.payment.core.payment :as payment]))

;; =============================================================================
;; Payment Intent Creation Tests
;; =============================================================================

(deftest create-payment-intent-data-test
  (let [order {:id (random-uuid)
               :order-number "ORD-20260117-12345"
               :total-cents 5999
               :currency "EUR"
               :customer-email "test@example.com"}
        intent-data (payment/create-payment-intent-data order)]
    
    (testing "includes amount from order"
      (is (= 5999 (:amount intent-data))))
    
    (testing "includes currency"
      (is (= "EUR" (:currency intent-data))))
    
    (testing "includes metadata"
      (is (= (str (:id order)) (get-in intent-data [:metadata :order-id])))
      (is (= "ORD-20260117-12345" (get-in intent-data [:metadata :order-number])))
      (is (= "test@example.com" (get-in intent-data [:metadata :customer-email]))))))

;; =============================================================================
;; Webhook Event Processing Tests
;; =============================================================================

(deftest should-process-event?-test
  (testing "processes payment succeeded"
    (is (payment/should-process-event? "payment_intent.succeeded")))
  
  (testing "processes payment failed"
    (is (payment/should-process-event? "payment_intent.payment_failed")))
  
  (testing "processes payment canceled"
    (is (payment/should-process-event? "payment_intent.canceled")))
  
  (testing "ignores other events"
    (is (not (payment/should-process-event? "customer.created")))
    (is (not (payment/should-process-event? "charge.succeeded")))))

(deftest extract-payment-intent-id-test
  (let [event {:data {:object {:id "pi_test123"}}}]
    (is (= "pi_test123" (payment/extract-payment-intent-id event)))))

(deftest extract-order-id-test
  (let [order-id (random-uuid)
        event {:data {:object {:metadata {:order-id (str order-id)}}}}]
    (is (= order-id (payment/extract-order-id event))))
  
  (testing "returns nil for missing order-id"
    (let [event {:data {:object {:metadata {}}}}]
      (is (nil? (payment/extract-order-id event))))))

(deftest determine-order-action-test
  (testing "succeeded -> mark-paid"
    (is (= :mark-paid (payment/determine-order-action "payment_intent.succeeded"))))
  
  (testing "failed -> mark-failed"
    (is (= :mark-failed (payment/determine-order-action "payment_intent.payment_failed"))))
  
  (testing "canceled -> cancel"
    (is (= :cancel (payment/determine-order-action "payment_intent.canceled"))))
  
  (testing "unknown -> nil"
    (is (nil? (payment/determine-order-action "unknown.event")))))

;; =============================================================================
;; Signature Verification Tests
;; =============================================================================

(deftest compute-signature-test
  (let [payload "test payload"
        secret "test_secret"]
    (testing "produces consistent signature"
      (let [sig1 (payment/compute-signature payload secret)
            sig2 (payment/compute-signature payload secret)]
        (is (= sig1 sig2))))
    
    (testing "different payload produces different signature"
      (let [sig1 (payment/compute-signature "payload1" secret)
            sig2 (payment/compute-signature "payload2" secret)]
        (is (not= sig1 sig2))))
    
    (testing "different secret produces different signature"
      (let [sig1 (payment/compute-signature payload "secret1")
            sig2 (payment/compute-signature payload "secret2")]
        (is (not= sig1 sig2))))))

(deftest verify-signature-test
  (let [payload "{\"test\": true}"
        secret "whsec_test_secret"
        timestamp "1705500000"
        signed-payload (str timestamp "." payload)
        signature (payment/compute-signature signed-payload secret)
        stripe-sig (str "t=" timestamp ",v1=" signature)]
    
    (testing "verifies valid signature"
      (is (payment/verify-signature payload stripe-sig secret)))
    
    (testing "rejects invalid signature"
      (is (not (payment/verify-signature payload "t=123,v1=invalid" secret))))
    
    (testing "rejects wrong secret"
      (is (not (payment/verify-signature payload stripe-sig "wrong_secret"))))
    
    (testing "handles nil inputs"
      (is (not (payment/verify-signature nil stripe-sig secret)))
      (is (not (payment/verify-signature payload nil secret)))
      (is (not (payment/verify-signature payload stripe-sig nil))))))

;; =============================================================================
;; Payment Status Tests
;; =============================================================================

(deftest payment-status->api-test
  (let [intent {:id "pi_123"
                :status :succeeded
                :amount 5999
                :currency "EUR"}
        order {:id (random-uuid)
               :status :paid}
        api (payment/payment-status->api intent order)]
    
    (testing "includes payment intent info"
      (is (= "pi_123" (:payment-intent-id api)))
      (is (= "succeeded" (:status api)))
      (is (= 5999 (:amount api)))
      (is (= "EUR" (:currency api))))
    
    (testing "includes order info"
      (is (= (:id order) (:order-id api)))
      (is (= "paid" (:order-status api))))))
