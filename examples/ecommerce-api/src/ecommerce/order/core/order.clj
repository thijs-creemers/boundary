(ns ecommerce.order.core.order
  "Pure business logic for orders.
   
   Includes ORDER STATE MACHINE - defines valid status transitions.")

;; =============================================================================
;; Order Number Generation
;; =============================================================================

(defn generate-order-number
  "Generate unique order number.
   Format: ORD-YYYYMMDD-XXXXX (random suffix)"
  [now]
  (let [date-str (-> now
                     .toString
                     (subs 0 10)
                     (clojure.string/replace "-" ""))
        suffix (format "%05d" (rand-int 100000))]
    (str "ORD-" date-str "-" suffix)))

;; =============================================================================
;; State Machine
;; =============================================================================

(def status-transitions
  "Valid state transitions for orders.
   Maps from-status -> set of valid to-statuses."
  {:pending   #{:paid :cancelled}
   :paid      #{:shipped :refunded :cancelled}
   :shipped   #{:delivered :refunded}
   :delivered #{:refunded}
   :cancelled #{}
   :refunded  #{}})

(defn valid-transition?
  "Check if status transition is valid."
  [from-status to-status]
  (contains? (get status-transitions from-status #{}) to-status))

(defn can-cancel?
  "Check if order can be cancelled."
  [order]
  (contains? #{:pending :paid} (:status order)))

(defn can-refund?
  "Check if order can be refunded."
  [order]
  (contains? #{:paid :shipped :delivered} (:status order)))

(defn transition-status
  "Attempt to transition order to new status.
   
   Returns:
     {:ok updated-order} or {:error :invalid-transition ...}"
  [order new-status now]
  (let [current-status (:status order)]
    (if (valid-transition? current-status new-status)
      {:ok (-> order
               (assoc :status new-status)
               (assoc :updated-at now)
               ;; Set timestamp for specific transitions
               (cond->
                 (= new-status :paid) (assoc :paid-at now)
                 (= new-status :shipped) (assoc :shipped-at now)
                 (= new-status :delivered) (assoc :delivered-at now)
                 (= new-status :cancelled) (assoc :cancelled-at now)))}
      {:error :invalid-transition
       :from current-status
       :to new-status})))

;; =============================================================================
;; Order Creation
;; =============================================================================

(defn create-order-item
  "Create order item from cart item with product info."
  [order-id cart-item product now]
  {:id (random-uuid)
   :order-id order-id
   :product-id (:product-id cart-item)
   :product-name (:name product)
   :product-price-cents (:price-cents product)
   :quantity (:quantity cart-item)
   :total-cents (* (:quantity cart-item) (:price-cents product))
   :created-at now})

(defn calculate-totals
  "Calculate order totals from items."
  [items shipping-cents tax-rate]
  (let [subtotal (reduce + 0 (map :total-cents items))
        tax-cents (long (* subtotal tax-rate))
        total (+ subtotal shipping-cents tax-cents)]
    {:subtotal-cents subtotal
     :shipping-cents shipping-cents
     :tax-cents tax-cents
     :total-cents total}))

(defn create-order
  "Create a new order from cart and customer info.
   
   Args:
     cart-items    - Vector of cart items
     products      - Map of product-id -> product
     customer-info - Map with :email, :name, :shipping-address
     now           - Current timestamp
   
   Returns:
     New order map"
  [cart-items products customer-info now]
  (let [order-id (random-uuid)
        order-items (mapv (fn [item]
                           (let [product (get products (:product-id item))]
                             (create-order-item order-id item product now)))
                         cart-items)
        totals (calculate-totals order-items 0 0.0)] ;; Free shipping, no tax for demo
    (merge
     {:id order-id
      :order-number (generate-order-number now)
      :status :pending
      :customer-email (:email customer-info)
      :customer-name (:name customer-info)
      :shipping-address (:shipping-address customer-info)
      :items order-items
      :payment-intent-id nil
      :payment-status nil
      :currency "EUR"
      :created-at now
      :updated-at now
      :paid-at nil
      :shipped-at nil
      :delivered-at nil
      :cancelled-at nil}
     totals)))

;; =============================================================================
;; Order Updates
;; =============================================================================

(defn set-payment-intent
  "Associate payment intent with order."
  [order payment-intent-id now]
  (-> order
      (assoc :payment-intent-id payment-intent-id)
      (assoc :updated-at now)))

(defn mark-paid
  "Mark order as paid with payment details."
  [order payment-intent-id now]
  (-> order
      (assoc :status :paid)
      (assoc :payment-intent-id payment-intent-id)
      (assoc :payment-status "succeeded")
      (assoc :paid-at now)
      (assoc :updated-at now)))

;; =============================================================================
;; Serialization
;; =============================================================================

(defn order->api
  "Transform order for API response."
  [order]
  (-> order
      (update :status name)
      (dissoc :paid-at :shipped-at :delivered-at :cancelled-at)))

(defn orders->api
  "Transform multiple orders for API response."
  [orders]
  (mapv order->api orders))
