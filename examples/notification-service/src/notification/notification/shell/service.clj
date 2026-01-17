(ns notification.notification.shell.service
  "Notification service - orchestrates notification operations."
  (:require [notification.notification.ports :as ports]
            [notification.notification.core.notification :as notif-core]
            [notification.event.core.event :as event-core])
  (:import [java.time Instant]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- now []
  (Instant/now))

;; =============================================================================
;; Service Implementation
;; =============================================================================

(defrecord NotificationService [store sender config]
  ports/INotificationService
  
  (create-notification [_ event channel template]
    (let [recipient (event-core/extract-recipient event)]
      (if recipient
        (let [notification (notif-core/create-notification 
                           event channel template recipient (now))]
          (ports/save-notification! store notification)
          {:ok notification})
        {:error :invalid-recipient
         :message "Could not extract recipient from event"})))
  
  (send-and-update [_ notification-id]
    (if-let [notification (ports/find-notification store notification-id)]
      (let [result (ports/send-notification! sender notification)]
        (if (:ok result)
          ;; Mark as sent
          (let [updated (notif-core/mark-sent notification (now))]
            (ports/save-notification! store updated)
            {:ok updated})
          ;; Mark as failed
          (let [updated (notif-core/mark-failed notification (:message result) (now))]
            (ports/save-notification! store updated)
            {:ok updated})))
      {:error :not-found :id notification-id}))
  
  (retry-notification [this notification-id]
    (if-let [notification (ports/find-notification store notification-id)]
      (let [retry-config (:retry config)]
        (if (notif-core/can-retry? notification retry-config)
          ;; Reset and send
          (let [reset (notif-core/reset-for-retry notification (now))]
            (ports/save-notification! store reset)
            (ports/send-and-update this notification-id))
          {:error :retry-exhausted
           :attempts (:attempts notification)
           :max-attempts (:max-attempts retry-config)}))
      {:error :not-found :id notification-id}))
  
  (get-notification [_ notification-id]
    (if-let [notification (ports/find-notification store notification-id)]
      {:ok notification}
      {:error :not-found :id notification-id}))
  
  (list-notifications [_ options]
    (let [result (ports/list-notifications store options)]
      {:ok result})))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-service
  "Create a new notification service."
  [store sender config]
  (->NotificationService store sender config))
