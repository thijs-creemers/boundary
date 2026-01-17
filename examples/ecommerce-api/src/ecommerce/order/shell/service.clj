(ns ecommerce.order.shell.service
  "Order service - orchestrates order operations."
  (:require [ecommerce.order.ports :as ports]
            [ecommerce.order.schema :as schema]
            [ecommerce.order.core.order :as order-core]
            [ecommerce.cart.ports :as cart-ports]
            [ecommerce.product.ports :as product-ports])
  (:import [java.time Instant]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- now []
  (Instant/now))

;; =============================================================================
;; Service Implementation
;; =============================================================================

(defrecord OrderService [order-repository cart-repository product-repository]
  ports/IOrderService
  
  (get-order [_ order-id]
    (if-let [order (ports/find-by-id order-repository order-id)]
      {:ok order}
      {:error :not-found :id order-id}))
  
  (get-order-by-number [_ order-number]
    (if-let [order (ports/find-by-number order-repository order-number)]
      {:ok order}
      {:error :not-found :id order-number}))
  
  (list-customer-orders [_ email options]
    (let [result (ports/list-by-customer order-repository email options)]
      {:ok result}))
  
  (create-order [_ session-id customer-info]
    ;; Validate input
    (let [validation (schema/validate schema/CheckoutRequest customer-info)]
      (if (:error validation)
        validation
        ;; Get cart
        (if-let [cart (cart-ports/find-by-session cart-repository session-id)]
          (if (empty? (:items cart))
            {:error :validation :details "Cart is empty"}
            ;; Get products for cart items
            (let [product-ids (mapv :product-id (:items cart))
                  products-list (product-ports/find-by-ids product-repository product-ids)
                  products (into {} (map (juxt :id identity) products-list))
                  ;; Check stock for all items
                  stock-errors (keep (fn [item]
                                       (let [product (get products (:product-id item))]
                                         (when (< (:stock product) (:quantity item))
                                           {:product-id (:product-id item)
                                            :available (:stock product)
                                            :requested (:quantity item)})))
                                     (:items cart))]
              (if (seq stock-errors)
                {:error :insufficient-stock :items stock-errors}
                ;; Create order
                (let [order (order-core/create-order (:items cart) products customer-info (now))]
                  ;; Save order
                  (ports/save! order-repository order)
                  (ports/save-items! order-repository (:id order) (:items order))
                  ;; Deduct stock
                  (doseq [item (:items cart)]
                    (let [product (get products (:product-id item))
                          new-stock (- (:stock product) (:quantity item))]
                      (product-ports/save! product-repository 
                                           (assoc product :stock new-stock :updated-at (now)))))
                  ;; Clear cart
                  (cart-ports/clear-cart! cart-repository (:id cart))
                  {:ok order}))))
          {:error :not-found :id "cart"}))))
  
  (update-status [_ order-id new-status]
    (if-let [order (ports/find-by-id order-repository order-id)]
      (let [result (order-core/transition-status order new-status (now))]
        (if (:ok result)
          (do
            (ports/save! order-repository (:ok result))
            {:ok (:ok result)})
          result))
      {:error :not-found :id order-id}))
  
  (mark-paid [_ order-id payment-intent-id]
    (if-let [order (ports/find-by-id order-repository order-id)]
      (if (= :pending (:status order))
        (let [updated (order-core/mark-paid order payment-intent-id (now))]
          (ports/save! order-repository updated)
          {:ok updated})
        {:error :invalid-transition
         :from (:status order)
         :to :paid})
      {:error :not-found :id order-id}))
  
  (cancel-order [_ order-id]
    (if-let [order (ports/find-by-id order-repository order-id)]
      (if (order-core/can-cancel? order)
        (let [result (order-core/transition-status order :cancelled (now))]
          (when (:ok result)
            (ports/save! order-repository (:ok result))
            ;; Restore stock
            (doseq [item (:items order)]
              (when-let [product (product-ports/find-by-id product-repository (:product-id item))]
                (let [new-stock (+ (:stock product) (:quantity item))]
                  (product-ports/save! product-repository 
                                       (assoc product :stock new-stock :updated-at (now)))))))
          result)
        {:error :invalid-transition
         :from (:status order)
         :to :cancelled})
      {:error :not-found :id order-id})))
