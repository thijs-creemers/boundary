(ns ecommerce.order.schema
  "Malli schemas for order module."
  (:require [malli.core :as m]))

;; =============================================================================
;; Order Status (State Machine States)
;; =============================================================================

(def OrderStatus
  "Valid order statuses."
  [:enum :pending :paid :shipped :delivered :cancelled :refunded])

;; =============================================================================
;; Order Entities
;; =============================================================================

(def ShippingAddress
  "Customer shipping address."
  [:map
   [:line1 [:string {:min 1 :max 200}]]
   [:line2 {:optional true} [:string {:max 200}]]
   [:city [:string {:min 1 :max 100}]]
   [:postal-code [:string {:min 1 :max 20}]]
   [:country [:string {:min 2 :max 2}]]])  ;; ISO country code

(def OrderItem
  "A line item in an order (denormalized from cart)."
  [:map
   [:id uuid?]
   [:order-id uuid?]
   [:product-id uuid?]
   [:product-name :string]
   [:product-price-cents pos-int?]
   [:quantity pos-int?]
   [:total-cents pos-int?]
   [:created-at inst?]])

(def Order
  "Complete order entity."
  [:map
   [:id uuid?]
   [:order-number :string]
   [:status OrderStatus]
   ;; Customer info
   [:customer-email [:string {:min 1}]]
   [:customer-name [:string {:min 1}]]
   [:shipping-address ShippingAddress]
   ;; Items
   [:items [:vector OrderItem]]
   ;; Payment
   [:payment-intent-id {:optional true} [:maybe :string]]
   [:payment-status {:optional true} [:maybe :string]]
   ;; Totals
   [:subtotal-cents nat-int?]
   [:shipping-cents nat-int?]
   [:tax-cents nat-int?]
   [:total-cents pos-int?]
   [:currency [:enum "EUR" "USD" "GBP"]]
   ;; Timestamps
   [:created-at inst?]
   [:updated-at inst?]
   [:paid-at {:optional true} [:maybe inst?]]
   [:shipped-at {:optional true} [:maybe inst?]]
   [:delivered-at {:optional true} [:maybe inst?]]
   [:cancelled-at {:optional true} [:maybe inst?]]])

;; =============================================================================
;; API Schemas
;; =============================================================================

(def CheckoutRequest
  "Request to create an order from cart."
  [:map
   [:email [:string {:min 1}]]
   [:name [:string {:min 1}]]
   [:shipping-address ShippingAddress]])

(def UpdateOrderStatusRequest
  "Request to update order status (admin)."
  [:map
   [:status OrderStatus]])

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate
  "Validate data against schema."
  [schema data]
  (if (m/validate schema data)
    {:ok data}
    {:error :validation
     :details (m/explain schema data)}))
