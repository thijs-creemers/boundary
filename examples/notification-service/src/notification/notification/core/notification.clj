(ns notification.notification.core.notification
  "Pure business logic for notifications.
   
   Includes template rendering, retry logic, and status transitions."
  (:require [notification.shared.retry :as retry]
            [clojure.string :as str]))

;; =============================================================================
;; Price Formatting
;; =============================================================================

(defn format-price
  "Format price from cents to display string."
  [cents currency]
  (when cents
    (let [symbol (case currency
                   "EUR" "€"
                   "USD" "$"
                   "GBP" "£"
                   currency)
          amount (/ cents 100.0)]
      (str symbol (String/format java.util.Locale/US "%.2f" (into-array Object [(double amount)]))))))

;; =============================================================================
;; Template Context
;; =============================================================================

(defn build-context
  "Build template context from event data.
   
   Returns map of variables for template rendering."
  [event template]
  (let [payload (:payload event)
        base {:event-id (:id event)
              :event-type (when (:type event) (name (:type event)))
              :timestamp (get-in event [:metadata :timestamp])}]
    (merge base
           (case template
             :order-confirmation
             {:order-number (:order-number payload)
              :customer-name (:customer-name payload)
              :total (format-price (:total-cents payload) (:currency payload "EUR"))
              :items (:items payload)}
             
             :payment-receipt
             {:order-number (:order-number payload)
              :amount (format-price (:amount-cents payload) (:currency payload "EUR"))
              :payment-method (:payment-method payload "Card")}
             
             :shipping-update
             {:order-number (:order-number payload)
              :tracking-number (:tracking-number payload)
              :carrier (:carrier payload)
              :estimated-delivery (:estimated-delivery payload)}
             
             ;; Default context
             payload))))

;; =============================================================================
;; Notification Creation
;; =============================================================================

(defn create-notification
  "Create a new notification.
   
   Args:
     event    - Source event
     channel  - Notification channel (:email, :sms, :push)
     template - Template to use
     recipient - Recipient address/number
     now      - Current timestamp
   
   Returns:
     Notification map"
  [event channel template recipient now]
  {:id (random-uuid)
   :event-id (:id event)
   :channel channel
   :recipient recipient
   :template template
   :context (build-context event template)
   :status :pending
   :attempts 0
   :last-attempt-at nil
   :sent-at nil
   :error nil
   :created-at now
   :updated-at now})

;; =============================================================================
;; Template Rendering
;; =============================================================================

(def templates
  "Notification templates by name."
  {:order-confirmation
   {:subject "Order Confirmation - {{order-number}}"
    :body "Hi {{customer-name}},\n\nThank you for your order {{order-number}}!\n\nTotal: {{total}}\n\nWe'll notify you when your order ships."}
   
   :payment-receipt
   {:subject "Payment Received - {{order-number}}"
    :body "Your payment of {{amount}} for order {{order-number}} has been received.\n\nPayment method: {{payment-method}}"}
   
   :shipping-update
   {:subject "Your Order Has Shipped - {{order-number}}"
    :body "Great news! Your order {{order-number}} is on its way.\n\nTracking: {{tracking-number}}\nCarrier: {{carrier}}\nEstimated delivery: {{estimated-delivery}}"}
   
   :order-cancelled
   {:subject "Order Cancelled - {{order-number}}"
    :body "Your order {{order-number}} has been cancelled.\n\nIf you didn't request this, please contact support."}
   
   :delivery-confirmation
   {:subject "Delivered! - {{order-number}}"
    :body "Your order {{order-number}} has been delivered!\n\nThank you for shopping with us."}})

(defn render-template
  "Render a template with context variables.
   
   Replaces {{variable}} placeholders with values from context."
  [template-key context]
  (if-let [template (get templates template-key)]
    (let [render-str (fn [s]
                       (reduce-kv (fn [acc k v]
                                    (str/replace acc 
                                                 (str "{{" (name k) "}}") 
                                                 (str v)))
                                  s
                                  context))]
      {:ok {:subject (render-str (:subject template))
            :body (render-str (:body template))}})
    {:error :template-not-found
     :template template-key}))

;; =============================================================================
;; Status Transitions
;; =============================================================================

(defn mark-sent
  "Mark notification as successfully sent."
  [notification now]
  (-> notification
      (assoc :status :sent)
      (assoc :sent-at now)
      (update :attempts inc)
      (assoc :last-attempt-at now)
      (assoc :updated-at now)))

(defn mark-failed
  "Mark notification as failed with error."
  [notification error now]
  (-> notification
      (assoc :status :failed)
      (assoc :error (str error))
      (update :attempts inc)
      (assoc :last-attempt-at now)
      (assoc :updated-at now)))

(defn reset-for-retry
  "Reset notification for retry attempt."
  [notification now]
  (-> notification
      (assoc :status :pending)
      (assoc :error nil)
      (assoc :updated-at now)))

;; =============================================================================
;; Retry Logic
;; =============================================================================

(defn can-retry?
  "Check if notification can be retried."
  [notification config]
  (and (= :failed (:status notification))
       (< (:attempts notification) (:max-attempts config))))

(defn calculate-next-retry
  "Calculate when notification should be retried."
  [notification config now]
  (let [backoff (retry/calculate-backoff 
                 (:attempts notification)
                 (:base-delay-ms config)
                 (:max-delay-ms config))]
    (.plusMillis now backoff)))

;; =============================================================================
;; Serialization
;; =============================================================================

(defn notification->api
  "Transform notification for API response."
  [notification]
  (-> notification
      (update :channel name)
      (update :template name)
      (update :status name)))

(defn notifications->api
  "Transform multiple notifications for API response."
  [notifications]
  (mapv notification->api notifications))
