(ns notification.notification.ports
  "Port definitions for the notification module.")

;; =============================================================================
;; Notification Store Port
;; =============================================================================

(defprotocol INotificationStore
  "Interface for notification persistence."
  
  (save-notification! [this notification]
    "Persist a notification. Returns saved notification.")
  
  (find-notification [this notification-id]
    "Find notification by ID. Returns notification or nil.")
  
  (find-by-event [this event-id]
    "Find notifications for an event. Returns vector.")
  
  (list-notifications [this options]
    "List notifications with filtering.
     Options: :status, :channel, :recipient, :limit, :offset
     Returns {:notifications [...] :total n}")
  
  (list-pending [this]
    "List notifications pending retry."))

;; =============================================================================
;; Notification Sender Port
;; =============================================================================

(defprotocol INotificationSender
  "Interface for sending notifications.
   Different implementations for each channel."
  
  (send-notification! [this notification]
    "Send a notification.
     Returns {:ok :sent} or {:error error-type :message ...}")
  
  (supports-channel? [this channel]
    "Check if sender supports a channel."))

;; =============================================================================
;; Notification Service Port
;; =============================================================================

(defprotocol INotificationService
  "Service interface for notification operations."
  
  (create-notification [this event channel template]
    "Create a new notification for an event.
     Returns {:ok notification} or {:error ...}")
  
  (send-and-update [this notification-id]
    "Send a notification and update its status.
     Returns {:ok notification} or {:error ...}")
  
  (retry-notification [this notification-id]
    "Retry a failed notification.
     Returns {:ok notification} or {:error ...}")
  
  (get-notification [this notification-id]
    "Get notification by ID.
     Returns {:ok notification} or {:error :not-found}")
  
  (list-notifications [this options]
    "List notifications with filtering.
     Returns {:ok {:notifications [...] :total n}}"))
