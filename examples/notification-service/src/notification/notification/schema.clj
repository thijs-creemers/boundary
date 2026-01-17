(ns notification.notification.schema
  "Malli schemas for notifications."
  (:require [malli.core :as m]))

;; =============================================================================
;; Notification Types
;; =============================================================================

(def NotificationChannel
  "Supported notification channels."
  [:enum :email :sms :push])

(def NotificationStatus
  "Notification delivery status."
  [:enum :pending :sent :failed :skipped])

(def NotificationTemplate
  "Available notification templates."
  [:enum
   :order-confirmation
   :order-confirmed
   :order-cancelled
   :payment-receipt
   :payment-failed
   :refund-confirmation
   :shipping-update
   :delivery-confirmation
   :return-received])

;; =============================================================================
;; Notification Entity
;; =============================================================================

(def Notification
  "Notification entity schema."
  [:map
   [:id uuid?]
   [:event-id uuid?]
   [:channel NotificationChannel]
   [:recipient :string]
   [:template NotificationTemplate]
   [:context :map]
   [:status NotificationStatus]
   [:attempts nat-int?]
   [:last-attempt-at {:optional true} [:maybe inst?]]
   [:sent-at {:optional true} [:maybe inst?]]
   [:error {:optional true} [:maybe :string]]
   [:created-at inst?]
   [:updated-at inst?]])

;; =============================================================================
;; API Schemas
;; =============================================================================

(def RetryNotificationRequest
  "Request to retry a failed notification."
  [:map
   [:force {:optional true} :boolean]])  ;; Force retry even if max attempts reached

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate
  "Validate data against schema."
  [schema data]
  (if (m/validate schema data)
    {:ok data}
    {:error :validation
     :details (m/explain schema data)}))
