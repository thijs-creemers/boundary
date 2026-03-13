(ns boundary.calendar.core.event
  "Event type registry and pure helper functions.

   Provides the `defevent` macro for declaring event type definitions as data,
   an in-process registry backed by an atom, and pure helper functions
   for working with EventData maps.

   FC/IS rule: no I/O here. All side effects live in the shell layer."
  (:require [boundary.calendar.schema :as schema])
  (:import [java.time Duration Instant ZoneId]))

;; =============================================================================
;; Global definition registry (in-process)
;; =============================================================================

(defonce ^:private registry-atom (atom {}))

;; =============================================================================
;; defevent macro
;; =============================================================================

(defmacro defevent
  "Define and register an event type.

   The body is a map literal that must satisfy EventDef schema.
   After macro expansion the definition is automatically registered in the
   in-process registry so it is available via `get-event-type`.

   Example:

     (defevent appointment-event
       {:id    :appointment
        :label \"Appointment\"
        :schema [:map
                 [:patient-id :uuid]
                 [:room       :string]]})

   The var `appointment-event` is bound to the definition map.
   The event type is registered under :appointment."
  [sym definition-map]
  `(do
     (def ~sym ~definition-map)
     (register-event-type! ~sym)
     ~sym))

;; =============================================================================
;; Registry operations (pure — no I/O)
;; =============================================================================

(defn register-event-type!
  "Register an event type definition in the in-process registry.

   Args:
     definition - EventDef map

   Returns the definition map."
  [definition]
  (swap! registry-atom assoc (:id definition) definition)
  definition)

(defn get-event-type
  "Look up an event type definition by id.

   Returns the definition map or nil if not found."
  [id]
  (get @registry-atom id))

(defn list-event-types
  "Return a vector of all registered event type ids."
  []
  (vec (keys @registry-atom)))

(defn clear-registry!
  "Reset the registry to an empty map.

   Use in tests to avoid inter-test pollution."
  []
  (reset! registry-atom {}))

;; =============================================================================
;; Pure helpers
;; =============================================================================

(defn duration
  "Return the java.time.Duration between an event's :start and :end.

   Args:
     event - EventData map with :start and :end Instants

   Returns java.time.Duration (may be zero or negative for malformed events)."
  [event]
  (Duration/between ^Instant (:start event) ^Instant (:end event)))

(defn all-day?
  "Return true if the event spans at least 24 hours and starts at midnight UTC.

   Args:
     event - EventData map

   Returns boolean."
  [event]
  (let [dur     (duration event)
        start   ^Instant (:start event)
        zdt     (.atZone start (ZoneId/of "UTC"))
        midnight? (and (zero? (.getHour zdt))
                       (zero? (.getMinute zdt))
                       (zero? (.getSecond zdt))
                       (zero? (.getNano zdt)))]
    (and midnight?
         (>= (.toHours dur) 24))))

(defn within-range?
  "Return true if the event overlaps with [range-start, range-end).

   Uses half-open interval: event start < range-end AND event end > range-start.

   Args:
     event       - EventData map with :start and :end Instants
     range-start - java.time.Instant
     range-end   - java.time.Instant

   Returns boolean."
  [event range-start range-end]
  (let [es ^Instant (:start event)
        ee ^Instant (:end event)
        rs ^Instant range-start
        re ^Instant range-end]
    (and (.isBefore es re)
         (.isAfter ee rs))))

(defn valid-event?
  "Returns true if the given map satisfies EventData schema.

   Delegates to boundary.calendar.schema/valid-event?."
  [event]
  (schema/valid-event? event))

(defn explain-event
  "Returns human-readable validation errors for an event map.

   Delegates to boundary.calendar.schema/explain-event."
  [event]
  (schema/explain-event event))
