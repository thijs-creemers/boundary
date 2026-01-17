(ns notification.event.shell.service
  "Event service - orchestrates event operations."
  (:require [notification.event.ports :as ports]
            [notification.event.schema :as schema]
            [notification.event.core.event :as event-core]
            [notification.shared.bus :as bus])
  (:import [java.time Instant]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- now []
  (Instant/now))

;; =============================================================================
;; Service Implementation
;; =============================================================================

(defrecord EventService [store message-bus]
  ports/IEventService
  
  (publish-event [_ event-data]
    ;; Validate event data
    (let [validation (schema/validate schema/PublishEventRequest 
                                      (select-keys event-data [:type :aggregate-id :aggregate-type :payload]))]
      (if (:error validation)
        validation
        ;; Create event
        (let [event (event-core/create-event event-data (:correlation-id event-data) (now))]
          ;; Save to store
          (ports/save-event! store event)
          ;; Publish to message bus for handlers
          (let [topics (event-core/route-event event)]
            (doseq [topic topics]
              (bus/publish! message-bus topic event)))
          {:ok event}))))
  
  (get-event [_ event-id]
    (if-let [event (ports/find-event store event-id)]
      {:ok event}
      {:error :not-found :id event-id}))
  
  (list-recent-events [_ options]
    (let [result (ports/list-events store options)]
      {:ok result})))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-service
  "Create a new event service."
  [store message-bus]
  (->EventService store message-bus))
