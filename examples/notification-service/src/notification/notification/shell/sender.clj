(ns notification.notification.shell.sender
  "Mock notification sender implementation.
   
   In production, replace with actual email/SMS/push providers."
  (:require [notification.notification.ports :as ports]
            [notification.notification.core.notification :as notif-core]))

;; =============================================================================
;; Mock Sender Implementation
;; =============================================================================

(defrecord MockNotificationSender [config sent-notifications]
  ports/INotificationSender
  
  (send-notification! [_ notification]
    (let [channel (:channel notification)
          channel-config (get-in config [:channels channel])
          template-key (:template notification)]
      (cond
        ;; Channel disabled
        (not (:enabled channel-config))
        {:error :channel-disabled
         :message (str "Channel " (name channel) " is disabled")}
        
        ;; Missing recipient
        (empty? (:recipient notification))
        {:error :invalid-recipient
         :message "Recipient is required"}
        
        ;; Render template
        :else
        (let [render-result (notif-core/render-template template-key (:context notification))]
          (if (:error render-result)
            render-result
            ;; Simulate sending (95% success rate for testing)
            (if (< (rand) 0.95)
              (do
                ;; Log the "sent" notification for testing
                (swap! sent-notifications conj
                       {:notification-id (:id notification)
                        :channel channel
                        :recipient (:recipient notification)
                        :subject (get-in render-result [:ok :subject])
                        :body (get-in render-result [:ok :body])
                        :sent-at (java.time.Instant/now)})
                (println (format "[%s] Sent to %s: %s"
                                 (name channel)
                                 (:recipient notification)
                                 (get-in render-result [:ok :subject])))
                {:ok :sent})
              ;; Simulate random failure
              {:error :delivery-failed
               :message "Simulated delivery failure"}))))))
  
  (supports-channel? [_ channel]
    (contains? #{:email :sms :push} channel)))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-sender
  "Create a new mock notification sender.
   
   Config should contain :channels map with enabled/disabled channels."
  [config]
  (->MockNotificationSender config (atom [])))

;; =============================================================================
;; Test Utilities
;; =============================================================================

(defn get-sent-notifications
  "Get all notifications that were 'sent' (for testing)."
  [sender]
  @(:sent-notifications sender))

(defn clear-sent-notifications
  "Clear sent notification history (for testing)."
  [sender]
  (reset! (:sent-notifications sender) []))
