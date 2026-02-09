(ns boundary.realtime.core.message
  "Pure functions for WebSocket message validation and routing.
  
  Following FC/IS pattern - all functions are pure (no I/O).
  Message creation, validation, and routing logic."
  (:require [boundary.realtime.schema :as schema]
            [boundary.realtime.core.connection :as conn]
            [malli.core :as m]))

;; Message Creation (Pure)

(defn create-message
  "Create new message record.
  
  Pure function - deterministic for given inputs.
  
  Args:
    message-input - Map with :type, :payload, :target (optional)
  
  Returns:
    Message map with timestamp"
  [{:keys [type payload target] :as message-input}]
  {:pre [(m/validate schema/MessageInput message-input)]}
  (cond-> {:type type
           :payload payload
           :timestamp (java.time.Instant/now)}
    target (assoc :target target)))

(defn broadcast-message
  "Create broadcast message (send to all connections).
  
  Pure function - no side effects.
  
  Args:
    payload - Message payload map
  
  Returns:
    Broadcast message map"
  [payload]
  {:pre [(map? payload)]}
  (create-message {:type :broadcast
                   :payload payload}))

(defn user-message
  "Create user message (send to specific user).
  
  Pure function - no side effects.
  
  Args:
    user-id - Target user UUID
    payload - Message payload map
  
  Returns:
    User message map"
  [user-id payload]
  {:pre [(uuid? user-id)
         (map? payload)]}
  (create-message {:type :user
                   :payload payload
                   :target user-id}))

(defn role-message
  "Create role message (send to users with specific role).
  
  Pure function - no side effects.
  
  Args:
    role - Target role keyword
    payload - Message payload map
  
  Returns:
    Role message map"
  [role payload]
  {:pre [(keyword? role)
         (map? payload)]}
  (create-message {:type :role
                   :payload payload
                   :target role}))

(defn connection-message
  "Create connection-specific message.
  
  Pure function - no side effects.
  
  Args:
    connection-id - Target connection UUID
    payload - Message payload map
  
  Returns:
    Connection message map"
  [connection-id payload]
  {:pre [(uuid? connection-id)
         (map? payload)]}
  (create-message {:type :connection
                   :payload payload
                   :target connection-id}))

;; Message Validation (Pure)

(defn valid-message?
  "Validate message against schema.
  
  Pure function - no side effects.
  
  Args:
    message - Message map
  
  Returns:
    Boolean - true if valid"
  [message]
  (m/validate schema/Message message))

(defn explain-message
  "Explain why message is invalid.
  
  Pure function - returns validation errors.
  
  Args:
    message - Message map
  
  Returns:
    Malli explanation or nil if valid"
  [message]
  (m/explain schema/Message message))

;; Message Routing (Pure)

(defn route-message
  "Determine which connection IDs should receive message.
  
  Pure function - no side effects, just logic.
  
  Args:
    message - Message map
    connections - Collection of Connection records
  
  Returns:
    Vector of connection IDs that should receive message"
  [message connections]
  {:pre [(valid-message? message)]}
  (case (:type message)
    :broadcast
    (mapv :id connections)
    
    :user
    (let [user-id (:target message)]
      (->> connections
           (filter #(= (:user-id %) user-id))
           (mapv :id)))
    
    :role
    (let [role (:target message)]
      (->> connections
           (filter #(contains? (:roles %) role))
           (mapv :id)))
    
    :connection
    (let [connection-id (:target message)]
      (if (some #(= (:id %) connection-id) connections)
        [connection-id]
        []))
    
    ;; Unknown type - no targets
    []))

(defn route-to-connections
  "Determine which Connection records should receive message.
  
  Pure function - returns full connection records instead of just IDs.
  Useful for downstream processing that needs connection metadata.
  
  Args:
    message - Message map
    connections - Collection of Connection records
  
  Returns:
    Vector of Connection records that should receive message"
  [message connections]
  {:pre [(valid-message? message)]}
  (let [target-ids (set (route-message message connections))]
    (vec (filter #(contains? target-ids (:id %)) connections))))

;; Message Filtering (Pure)

(defn filter-by-type
  "Filter messages by type.
  
  Pure function - no side effects.
  
  Args:
    messages - Collection of message maps
    message-type - Message type keyword
  
  Returns:
    Vector of messages with specified type"
  [messages message-type]
  {:pre [(keyword? message-type)]}
  (vec (filter #(= (:type %) message-type) messages)))

(defn filter-by-target
  "Filter messages by target.
  
  Pure function - no side effects.
  
  Args:
    messages - Collection of message maps
    target - Target (UUID or keyword)
  
  Returns:
    Vector of messages with specified target"
  [messages target]
  (vec (filter #(= (:target %) target) messages)))

(defn filter-recent
  "Filter messages by time window.
  
  Pure function - no side effects.
  
  Args:
    messages - Collection of message maps
    duration - Time window (java.time.Duration)
    now - Current instant (for testability)
  
  Returns:
    Vector of messages within time window"
  [messages duration now]
  {:pre [(instance? java.time.Duration duration)
         (inst? now)]}
  (let [cutoff (.minus now duration)]
    (vec (filter #(.isAfter (:timestamp %) cutoff) messages))))

;; Message Statistics (Pure)

(defn message-count-by-type
  "Count messages by type.
  
  Pure function - no side effects.
  
  Args:
    messages - Collection of message maps
  
  Returns:
    Map of {message-type count}"
  [messages]
  (frequencies (map :type messages)))

(defn messages-for-connection
  "Filter messages that would be routed to specific connection.
  
  Pure function - useful for testing routing logic.
  
  Args:
    messages - Collection of message maps
    connection - Connection record
    all-connections - All active connections (for routing context)
  
  Returns:
    Vector of messages that would reach this connection"
  [messages connection all-connections]
  {:pre [(conn/valid-connection? connection)]}
  (let [connection-id (:id connection)]
    (vec (filter (fn [msg]
                   (contains? (set (route-message msg all-connections))
                              connection-id))
                 messages))))
