(ns boundary.realtime.shell.service
  "Realtime service implementation (Shell layer).
  
  Orchestrates WebSocket messaging between core logic and adapters.
  Implements the imperative shell in the FC/IS architecture pattern.
  
  Responsibilities (Shell/I/O):
  - WebSocket connection lifecycle (open, close)
  - JWT authentication (delegates to user module)
  - Message routing (uses core routing logic)
  - Connection registry management
  - Logging and error handling
  
  Does NOT contain:
  - Business logic (lives in core.*)
  - Database operations (no persistence needed for WebSockets)
  - Message validation logic (lives in core.message)"
  (:require [boundary.realtime.ports :as ports]
            [boundary.realtime.core.connection :as conn]
            [boundary.realtime.core.auth :as auth]
            [clojure.tools.logging :as log])
  (:import (java.time Instant)
           (java.util UUID)))

;; =============================================================================
;; Helper Functions (External Dependencies)
;; =============================================================================

(defn- current-timestamp
  "Get current timestamp. Shell layer responsibility for time dependency."
  []
  (Instant/now))

(defn- generate-connection-id
  "Generate UUID for new connection. Shell layer responsibility."
  []
  (UUID/randomUUID))

;; =============================================================================
;; Realtime Service (Shell Layer)
;; =============================================================================

