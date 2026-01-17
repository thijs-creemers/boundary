(ns ecommerce.cart.shell.http
  "HTTP handlers for cart API."
  (:require [ecommerce.cart.ports :as ports]
            [ecommerce.shared.http.responses :as resp])
  (:import [java.util UUID]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- str->uuid [s]
  (try (UUID/fromString s)
       (catch Exception _ nil)))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn get-cart-handler
  "GET /api/cart - Get current cart."
  [cart-service]
  (fn [request]
    (let [session-id (:session-id request)
          result (ports/get-cart cart-service session-id)]
      (resp/handle-result result :resource-type "Cart"))))

(defn add-item-handler
  "POST /api/cart/items - Add item to cart."
  [cart-service]
  (fn [request]
    (let [session-id (:session-id request)
          body (:json-body request)
          product-id (some-> (:product-id body) str str->uuid)
          quantity (or (:quantity body) 1)]
      (if product-id
        (let [result (ports/add-item cart-service session-id product-id quantity)]
          (resp/handle-result result :resource-type "Product"))
        (resp/bad-request "product-id is required")))))

(defn update-item-handler
  "PATCH /api/cart/items/:product-id - Update item quantity."
  [cart-service]
  (fn [request]
    (let [session-id (:session-id request)
          product-id (str->uuid (get-in request [:path-params :product-id]))
          body (:json-body request)
          quantity (:quantity body)]
      (if (and product-id quantity)
        (let [result (ports/update-item cart-service session-id product-id quantity)]
          (resp/handle-result result :resource-type "Cart item"))
        (resp/bad-request "product-id and quantity are required")))))

(defn remove-item-handler
  "DELETE /api/cart/items/:product-id - Remove item from cart."
  [cart-service]
  (fn [request]
    (let [session-id (:session-id request)
          product-id (str->uuid (get-in request [:path-params :product-id]))]
      (if product-id
        (let [result (ports/remove-item cart-service session-id product-id)]
          (resp/handle-result result :resource-type "Cart"))
        (resp/bad-request "product-id is required")))))

(defn clear-cart-handler
  "DELETE /api/cart - Clear entire cart."
  [cart-service]
  (fn [request]
    (let [session-id (:session-id request)
          result (ports/clear-cart cart-service session-id)]
      (resp/handle-result result :resource-type "Cart"))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  "Cart API routes."
  [cart-service]
  [["/api/cart"
    {:get {:handler (get-cart-handler cart-service)
           :summary "Get current cart"}
     :delete {:handler (clear-cart-handler cart-service)
              :summary "Clear cart"}}]
   ["/api/cart/items"
    {:post {:handler (add-item-handler cart-service)
            :summary "Add item to cart"}}]
   ["/api/cart/items/:product-id"
    {:patch {:handler (update-item-handler cart-service)
             :summary "Update item quantity"}
     :delete {:handler (remove-item-handler cart-service)
              :summary "Remove item from cart"}}]])
