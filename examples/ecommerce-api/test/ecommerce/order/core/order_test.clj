(ns ecommerce.order.core.order-test
  (:require [clojure.test :refer [deftest is testing]]
            [ecommerce.order.core.order :as order])
  (:import [java.time Instant]))

(def test-instant (Instant/parse "2026-01-17T12:00:00Z"))
(def product-id (random-uuid))

;; =============================================================================
;; Order Number Generation Tests
;; =============================================================================

(deftest generate-order-number-test
  (testing "generates valid order number format"
    (let [order-num (order/generate-order-number test-instant)]
      (is (clojure.string/starts-with? order-num "ORD-"))
      (is (re-matches #"ORD-\d{8}-\d{5}" order-num)))))

;; =============================================================================
;; State Machine Tests
;; =============================================================================

(deftest valid-transition?-test
  (testing "valid transitions from pending"
    (is (order/valid-transition? :pending :paid))
    (is (order/valid-transition? :pending :cancelled)))
  
  (testing "valid transitions from paid"
    (is (order/valid-transition? :paid :shipped))
    (is (order/valid-transition? :paid :refunded))
    (is (order/valid-transition? :paid :cancelled)))
  
  (testing "valid transitions from shipped"
    (is (order/valid-transition? :shipped :delivered))
    (is (order/valid-transition? :shipped :refunded)))
  
  (testing "valid transitions from delivered"
    (is (order/valid-transition? :delivered :refunded)))
  
  (testing "terminal states have no valid transitions"
    (is (not (order/valid-transition? :cancelled :pending)))
    (is (not (order/valid-transition? :refunded :pending))))
  
  (testing "invalid transitions"
    (is (not (order/valid-transition? :pending :shipped)))
    (is (not (order/valid-transition? :pending :delivered)))
    (is (not (order/valid-transition? :delivered :pending)))))

(deftest can-cancel?-test
  (testing "can cancel pending"
    (is (order/can-cancel? {:status :pending})))
  
  (testing "can cancel paid"
    (is (order/can-cancel? {:status :paid})))
  
  (testing "cannot cancel shipped"
    (is (not (order/can-cancel? {:status :shipped}))))
  
  (testing "cannot cancel delivered"
    (is (not (order/can-cancel? {:status :delivered})))))

(deftest can-refund?-test
  (testing "can refund paid"
    (is (order/can-refund? {:status :paid})))
  
  (testing "can refund shipped"
    (is (order/can-refund? {:status :shipped})))
  
  (testing "can refund delivered"
    (is (order/can-refund? {:status :delivered})))
  
  (testing "cannot refund pending"
    (is (not (order/can-refund? {:status :pending})))))

(deftest transition-status-test
  (testing "successful transition"
    (let [order {:status :pending}
          result (order/transition-status order :paid test-instant)]
      (is (= :paid (get-in result [:ok :status])))
      (is (= test-instant (get-in result [:ok :paid-at])))))
  
  (testing "invalid transition returns error"
    (let [order {:status :pending}
          result (order/transition-status order :delivered test-instant)]
      (is (= :invalid-transition (:error result)))
      (is (= :pending (:from result)))
      (is (= :delivered (:to result)))))
  
  (testing "sets correct timestamp for each transition"
    (let [pending-order {:status :pending}
          paid-order {:status :paid}
          shipped-order {:status :shipped}]
      
      (is (some? (get-in (order/transition-status pending-order :paid test-instant) [:ok :paid-at])))
      (is (some? (get-in (order/transition-status paid-order :shipped test-instant) [:ok :shipped-at])))
      (is (some? (get-in (order/transition-status shipped-order :delivered test-instant) [:ok :delivered-at]))))))

;; =============================================================================
;; Order Creation Tests
;; =============================================================================

(deftest create-order-item-test
  (let [order-id (random-uuid)
        cart-item {:product-id product-id :quantity 2}
        product {:name "Test Product" :price-cents 1000}
        item (order/create-order-item order-id cart-item product test-instant)]
    
    (testing "creates item with correct fields"
      (is (uuid? (:id item)))
      (is (= order-id (:order-id item)))
      (is (= product-id (:product-id item)))
      (is (= "Test Product" (:product-name item)))
      (is (= 1000 (:product-price-cents item)))
      (is (= 2 (:quantity item)))
      (is (= 2000 (:total-cents item))))))

(deftest calculate-totals-test
  (let [items [{:total-cents 2000}
               {:total-cents 500}]
        totals (order/calculate-totals items 500 0.21)]
    
    (testing "calculates subtotal"
      (is (= 2500 (:subtotal-cents totals))))
    
    (testing "includes shipping"
      (is (= 500 (:shipping-cents totals))))
    
    (testing "calculates tax"
      ;; 21% of 2500 = 525
      (is (= 525 (:tax-cents totals))))
    
    (testing "calculates total"
      ;; 2500 + 500 + 525 = 3525
      (is (= 3525 (:total-cents totals))))))

(deftest create-order-test
  (let [cart-items [{:product-id product-id :quantity 2}]
        products {product-id {:id product-id :name "Test" :price-cents 1000}}
        customer {:email "test@example.com"
                  :name "Test User"
                  :shipping-address {:line1 "123 Main St"
                                     :city "Amsterdam"
                                     :postal-code "1234AB"
                                     :country "NL"}}
        order (order/create-order cart-items products customer test-instant)]
    
    (testing "creates order with correct fields"
      (is (uuid? (:id order)))
      (is (clojure.string/starts-with? (:order-number order) "ORD-"))
      (is (= :pending (:status order)))
      (is (= "test@example.com" (:customer-email order)))
      (is (= "Test User" (:customer-name order))))
    
    (testing "includes order items"
      (is (= 1 (count (:items order))))
      (is (= "Test" (:product-name (first (:items order))))))
    
    (testing "calculates totals"
      (is (= 2000 (:subtotal-cents order)))
      (is (= 2000 (:total-cents order))))))

;; =============================================================================
;; Order Update Tests
;; =============================================================================

(deftest mark-paid-test
  (let [order {:status :pending :payment-intent-id nil}
        paid (order/mark-paid order "pi_123" test-instant)]
    
    (testing "updates status to paid"
      (is (= :paid (:status paid))))
    
    (testing "sets payment intent"
      (is (= "pi_123" (:payment-intent-id paid))))
    
    (testing "sets paid timestamp"
      (is (= test-instant (:paid-at paid))))))

;; =============================================================================
;; Serialization Tests
;; =============================================================================

(deftest order->api-test
  (let [order {:id (random-uuid)
               :status :pending
               :paid-at test-instant
               :shipped-at nil}
        api (order/order->api order)]
    
    (testing "converts status to string"
      (is (= "pending" (:status api))))
    
    (testing "removes timestamp fields"
      (is (nil? (:paid-at api)))
      (is (nil? (:shipped-at api))))))
