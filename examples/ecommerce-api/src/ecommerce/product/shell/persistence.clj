(ns ecommerce.product.shell.persistence
  "SQLite persistence adapter for products."
  (:require [ecommerce.product.ports :as ports]
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

(defn- db->product
  "Transform database row to product entity."
  [row]
  (when row
    {:id (str->uuid (:products/id row))
     :name (:products/name row)
     :slug (:products/slug row)
     :description (:products/description row)
     :price-cents (:products/price_cents row)
     :currency (:products/currency row)
     :stock (:products/stock row)
     :active (= 1 (:products/active row))
     :created-at (str->instant (:products/created_at row))
     :updated-at (str->instant (:products/updated_at row))}))

(defn- product->db
  "Transform product entity to database row."
  [product]
  {:id (str (:id product))
   :name (:name product)
   :slug (:slug product)
   :description (:description product)
   :price_cents (:price-cents product)
   :currency (:currency product)
   :stock (:stock product)
   :active (if (:active product) 1 0)
   :created_at (instant->str (:created-at product))
   :updated_at (instant->str (:updated-at product))})

;; =============================================================================
;; Repository Implementation
;; =============================================================================

(defrecord SQLiteProductRepository [datasource]
  ports/IProductRepository
  
  (find-by-id [_ product-id]
    (let [result (jdbc/execute-one! 
                  datasource
                  ["SELECT * FROM products WHERE id = ?" (str product-id)]
                  {:builder-fn rs/as-unqualified-maps})]
      (when result
        (db->product (update-keys result #(keyword "products" (name %)))))))
  
  (find-by-slug [_ slug]
    (let [result (jdbc/execute-one!
                  datasource
                  ["SELECT * FROM products WHERE slug = ?" slug]
                  {:builder-fn rs/as-unqualified-maps})]
      (when result
        (db->product (update-keys result #(keyword "products" (name %)))))))
  
  (find-by-ids [_ product-ids]
    (if (empty? product-ids)
      []
      (let [placeholders (clojure.string/join "," (repeat (count product-ids) "?"))
            sql (str "SELECT * FROM products WHERE id IN (" placeholders ")")
            params (mapv str product-ids)
            results (jdbc/execute! datasource (into [sql] params)
                                   {:builder-fn rs/as-unqualified-maps})]
        (mapv #(db->product (update-keys % (fn [k] (keyword "products" (name k))))) 
              results))))
  
  (list-products [_ options]
    (let [{:keys [limit offset active]
           :or {limit 20 offset 0}} options
          base-where (if (some? active)
                       ["WHERE active = ?" (if active 1 0)]
                       ["WHERE 1=1"])
          count-sql (str "SELECT COUNT(*) as cnt FROM products " (first base-where))
          count-result (jdbc/execute-one! datasource 
                                          (if (second base-where)
                                            [count-sql (second base-where)]
                                            [count-sql])
                                          {:builder-fn rs/as-unqualified-maps})
          total (:cnt count-result)
          sql (str "SELECT * FROM products " (first base-where) 
                   " ORDER BY created_at DESC LIMIT ? OFFSET ?")
          params (if (second base-where)
                   [sql (second base-where) limit offset]
                   [sql limit offset])
          results (jdbc/execute! datasource params
                                 {:builder-fn rs/as-unqualified-maps})]
      {:products (mapv #(db->product (update-keys % (fn [k] (keyword "products" (name k))))) 
                       results)
       :total total}))
  
  (save! [this product]
    (let [existing (ports/find-by-id this (:id product))
          db-product (product->db product)]
      (if existing
        ;; Update
        (do
          (jdbc/execute! datasource
                         ["UPDATE products SET name=?, slug=?, description=?, price_cents=?, 
                           currency=?, stock=?, active=?, updated_at=? WHERE id=?"
                          (:name db-product) (:slug db-product) (:description db-product)
                          (:price_cents db-product) (:currency db-product) (:stock db-product)
                          (:active db-product) (:updated_at db-product) (:id db-product)])
          product)
        ;; Insert
        (do
          (jdbc/execute! datasource
                         ["INSERT INTO products (id, name, slug, description, price_cents, 
                           currency, stock, active, created_at, updated_at) 
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                          (:id db-product) (:name db-product) (:slug db-product) 
                          (:description db-product) (:price_cents db-product) 
                          (:currency db-product) (:stock db-product) (:active db-product)
                          (:created_at db-product) (:updated_at db-product)])
          product))))
  
  (delete! [_ product-id]
    (let [result (jdbc/execute-one! datasource
                                    ["DELETE FROM products WHERE id = ?" (str product-id)])]
      (pos? (:next.jdbc/update-count result)))))
