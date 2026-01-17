(ns notification.event.core.event
  "Pure business logic for domain events.
   
   Includes event creation, routing, and validation.")

;; =============================================================================
;; Event Creation
;; =============================================================================

(defn create-event
  "Create a new domain event.
   
   Args:
     event-data - Map with :type, :aggregate-id, :aggregate-type, :payload
     correlation-id - Optional correlation ID (generated if not provided)
     now - Current timestamp
   
   Returns:
     Complete event map"
  [event-data correlation-id now]
  {:id (random-uuid)
   :type (:type event-data)
   :aggregate-id (:aggregate-id event-data)
   :aggregate-type (:aggregate-type event-data)
   :payload (:payload event-data)
   :metadata {:correlation-id (or correlation-id (random-uuid))
              :causation-id (:causation-id event-data)
              :timestamp now
              :source (:source event-data "notification-service")}
   :created-at now})

;; =============================================================================
;; Event Routing
;; =============================================================================

(def event-handlers
  "Map of event types to handler topics.
   Each event type can trigger multiple handlers."
  {:order/placed     [:notification/order-confirmation]
   :order/confirmed  [:notification/order-confirmed]
   :order/cancelled  [:notification/order-cancelled]
   :payment/received [:notification/payment-receipt]
   :payment/failed   [:notification/payment-failed]
   :payment/refunded [:notification/refund-confirmation]
   :shipment/sent    [:notification/shipping-update]
   :shipment/delivered [:notification/delivery-confirmation]
   :shipment/returned [:notification/return-received]})

(defn route-event
  "Determine which handlers should process an event.
   
   Args:
     event - The domain event
   
   Returns:
     Vector of handler topics to notify"
  [event]
  (get event-handlers (:type event) []))

(defn should-handle?
  "Check if a handler should process this event type."
  [handler-topic event-type]
  (contains? (set (get event-handlers event-type [])) handler-topic))

;; =============================================================================
;; Event Validation
;; =============================================================================

(defn valid-event-type?
  "Check if event type is known."
  [event-type]
  (contains? event-handlers event-type))

(defn extract-recipient
  "Extract notification recipient from event payload.
   
   Returns email address for notifications."
  [event]
  (or (get-in event [:payload :customer-email])
      (get-in event [:payload :email])))

(defn extract-order-info
  "Extract order information from event for notifications."
  [event]
  {:order-id (:aggregate-id event)
   :order-number (get-in event [:payload :order-number])
   :customer-name (get-in event [:payload :customer-name])
   :total-cents (get-in event [:payload :total-cents])
   :currency (get-in event [:payload :currency] "EUR")})

;; =============================================================================
;; Event Serialization
;; =============================================================================

(defn event->api
  "Transform event for API response."
  [event]
  (-> event
      (update :type name)
      (update :aggregate-type name)))

(defn events->api
  "Transform multiple events for API response."
  [events]
  (mapv event->api events))

;; =============================================================================
;; Event Statistics
;; =============================================================================

(defn categorize-event
  "Categorize event by its domain."
  [event]
  (let [type-name (name (:type event))]
    (cond
      (clojure.string/starts-with? type-name "order") :order
      (clojure.string/starts-with? type-name "payment") :payment
      (clojure.string/starts-with? type-name "shipment") :shipment
      :else :other)))

(defn aggregate-by-type
  "Aggregate events by type for statistics."
  [events]
  (->> events
       (group-by :type)
       (map (fn [[k v]] [k (count v)]))
       (into {})))
