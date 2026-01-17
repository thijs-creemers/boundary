(ns notification.event.ports
  "Port definitions for the event module.")

;; =============================================================================
;; Event Store Port
;; =============================================================================

(defprotocol IEventStore
  "Interface for event persistence."
  
  (save-event! [this event]
    "Persist an event. Returns saved event.")
  
  (find-event [this event-id]
    "Find event by ID. Returns event or nil.")
  
  (list-events [this options]
    "List events with filtering.
     Options: :type, :aggregate-id, :since, :limit, :offset
     Returns {:events [...] :total n}")
  
  (event-processed? [this event-id]
    "Check if event has already been processed (idempotency).")
  
  (mark-processed! [this event-id]
    "Mark event as processed."))

;; =============================================================================
;; Event Service Port
;; =============================================================================

(defprotocol IEventService
  "Service interface for event operations."
  
  (publish-event [this event-data]
    "Create and publish a new event.
     Returns {:ok event} or {:error ...}")
  
  (get-event [this event-id]
    "Get event by ID.
     Returns {:ok event} or {:error :not-found}")
  
  (list-recent-events [this options]
    "List recent events.
     Returns {:ok {:events [...] :total n}}"))
