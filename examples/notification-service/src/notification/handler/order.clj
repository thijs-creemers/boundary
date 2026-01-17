(ns notification.handler.order
  "Event handler for order-related events.
   
   Subscribes to order events and creates appropriate notifications."
  (:require [notification.notification.ports :as notif-ports]
            [notification.event.ports :as event-ports]))

;; =============================================================================
;; Order Event Handlers
;; =============================================================================

(defn handle-order-placed
  "Handle order.placed event.
   Creates order confirmation notification."
  [notification-service event]
  (println "[Handler] Processing order.placed:" (:id event))
  (let [result (notif-ports/create-notification 
                notification-service 
                event 
                :email 
                :order-confirmation)]
    (if (:ok result)
      (let [notification (:ok result)]
        ;; Immediately send the notification
        (notif-ports/send-and-update notification-service (:id notification)))
      (println "[Handler] Failed to create notification:" (:error result)))))

(defn handle-order-confirmed
  "Handle order.confirmed event.
   Creates order confirmed notification."
  [notification-service event]
  (println "[Handler] Processing order.confirmed:" (:id event))
  (notif-ports/create-notification 
   notification-service 
   event 
   :email 
   :order-confirmed))

(defn handle-order-cancelled
  "Handle order.cancelled event.
   Creates order cancelled notification."
  [notification-service event]
  (println "[Handler] Processing order.cancelled:" (:id event))
  (let [result (notif-ports/create-notification 
                notification-service 
                event 
                :email 
                :order-cancelled)]
    (when (:ok result)
      (notif-ports/send-and-update notification-service (get-in result [:ok :id])))))

;; =============================================================================
;; Handler Registry
;; =============================================================================

(defn register-handlers
  "Register order event handlers with the message bus."
  [bus notification-service]
  (require '[notification.shared.bus :as msg-bus])
  (let [subscribe! (resolve 'notification.shared.bus/subscribe!)]
    (subscribe! bus :order/placed 
                (fn [event] (handle-order-placed notification-service event)))
    (subscribe! bus :order/confirmed 
                (fn [event] (handle-order-confirmed notification-service event)))
    (subscribe! bus :order/cancelled 
                (fn [event] (handle-order-cancelled notification-service event)))))
