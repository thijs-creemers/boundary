(ns notification.event-test
  "Tests for event routing and processing."
  (:require [clojure.test :refer [deftest testing is]]
            [notification.event.core.event :as event-core]
            [notification.event.schema :as schema])
  (:import [java.time Instant]))

;; =============================================================================
;; Event Creation Tests
;; =============================================================================

(deftest create-event-test
  (testing "creates event with all required fields"
    (let [now (Instant/now)
          event (event-core/create-event 
                 {:type :order/placed
                  :payload {:order-number "ORD-001"
                            :customer-name "John Doe"
                            :customer-email "john@example.com"}}
                 "correlation-123"
                 now)]
      (is (uuid? (:id event)))
      (is (= :order/placed (:type event)))
      (is (= "ORD-001" (get-in event [:payload :order-number])))
      (is (= now (get-in event [:metadata :timestamp])))))
  
  (testing "generates unique IDs"
    (let [event1 (event-core/create-event {:type :order/placed :payload {}} nil (Instant/now))
          event2 (event-core/create-event {:type :order/placed :payload {}} nil (Instant/now))]
      (is (not= (:id event1) (:id event2))))))

;; =============================================================================
;; Event Routing Tests
;; =============================================================================

(deftest route-event-test
  (testing "returns handlers for known event types"
    (is (= [:notification/order-confirmation]
           (event-core/route-event {:type :order/placed})))
    (is (= [:notification/payment-receipt]
           (event-core/route-event {:type :payment/received})))
    (is (= [:notification/shipping-update]
           (event-core/route-event {:type :shipment/sent}))))
  
  (testing "returns empty for unknown event types"
    (is (empty? (event-core/route-event {:type :unknown/event})))))

(deftest valid-event-type?-test
  (testing "returns true for known event types"
    (is (true? (event-core/valid-event-type? :order/placed)))
    (is (true? (event-core/valid-event-type? :payment/received))))
  
  (testing "returns false for unknown event types"
    (is (false? (event-core/valid-event-type? :unknown/event)))))

;; =============================================================================
;; Recipient Extraction Tests
;; =============================================================================

(deftest extract-recipient-test
  (testing "extracts from customer-email"
    (let [event {:payload {:customer-email "customer@example.com"}}]
      (is (= "customer@example.com" (event-core/extract-recipient event)))))
  
  (testing "extracts from email"
    (let [event {:payload {:email "user@example.com"}}]
      (is (= "user@example.com" (event-core/extract-recipient event)))))
  
  (testing "returns nil when no email found"
    (let [event {:payload {:name "John"}}]
      (is (nil? (event-core/extract-recipient event))))))

;; =============================================================================
;; Event Validation Tests
;; =============================================================================

(deftest validate-event-test
  (testing "valid event passes validation"
    (let [event {:type :order/placed
                 :aggregate-id (random-uuid)
                 :aggregate-type :order
                 :payload {:order-number "123"}}]
      (is (:ok (schema/validate schema/PublishEventRequest event)))))
  
  (testing "missing type fails validation"
    (let [event {:aggregate-id (random-uuid)
                 :aggregate-type :order
                 :payload {:order-number "123"}}]
      (is (:error (schema/validate schema/PublishEventRequest event)))))
  
  (testing "invalid type fails validation"
    (let [event {:type :invalid/type
                 :aggregate-id (random-uuid)
                 :aggregate-type :order
                 :payload {}}]
      (is (:error (schema/validate schema/PublishEventRequest event))))))

;; =============================================================================
;; API Transformation Tests
;; =============================================================================

(deftest event->api-test
  (testing "transforms event for API response"
    (let [event {:id (random-uuid)
                 :type :order/placed
                 :aggregate-type :order
                 :payload {}
                 :metadata {:timestamp (Instant/now)}}
          api (event-core/event->api event)]
      (is (string? (:type api)))
      ;; (name :order/placed) returns "placed" not "order/placed"
      (is (= "placed" (:type api)))
      (is (= "order" (:aggregate-type api))))))
