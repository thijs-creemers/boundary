(ns notification.notification.shell.store
  "In-memory notification store implementation."
  (:require [notification.notification.ports :as ports]))

;; =============================================================================
;; In-Memory Store Implementation
;; =============================================================================

(defrecord InMemoryNotificationStore [notifications]
  ports/INotificationStore
  
  (save-notification! [_ notification]
    (swap! notifications assoc (:id notification) notification)
    notification)
  
  (find-notification [_ notification-id]
    (get @notifications notification-id))
  
  (find-by-event [_ event-id]
    (->> (vals @notifications)
         (filter #(= event-id (:event-id %)))
         vec))
  
  (list-notifications [_ options]
    (let [{:keys [status channel recipient limit offset]
           :or {limit 50 offset 0}} options
          all-notifications (vals @notifications)
          filtered (->> all-notifications
                        (filter (fn [n]
                                  (and (or (nil? status) (= status (:status n)))
                                       (or (nil? channel) (= channel (:channel n)))
                                       (or (nil? recipient) (= recipient (:recipient n))))))
                        (sort-by :created-at #(compare %2 %1)))
          total (count filtered)
          page (->> filtered
                    (drop offset)
                    (take limit)
                    vec)]
      {:notifications page
       :total total}))
  
  (list-pending [_]
    (->> (vals @notifications)
         (filter #(= :pending (:status %)))
         vec)))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-store
  "Create a new in-memory notification store."
  []
  (->InMemoryNotificationStore (atom {})))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn get-stats
  "Get store statistics."
  [store]
  (let [all (vals @(:notifications store))]
    {:total (count all)
     :by-status (->> all
                     (group-by :status)
                     (map (fn [[k v]] [k (count v)]))
                     (into {}))
     :by-channel (->> all
                      (group-by :channel)
                      (map (fn [[k v]] [k (count v)]))
                      (into {}))}))
