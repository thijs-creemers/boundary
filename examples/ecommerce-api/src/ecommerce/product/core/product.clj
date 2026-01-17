(ns ecommerce.product.core.product
  "Pure business logic for products.
   
   All functions in this namespace are PURE:
   - No I/O
   - No side effects
   - Deterministic (same input = same output)
   - Easy to test"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Slug Generation
;; =============================================================================

(defn generate-slug
  "Generate URL-friendly slug from product name.
   
   Examples:
     (generate-slug \"Boundary T-Shirt\") => \"boundary-t-shirt\"
     (generate-slug \"Clojure Mug (Large)\") => \"clojure-mug-large\""
  [name]
  (-> name
      str/lower-case
      (str/replace #"[^a-z0-9\s-]" "")
      (str/replace #"\s+" "-")
      (str/replace #"-+" "-")
      (str/replace #"^-|-$" "")))

;; =============================================================================
;; Product Creation
;; =============================================================================

(defn create-product
  "Create a new product entity from input.
   
   Args:
     input - Map with :name, :description, :price-cents, :currency, :stock, :active
     now   - Current timestamp
   
   Returns:
     Complete product map with generated id, slug, and timestamps"
  [input now]
  (let [name (:name input)]
    {:id (random-uuid)
     :name name
     :slug (generate-slug name)
     :description (:description input)
     :price-cents (:price-cents input)
     :currency (or (:currency input) "EUR")
     :stock (or (:stock input) 0)
     :active (if (contains? input :active) (:active input) true)
     :created-at now
     :updated-at now}))

;; =============================================================================
;; Product Updates
;; =============================================================================

(defn update-product
  "Apply updates to a product.
   
   Args:
     product - Existing product
     updates - Map with optional :name, :description, :price-cents, :stock, :active
     now     - Current timestamp
   
   Returns:
     Updated product map"
  [product updates now]
  (cond-> product
    ;; Update name and regenerate slug if name changed
    (:name updates)
    (-> (assoc :name (:name updates))
        (assoc :slug (generate-slug (:name updates))))
    
    ;; Update other fields if present
    (contains? updates :description)
    (assoc :description (:description updates))
    
    (:price-cents updates)
    (assoc :price-cents (:price-cents updates))
    
    (contains? updates :stock)
    (assoc :stock (:stock updates))
    
    (contains? updates :active)
    (assoc :active (:active updates))
    
    ;; Always update timestamp
    true
    (assoc :updated-at now)))

;; =============================================================================
;; Stock Management
;; =============================================================================

(defn adjust-stock
  "Adjust product stock by delta.
   
   Args:
     product - Product to adjust
     delta   - Amount to add (positive) or remove (negative)
     now     - Current timestamp
   
   Returns:
     {:ok updated-product} or {:error :insufficient-stock ...}"
  [product delta now]
  (let [current-stock (:stock product)
        new-stock (+ current-stock delta)]
    (if (neg? new-stock)
      {:error :insufficient-stock
       :product-id (:id product)
       :available current-stock
       :requested (- delta)}
      {:ok (-> product
               (assoc :stock new-stock)
               (assoc :updated-at now))})))

(defn check-stock
  "Check if sufficient stock is available.
   
   Returns:
     {:ok true} or {:error :insufficient-stock ...}"
  [product quantity]
  (if (>= (:stock product) quantity)
    {:ok true}
    {:error :insufficient-stock
     :product-id (:id product)
     :available (:stock product)
     :requested quantity}))

;; =============================================================================
;; Price Calculations
;; =============================================================================

(defn format-price
  "Format price in cents to display string.
   
   Examples:
     (format-price 2999 \"EUR\") => \"€29.99\"
     (format-price 1499 \"USD\") => \"$14.99\""
  [cents currency]
  (let [symbol (case currency
                 "EUR" "€"
                 "USD" "$"
                 "GBP" "£"
                 currency)
        euros (/ cents 100.0)]
    (str symbol (String/format java.util.Locale/US "%.2f" (into-array Object [(double euros)])))))

(defn calculate-line-total
  "Calculate total for a line item.
   
   Returns:
     Total in cents"
  [price-cents quantity]
  (* price-cents quantity))

;; =============================================================================
;; Serialization
;; =============================================================================

(defn product->api
  "Transform product for API response.
   Adds formatted price, removes internal fields."
  [product]
  (-> product
      (assoc :price-formatted (format-price (:price-cents product) 
                                            (:currency product)))
      (dissoc :created-at :updated-at)))

(defn products->api
  "Transform multiple products for API response."
  [products]
  (mapv product->api products))
