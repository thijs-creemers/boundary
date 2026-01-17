(ns ecommerce.cart.core.cart
  "Pure business logic for shopping cart.
   
   All functions are PURE - no I/O, no side effects.")

;; =============================================================================
;; Cart Creation
;; =============================================================================

(defn create-cart
  "Create a new empty cart.
   
   Args:
     session-id - Session identifier
     now        - Current timestamp
   
   Returns:
     New cart map"
  [session-id now]
  {:id (random-uuid)
   :session-id session-id
   :items []
   :created-at now
   :updated-at now})

;; =============================================================================
;; Cart Item Operations
;; =============================================================================

(defn create-item
  "Create a new cart item.
   
   Args:
     cart-id    - Cart UUID
     product-id - Product UUID
     quantity   - Quantity to add
     now        - Current timestamp
   
   Returns:
     New cart item map"
  [cart-id product-id quantity now]
  {:id (random-uuid)
   :cart-id cart-id
   :product-id product-id
   :quantity quantity
   :created-at now
   :updated-at now})

(defn find-item
  "Find item in cart by product-id."
  [cart product-id]
  (some #(when (= product-id (:product-id %)) %) (:items cart)))

(defn add-item
  "Add or update item in cart.
   If product already in cart, increases quantity.
   
   Returns:
     Updated cart"
  [cart product-id quantity now]
  (let [existing (find-item cart product-id)]
    (if existing
      ;; Update existing item
      (let [updated-items (mapv (fn [item]
                                  (if (= product-id (:product-id item))
                                    (-> item
                                        (update :quantity + quantity)
                                        (assoc :updated-at now))
                                    item))
                                (:items cart))]
        (-> cart
            (assoc :items updated-items)
            (assoc :updated-at now)))
      ;; Add new item
      (let [new-item (create-item (:id cart) product-id quantity now)]
        (-> cart
            (update :items conj new-item)
            (assoc :updated-at now))))))

(defn update-item-quantity
  "Set item quantity.
   
   Returns:
     Updated cart or nil if item not found"
  [cart product-id quantity now]
  (if (find-item cart product-id)
    (let [updated-items (mapv (fn [item]
                                (if (= product-id (:product-id item))
                                  (-> item
                                      (assoc :quantity quantity)
                                      (assoc :updated-at now))
                                  item))
                              (:items cart))]
      (-> cart
          (assoc :items updated-items)
          (assoc :updated-at now)))
    nil))

(defn remove-item
  "Remove item from cart.
   
   Returns:
     Updated cart"
  [cart product-id now]
  (-> cart
      (update :items (fn [items] 
                       (filterv #(not= product-id (:product-id %)) items)))
      (assoc :updated-at now)))

(defn clear-items
  "Remove all items from cart.
   
   Returns:
     Empty cart"
  [cart now]
  (-> cart
      (assoc :items [])
      (assoc :updated-at now)))

;; =============================================================================
;; Cart Calculations
;; =============================================================================

(defn calculate-item-total
  "Calculate total for a cart item with product info.
   
   Args:
     item    - Cart item
     product - Product with :price-cents
   
   Returns:
     Total in cents"
  [item product]
  (* (:quantity item) (:price-cents product)))

(defn calculate-cart-totals
  "Calculate cart totals with product info.
   
   Args:
     cart     - Cart with items
     products - Map of product-id -> product
   
   Returns:
     Map with :subtotal-cents, :item-count, :items (enriched)"
  [cart products]
  (let [enriched-items (mapv (fn [item]
                               (let [product (get products (:product-id item))
                                     line-total (calculate-item-total item product)]
                                 (assoc item
                                        :product product
                                        :line-total-cents line-total)))
                             (:items cart))
        subtotal (reduce + 0 (map :line-total-cents enriched-items))
        item-count (reduce + 0 (map :quantity (:items cart)))]
    {:cart-id (:id cart)
     :session-id (:session-id cart)
     :items enriched-items
     :item-count item-count
     :subtotal-cents subtotal
     :currency "EUR"}))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-quantity
  "Validate quantity is positive."
  [quantity]
  (if (and (integer? quantity) (pos? quantity))
    {:ok quantity}
    {:error :validation
     :details "Quantity must be a positive integer"}))

(defn cart-empty?
  "Check if cart has no items."
  [cart]
  (empty? (:items cart)))
