(ns notification.event.shell.store
  "In-memory event store implementation.
   
   For production, replace with a proper event store (EventStoreDB, Kafka, etc.)"
  (:require [notification.event.ports :as ports])
  (:import [java.time Instant]))

;; =============================================================================
;; In-Memory Store Implementation
;; =============================================================================

(defrecord InMemoryEventStore [events processed-ids]
  ports/IEventStore
  
  (save-event! [_ event]
    (swap! events assoc (:id event) event)
    event)
  
  (find-event [_ event-id]
    (get @events event-id))
  
  (list-events [_ options]
    (let [{:keys [type aggregate-id since limit offset]
           :or {limit 50 offset 0}} options
          all-events (vals @events)
          filtered (->> all-events
                        (filter (fn [e]
                                  (and (or (nil? type) (= type (:type e)))
                                       (or (nil? aggregate-id) (= aggregate-id (:aggregate-id e)))
                                       (or (nil? since) (.isAfter (:created-at e) since)))))
                        (sort-by :created-at #(compare %2 %1)))  ;; newest first
          total (count filtered)
          page (->> filtered
                    (drop offset)
                    (take limit)
                    vec)]
      {:events page
       :total total}))
  
  (event-processed? [_ event-id]
    (contains? @processed-ids event-id))
  
  (mark-processed! [_ event-id]
    (swap! processed-ids conj event-id)
    true))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-store
  "Create a new in-memory event store."
  []
  (->InMemoryEventStore (atom {}) (atom #{})))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn get-stats
  "Get store statistics."
  [store]
  {:total-events (count @(:events store))
   :processed-count (count @(:processed-ids store))})
