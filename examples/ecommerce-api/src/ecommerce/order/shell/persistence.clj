(ns ecommerce.order.shell.persistence
  "SQLite persistence adapter for orders."
  (:require [ecommerce.order.ports :as ports]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- str->uuid [s]
  (when s (UUID/fromString s)))

(defn- str->instant [s]
  (when s (Instant/parse s)))

(defn- instant->str [inst]
  (when inst (str inst)))

(defn- str->keyword [s]
  (when s (keyword s)))

(defn- db->order-item [row]
  (when row
    {:id (str->uuid (:id row))
     :order-id (str->uuid (:order_id row))
     :product-id (str->uuid (:product_id row))
     :product-name (:product_name row)
     :product-price-cents (:product_price_cents row)
     :quantity (:quantity row)
     :total-cents (:total_cents row)
     :created-at (str->instant (:created_at row))}))

(defn- db->order [row items]
  (when row
    {:id (str->uuid (:id row))
     :order-number (:order_number row)
     :status (str->keyword (:status row))
     :customer-email (:customer_email row)
     :customer-name (:customer_name row)
     :shipping-address (json/parse-string (:shipping_address row) true)
     :items (or items [])
     :payment-intent-id (:payment_intent_id row)
     :payment-status (:payment_status row)
     :subtotal-cents (:subtotal_cents row)
     :shipping-cents (:shipping_cents row)
     :tax-cents (:tax_cents row)
     :total-cents (:total_cents row)
     :currency (:currency row)
     :created-at (str->instant (:created_at row))
     :updated-at (str->instant (:updated_at row))
     :paid-at (str->instant (:paid_at row))
     :shipped-at (str->instant (:shipped_at row))
     :delivered-at (str->instant (:delivered_at row))
     :cancelled-at (str->instant (:cancelled_at row))}))

(defn- fetch-order-items [datasource order-id]
  (let [results (jdbc/execute! datasource
                               ["SELECT * FROM order_items WHERE order_id = ? ORDER BY created_at"
                                (str order-id)]
                               {:builder-fn rs/as-unqualified-maps})]
    (mapv db->order-item results)))

;; =============================================================================
;; Repository Implementation
;; =============================================================================

(defrecord SQLiteOrderRepository [datasource]
  ports/IOrderRepository
  
  (find-by-id [_ order-id]
    (let [result (jdbc/execute-one! datasource
                                    ["SELECT * FROM orders WHERE id = ?" (str order-id)]
                                    {:builder-fn rs/as-unqualified-maps})]
      (when result
        (db->order result (fetch-order-items datasource order-id)))))
  
  (find-by-number [_ order-number]
    (let [result (jdbc/execute-one! datasource
                                    ["SELECT * FROM orders WHERE order_number = ?" order-number]
                                    {:builder-fn rs/as-unqualified-maps})]
      (when result
        (let [order-id (str->uuid (:id result))]
          (db->order result (fetch-order-items datasource order-id))))))
  
  (find-by-payment-intent [_ payment-intent-id]
    (let [result (jdbc/execute-one! datasource
                                    ["SELECT * FROM orders WHERE payment_intent_id = ?" payment-intent-id]
                                    {:builder-fn rs/as-unqualified-maps})]
      (when result
        (let [order-id (str->uuid (:id result))]
          (db->order result (fetch-order-items datasource order-id))))))
  
  (list-by-customer [_ email options]
    (let [{:keys [limit offset] :or {limit 20 offset 0}} options
          count-result (jdbc/execute-one! datasource
                                          ["SELECT COUNT(*) as cnt FROM orders WHERE customer_email = ?" email]
                                          {:builder-fn rs/as-unqualified-maps})
          total (:cnt count-result)
          results (jdbc/execute! datasource
                                 ["SELECT * FROM orders WHERE customer_email = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
                                  email limit offset]
                                 {:builder-fn rs/as-unqualified-maps})]
      {:orders (mapv (fn [row]
                       (let [order-id (str->uuid (:id row))]
                         (db->order row (fetch-order-items datasource order-id))))
                     results)
       :total total}))
  
  (save! [this order]
    (let [existing (ports/find-by-id this (:id order))]
      (if existing
        ;; Update
        (do
          (jdbc/execute! datasource
                         ["UPDATE orders SET status=?, payment_intent_id=?, payment_status=?, 
                           updated_at=?, paid_at=?, shipped_at=?, delivered_at=?, cancelled_at=? 
                           WHERE id=?"
                          (name (:status order)) (:payment-intent-id order) (:payment-status order)
                          (instant->str (:updated-at order)) (instant->str (:paid-at order))
                          (instant->str (:shipped-at order)) (instant->str (:delivered-at order))
                          (instant->str (:cancelled-at order)) (str (:id order))])
          order)
        ;; Insert
        (do
          (jdbc/execute! datasource
                         ["INSERT INTO orders (id, order_number, status, customer_email, customer_name,
                           shipping_address, payment_intent_id, payment_status, subtotal_cents, 
                           shipping_cents, tax_cents, total_cents, currency, created_at, updated_at,
                           paid_at, shipped_at, delivered_at, cancelled_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                          (str (:id order)) (:order-number order) (name (:status order))
                          (:customer-email order) (:customer-name order)
                          (json/generate-string (:shipping-address order))
                          (:payment-intent-id order) (:payment-status order)
                          (:subtotal-cents order) (:shipping-cents order) (:tax-cents order)
                          (:total-cents order) (:currency order)
                          (instant->str (:created-at order)) (instant->str (:updated-at order))
                          (instant->str (:paid-at order)) (instant->str (:shipped-at order))
                          (instant->str (:delivered-at order)) (instant->str (:cancelled-at order))])
          order))))
  
  (save-items! [_ order-id items]
    (doseq [item items]
      (jdbc/execute! datasource
                     ["INSERT INTO order_items (id, order_id, product_id, product_name, 
                       product_price_cents, quantity, total_cents, created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                      (str (:id item)) (str order-id) (str (:product-id item))
                      (:product-name item) (:product-price-cents item) (:quantity item)
                      (:total-cents item) (instant->str (:created-at item))]))
    items))
