(ns ecommerce.cart.shell.persistence
  "SQLite persistence adapter for carts."
  (:require [ecommerce.cart.ports :as ports]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
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

(defn- db->cart-item [row]
  (when row
    {:id (str->uuid (:id row))
     :cart-id (str->uuid (:cart_id row))
     :product-id (str->uuid (:product_id row))
     :quantity (:quantity row)
     :created-at (str->instant (:created_at row))
     :updated-at (str->instant (:updated_at row))}))

(defn- db->cart [row items]
  (when row
    {:id (str->uuid (:id row))
     :session-id (:session_id row)
     :items (or items [])
     :created-at (str->instant (:created_at row))
     :updated-at (str->instant (:updated_at row))}))

(defn- fetch-cart-items [datasource cart-id]
  (let [results (jdbc/execute! datasource
                               ["SELECT * FROM cart_items WHERE cart_id = ?" (str cart-id)]
                               {:builder-fn rs/as-unqualified-maps})]
    (mapv db->cart-item results)))

;; =============================================================================
;; Repository Implementation
;; =============================================================================

(defrecord SQLiteCartRepository [datasource]
  ports/ICartRepository
  
  (find-by-id [_ cart-id]
    (let [result (jdbc/execute-one! datasource
                                    ["SELECT * FROM carts WHERE id = ?" (str cart-id)]
                                    {:builder-fn rs/as-unqualified-maps})]
      (when result
        (db->cart result (fetch-cart-items datasource cart-id)))))
  
  (find-by-session [_ session-id]
    (let [result (jdbc/execute-one! datasource
                                    ["SELECT * FROM carts WHERE session_id = ?" session-id]
                                    {:builder-fn rs/as-unqualified-maps})]
      (when result
        (let [cart-id (str->uuid (:id result))]
          (db->cart result (fetch-cart-items datasource cart-id))))))
  
  (save-cart! [this cart]
    (let [existing (ports/find-by-id this (:id cart))]
      (if existing
        ;; Update
        (do
          (jdbc/execute! datasource
                         ["UPDATE carts SET updated_at = ? WHERE id = ?"
                          (instant->str (:updated-at cart)) (str (:id cart))])
          cart)
        ;; Insert
        (do
          (jdbc/execute! datasource
                         ["INSERT INTO carts (id, session_id, created_at, updated_at) VALUES (?, ?, ?, ?)"
                          (str (:id cart)) (:session-id cart)
                          (instant->str (:created-at cart)) (instant->str (:updated-at cart))])
          cart))))
  
  (save-item! [_ cart-id item]
    (let [existing (jdbc/execute-one! datasource
                                      ["SELECT * FROM cart_items WHERE cart_id = ? AND product_id = ?"
                                       (str cart-id) (str (:product-id item))]
                                      {:builder-fn rs/as-unqualified-maps})]
      (if existing
        ;; Update quantity
        (do
          (jdbc/execute! datasource
                         ["UPDATE cart_items SET quantity = ?, updated_at = ? WHERE cart_id = ? AND product_id = ?"
                          (:quantity item) (instant->str (:updated-at item))
                          (str cart-id) (str (:product-id item))])
          item)
        ;; Insert
        (do
          (jdbc/execute! datasource
                         ["INSERT INTO cart_items (id, cart_id, product_id, quantity, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
                          (str (:id item)) (str cart-id) (str (:product-id item))
                          (:quantity item) (instant->str (:created-at item)) (instant->str (:updated-at item))])
          item))))
  
  (delete-item! [_ cart-id product-id]
    (let [result (jdbc/execute-one! datasource
                                    ["DELETE FROM cart_items WHERE cart_id = ? AND product_id = ?"
                                     (str cart-id) (str product-id)])]
      (pos? (or (:next.jdbc/update-count result) 0))))
  
  (clear-cart! [_ cart-id]
    (jdbc/execute! datasource
                   ["DELETE FROM cart_items WHERE cart_id = ?" (str cart-id)])
    true))