(defrecord RealtimeService [connection-registry jwt-verifier logger error-reporter]
  ports/IRealtimeService

  (connect [this ws-connection query-params]
    ;; Shell: I/O and side effects for connection establishment
    (try
      ;; 1. Extract token from query params (pure core function)
      (let [token (auth/extract-token-from-query query-params)]
        (when-not token
          (throw (ex-info "Unauthorized: Missing JWT token in query params"
                          {:type :unauthorized
                           :message "WebSocket connection requires 'token' query parameter"})))

        ;; 2. Verify JWT token (delegates to user module via adapter)
        (let [claims (ports/verify-jwt jwt-verifier token)

              ;; 3. Create connection record (pure core function)
              connection (conn/create-connection
                          (:user-id claims)
                          (:roles claims)
                          {:email (:email claims)
                           :connected-at (current-timestamp)})

              connection-id (:id connection)]

          ;; 4. Register connection in registry (I/O - stateful atom)
          (ports/register connection-registry connection-id connection ws-connection)

          ;; 5. Log connection event
          (log/info "WebSocket connection established"
                    {:connection-id connection-id
                     :user-id (:user-id claims)
                     :email (:email claims)
                     :roles (:roles claims)})

          ;; Return connection ID
          connection-id))
      (catch clojure.lang.ExceptionInfo e
        ;; Re-throw with proper logging
        (log/warn "WebSocket connection failed"
                  {:error-type (:type (ex-data e))
                   :message (.getMessage e)})
        (throw e))
      (catch Exception e
        ;; Unexpected error - log and wrap
        (log/error e "Unexpected error during WebSocket connection")
        (throw (ex-info "Internal error during WebSocket connection"
                        {:type :internal-error
                         :message (.getMessage e)})))))

  (disconnect [this connection-id]
    ;; Shell: Clean up connection
    (try
      ;; 1. Get connection for logging
      (let [connection (ports/find-connection connection-registry connection-id)]

        ;; 2. Remove from registry
        (ports/unregister connection-registry connection-id)

        ;; 3. Log disconnection
        (when connection
          (log/info "WebSocket connection closed"
                    {:connection-id connection-id
                     :user-id (:user-id connection)
                     :duration-seconds (conn/connection-age connection (current-timestamp))}))

        nil)
      (catch Exception e
        ;; Log error but don't throw - disconnection is best-effort
        (log/warn e "Error during WebSocket disconnection"
                  {:connection-id connection-id}))))

  (send-to-user [this user-id message]
    ;; Shell: Route message to user's connections
    (try
      ;; 1. Add timestamp if missing (shell responsibility)
      (let [message-with-timestamp (if (:timestamp message)
                                     message
                                     (assoc message :timestamp (current-timestamp)))

            ;; 2. Find connections for user (registry lookup)
            ws-adapters (ports/find-by-user connection-registry user-id)

            ;; 3. Send to each connection (I/O)
            send-count (atom 0)]

        (doseq [ws-adapter ws-adapters]
          (when (ports/open? ws-adapter)
            (ports/send-message ws-adapter message-with-timestamp)
            (swap! send-count inc)))

        ;; 4. Log send event
        (log/debug "Sent message to user"
                   {:user-id user-id
                    :message-type (:type message)
                    :connection-count @send-count})

        @send-count)
      (catch Exception e
        (log/error e "Error sending message to user"
                   {:user-id user-id
                    :message-type (:type message)})
        0)))

  (send-to-role [this role message]
    ;; Shell: Route message to connections with role
    (try
      ;; 1. Add timestamp if missing
      (let [message-with-timestamp (if (:timestamp message)
                                     message
                                     (assoc message :timestamp (current-timestamp)))

            ;; 2. Find connections with role (registry lookup)
            ws-adapters (ports/find-by-role connection-registry role)

            ;; 3. Send to each connection (I/O)
            send-count (atom 0)]

        (doseq [ws-adapter ws-adapters]
          (when (ports/open? ws-adapter)
            (ports/send-message ws-adapter message-with-timestamp)
            (swap! send-count inc)))

        ;; 4. Log send event
        (log/debug "Sent message to role"
                   {:role role
                    :message-type (:type message)
                    :connection-count @send-count})

        @send-count)
      (catch Exception e
        (log/error e "Error sending message to role"
                   {:role role
                    :message-type (:type message)})
        0)))

  (broadcast [this message]
    ;; Shell: Broadcast to all connections
    (try
      ;; 1. Add timestamp if missing
      (let [message-with-timestamp (if (:timestamp message)
                                     message
                                     (assoc message :timestamp (current-timestamp)))

            ;; 2. Get all connections (registry lookup)
            ws-adapters (ports/all-connections connection-registry)

            ;; 3. Send to each connection (I/O)
            send-count (atom 0)]

        (doseq [ws-adapter ws-adapters]
          (when (ports/open? ws-adapter)
            (ports/send-message ws-adapter message-with-timestamp)
            (swap! send-count inc)))

        ;; 4. Log broadcast event
        (log/info "Broadcast message"
                  {:message-type (:type message)
                   :connection-count @send-count})

        @send-count)
      (catch Exception e
        (log/error e "Error broadcasting message"
                   {:message-type (:type message)})
        0)))

  (send-to-connection [this connection-id message]
    ;; Shell: Send to specific connection
    (try
      ;; 1. Add timestamp if missing
      (let [message-with-timestamp (if (:timestamp message)
                                     message
                                     (assoc message :timestamp (current-timestamp)))

            ;; 2. Find connection
            connection (ports/find-connection connection-registry connection-id)]

        (if connection
          ;; 3. Get ws-adapter from registry
          (let [ws-adapter (-> @(:state connection-registry)
                               (get connection-id)
                               :ws-adapter)]
            (if (and ws-adapter (ports/open? ws-adapter))
              ;; 4. Send message
              (do (ports/send-message ws-adapter message-with-timestamp)

                  ;; 5. Log send event
                  (log/debug "Sent message to connection"
                             {:connection-id connection-id
                              :message-type (:type message)})

                  true)
              (do
                (log/warn "Connection not open"
                          {:connection-id connection-id})
                false)))
          (do
            (log/warn "Connection not found"
                      {:connection-id connection-id})
            false)))
      (catch Exception e
        (log/error e "Error sending message to connection"
                   {:connection-id connection-id
                    :message-type (:type message)})
        false))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-realtime-service
  "Create realtime service for WebSocket messaging.
  
  Args:
    connection-registry - IConnectionRegistry implementation
    jwt-verifier - IJWTVerifier implementation
    logger - Logger instance (optional, uses clojure.tools.logging)
    error-reporter - Error reporter instance (optional)
  
  Returns:
    RealtimeService instance implementing IRealtimeService"
  [connection-registry jwt-verifier & {:keys [logger error-reporter]}]
  (->RealtimeService connection-registry jwt-verifier logger error-reporter))
