(ns ecommerce.cart.schema
  "Malli schemas for cart module."
  (:require [malli.core :as m]))

;; =============================================================================
;; Cart Entities
;; =============================================================================

(def CartItem
  "A single item in a cart."
  [:map
   [:id uuid?]
   [:cart-id uuid?]
   [:product-id uuid?]
   [:quantity pos-int?]
   [:created-at inst?]
   [:updated-at inst?]])

(def Cart
  "Shopping cart with items."
  [:map
   [:id uuid?]
   [:session-id :string]
   [:items [:vector CartItem]]
   [:created-at inst?]
   [:updated-at inst?]])

;; =============================================================================
;; API Schemas
;; =============================================================================

(def AddToCartRequest
  "Request to add an item to cart."
  [:map
   [:product-id uuid?]
   [:quantity {:optional true} pos-int?]])

(def UpdateCartItemRequest
  "Request to update cart item quantity."
  [:map
   [:quantity pos-int?]])

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
