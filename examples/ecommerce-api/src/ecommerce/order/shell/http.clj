(ns ecommerce.order.shell.http
  "HTTP handlers for order API."
  (:require [ecommerce.order.ports :as ports]
            [ecommerce.order.core.order :as order-core]
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

(defn checkout-handler
  "POST /api/checkout - Create order from cart."
  [order-service]
  (fn [request]
    (let [session-id (:session-id request)
          body (:json-body request)
          customer-info {:email (:email body)
                         :name (:name body)
                         :shipping-address (:shipping-address body)}
          result (ports/create-order order-service session-id customer-info)]
      (resp/handle-result result
                          :resource-type "Order"
                          :on-success #(resp/created (order-core/order->api %))))))

(defn get-order-handler
  "GET /api/orders/:id - Get order by ID or order number."
  [order-service]
  (fn [request]
    (let [id-param (get-in request [:path-params :id])
          ;; Try UUID first, then order number
          result (if-let [order-id (str->uuid id-param)]
                   (ports/get-order order-service order-id)
                   (ports/get-order-by-number order-service id-param))]
      (resp/handle-result result
                          :resource-type "Order"
                          :on-success #(resp/ok (order-core/order->api %))))))

(defn list-orders-handler
  "GET /api/orders?email=... - List orders for customer."
  [order-service]
  (fn [request]
    (let [email (get-in request [:query-params "email"])
          limit (some-> (get-in request [:query-params "limit"]) parse-long)
          offset (some-> (get-in request [:query-params "offset"]) parse-long)]
      (if email
        (let [result (ports/list-customer-orders order-service email {:limit limit :offset offset})
              {:keys [orders total]} (:ok result)]
          (resp/ok-list (order-core/orders->api orders)
                        {:total total
                         :limit (or limit 20)
                         :offset (or offset 0)}))
        (resp/bad-request "email query parameter is required")))))

(defn cancel-order-handler
  "POST /api/orders/:id/cancel - Cancel an order."
  [order-service]
  (fn [request]
    (let [order-id (str->uuid (get-in request [:path-params :id]))]
      (if order-id
        (let [result (ports/cancel-order order-service order-id)]
          (resp/handle-result result
                              :resource-type "Order"
                              :on-success #(resp/ok (order-core/order->api %))))
        (resp/bad-request "Invalid order ID")))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  "Order API routes."
  [order-service]
  [["/api/checkout"
    {:post {:handler (checkout-handler order-service)
            :summary "Create order from cart"}}]
   ["/api/orders"
    {:get {:handler (list-orders-handler order-service)
           :summary "List customer orders"}}]
   ["/api/orders/:id"
    {:get {:handler (get-order-handler order-service)
           :summary "Get order by ID or number"}}]
   ["/api/orders/:id/cancel"
    {:post {:handler (cancel-order-handler order-service)
            :summary "Cancel an order"}}]])
