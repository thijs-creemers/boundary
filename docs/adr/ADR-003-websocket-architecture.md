# ADR-003: WebSocket Architecture for Real-time Module

**Status:** Proposed  
**Date:** 2026-02-04  
**Deciders:** Boundary Core Team  
**Context:** Real-time/WebSocket Module (Phase 5)

---

## Context and Problem Statement

The Boundary framework currently supports HTTP-based request-response patterns but lacks built-in support for real-time, bidirectional communication. Modern web applications often require real-time features such as:

- Live notifications (e.g., new message alerts, system events)
- Dashboard updates (e.g., metrics, status changes)
- Collaborative features (e.g., live document editing indicators)
- Event streaming (e.g., order status updates, job progress)

Users currently must implement WebSocket support from scratch, leading to inconsistent patterns and integration challenges with Boundary's existing authentication and module system.

This ADR defines the architecture for a `libs/realtime/` module that provides WebSocket support with first-class integration into the Boundary framework's Functional Core / Imperative Shell paradigm.

---

## Decision Drivers

### Technical Drivers
- **FC/IS Compliance**: Must follow Functional Core / Imperative Shell architecture
- **Authentication Integration**: Seamless JWT-based auth with existing `boundary/user` module
- **Simplicity**: Start with core use cases, avoid premature complexity
- **Testability**: Pure functions in core, mockable adapters in shell
- **Performance**: Efficient message routing without external dependencies

### User Experience Drivers
- **Developer Ergonomics**: Simple API for sending/receiving messages
- **Documentation**: Clear examples for common use cases
- **Observability**: Integration with existing logging and metrics

### Scope Drivers (Explicit Non-Goals)
- **No rooms/channels**: Point-to-point or broadcast only (rooms add significant complexity)
- **No presence tracking**: No "who's online" features (can be added later if needed)
- **No Redis pub/sub**: Single-server deployment initially (scaling can be added later)
- **No complex message formats**: Simple EDN or JSON payloads

---

## Primary Use Cases

### Use Case 1: Live Notifications
A user receives real-time notifications when events occur (e.g., new order, comment reply).

**Flow:**
1. User authenticates via HTTP (gets JWT token)
2. Client opens WebSocket connection with JWT in query params
3. Server validates JWT and registers connection
4. When event occurs, server sends notification to user's connection
5. Client receives notification and updates UI

**Example:**
```clojure
;; Server-side: Send notification
(realtime/send-to-user realtime-service user-id
  {:type :notification
   :payload {:message "New order #1234 received"}})

;; Client-side: Receive notification
ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  if (data.type === 'notification') {
    showNotification(data.payload.message);
  }
}
```

### Use Case 2: Dashboard Live Updates
Admin dashboard displays live metrics that update without page refresh.

**Flow:**
1. Admin connects via WebSocket with JWT
2. Server periodically broadcasts metrics to all admin connections
3. Dashboard updates in real-time

**Example:**
```clojure
;; Server-side: Broadcast to role
(realtime/broadcast-to-role realtime-service :admin
  {:type :metrics
   :payload {:active-users 142 :orders-today 37}})
```

### Use Case 3: Job Progress Updates
User submits long-running job and receives progress updates.

**Flow:**
1. User triggers job via HTTP POST
2. User connects WebSocket with job-id in query params
3. Job sends progress updates via WebSocket
4. User receives real-time progress

**Example:**
```clojure
;; Server-side: Send job progress
(realtime/send-to-connection realtime-service connection-id
  {:type :job-progress
   :payload {:job-id "job-123" :progress 45 :status "Processing..."}})
```

---

## Considered Options

### Option 1: Server-Sent Events (SSE)
Use HTTP SSE for one-way server-to-client communication.

**Pros:**
- Simpler than WebSocket
- Works over HTTP (no special infrastructure)
- Native browser support

**Cons:**
- One-way only (no client → server messages)
- Connection limits in browsers (6 per domain)
- No binary data support
- Does not fit bidirectional use cases

**Verdict:** ❌ Rejected - Insufficient for bidirectional communication needs

---

### Option 2: WebSocket with Full Channel/Room System
Implement Phoenix Channels-style architecture with rooms, presence, and Redis pub/sub.

**Pros:**
- Feature-complete for complex apps
- Supports multi-server scaling
- Rich presence tracking

**Cons:**
- High complexity (1000+ lines of code)
- Requires Redis dependency
- Most users don't need rooms/presence
- Delays MVP delivery

**Verdict:** ❌ Rejected - Over-engineered for initial use cases

---

### Option 3: Minimal WebSocket with JWT Auth ✅ SELECTED
Simple WebSocket implementation with JWT authentication and point-to-point messaging.

**Pros:**
- Minimal complexity (~300-400 LOC)
- No external dependencies (no Redis)
- Sufficient for 80% of use cases
- Easy to extend later
- Fully testable with FC/IS pattern

**Cons:**
- Limited to single-server initially
- No built-in presence tracking
- No rooms/channels (must route manually)

**Verdict:** ✅ Selected - Right balance of simplicity and functionality

---

## Decision Outcome

**Chosen Option:** Option 3: Minimal WebSocket with JWT Auth

We will implement a `libs/realtime/` module with the following architecture.

---

## Architecture

### Module Structure

```
libs/realtime/
├── src/boundary/realtime/
│   ├── core/
│   │   ├── connection.clj      # Connection state management (pure)
│   │   ├── message.clj         # Message validation/routing (pure)
│   │   └── auth.clj            # JWT validation logic (pure)
│   ├── ports.clj               # Protocol definitions
│   ├── schema.clj              # Malli schemas
│   └── shell/
│       ├── service.clj         # Shell orchestration
│       ├── websocket_adapter.clj  # WebSocket I/O adapter
│       └── connection_registry.clj # In-memory connection store (atom)
├── test/boundary/realtime/
│   ├── core/                   # Unit tests
│   └── shell/                  # Integration tests
└── README.md
```

### Core Components (Functional Core)

#### 1. Connection State (core/connection.clj)
```clojure
(ns boundary.realtime.core.connection)

;; Pure data structures
(defrecord Connection [id user-id roles metadata created-at])

;; Pure functions
(defn create-connection [user-id roles metadata]
  (map->Connection
    {:id (random-uuid)
     :user-id user-id
     :roles roles
     :metadata metadata
     :created-at (java.time.Instant/now)}))

(defn authorize-connection? [connection required-role]
  (contains? (:roles connection) required-role))
```

#### 2. Message Handling (core/message.clj)
```clojure
(ns boundary.realtime.core.message
  (:require [boundary.realtime.schema :as schema]
            [malli.core :as m]))

;; Pure validation
(defn valid-message? [message]
  (m/validate schema/Message message))

;; Pure routing logic
(defn route-message [message connections]
  (case (:type message)
    :broadcast (map :id connections)
    :user      (filter #(= (:user-id %) (:target message)) connections)
    :role      (filter #(contains? (:roles %) (:target message)) connections)
    []))
```

#### 3. Authentication (core/auth.clj)
```clojure
(ns boundary.realtime.core.auth)

;; Pure JWT extraction
(defn extract-token-from-query [query-string]
  (when-let [params (parse-query-string query-string)]
    (get params "token")))

;; Pure validation decision (delegates actual JWT verification to port)
(defn connection-authorized? [jwt-claims required-permissions]
  (every? (set (:permissions jwt-claims)) required-permissions))
```

### Shell Components (Imperative Shell)

#### 4. Service Orchestration (shell/service.clj)
```clojure
(ns boundary.realtime.shell.service
  (:require [boundary.realtime.ports :as ports]
            [boundary.realtime.core.connection :as conn]
            [boundary.realtime.core.message :as msg]))

(defrecord RealtimeService [connection-registry jwt-service logger]
  ports/IRealtimeService
  
  (connect [this ws-connection query-params]
    ;; Shell: I/O and side effects
    (let [token (extract-token query-params)
          claims (ports/verify-jwt jwt-service token)
          connection (conn/create-connection (:user-id claims) (:roles claims) {})
          connection-id (:id connection)]
      (ports/register connection-registry connection-id connection ws-connection)
      (ports/log logger :info "WebSocket connection established" {:connection-id connection-id})
      connection-id))
  
  (send-to-user [this user-id message]
    ;; Shell: Lookup and send
    (let [connections (ports/find-by-user connection-registry user-id)]
      (doseq [conn connections]
        (ports/send-message conn message))
      (ports/log logger :debug "Sent message to user" {:user-id user-id :count (count connections)})))
  
  (broadcast [this message]
    ;; Shell: Broadcast to all
    (let [connections (ports/all-connections connection-registry)]
      (doseq [conn connections]
        (ports/send-message conn message))
      (ports/log logger :debug "Broadcast message" {:count (count connections)}))))
```

#### 5. Connection Registry (shell/connection_registry.clj)
```clojure
(ns boundary.realtime.shell.connection-registry
  (:require [boundary.realtime.ports :as ports]))

(defrecord InMemoryConnectionRegistry [state] ;; state is an atom
  ports/IConnectionRegistry
  
  (register [this connection-id connection ws-connection]
    (swap! state assoc connection-id {:connection connection :ws ws-connection})
    nil)
  
  (unregister [this connection-id]
    (swap! state dissoc connection-id)
    nil)
  
  (find-by-user [this user-id]
    (->> @state
         vals
         (filter #(= (:user-id (:connection %)) user-id))
         (map :ws)))
  
  (all-connections [this]
    (->> @state vals (map :ws))))

(defn create-registry []
  (->InMemoryConnectionRegistry (atom {})))
```

#### 6. WebSocket Adapter (shell/websocket_adapter.clj)
```clojure
(ns boundary.realtime.shell.websocket-adapter
  (:require [boundary.platform.shell.interfaces.web.websockets :as ws]
            [boundary.realtime.ports :as ports]))

;; Adapter wraps Ring/Reitit WebSocket implementation
(defrecord RingWebSocketAdapter [ws-connection]
  ports/IWebSocketConnection
  
  (send-message [this message]
    (ws/send! ws-connection (json/encode message)))
  
  (close [this]
    (ws/close! ws-connection)))
```

### Ports (Protocol Definitions)

```clojure
(ns boundary.realtime.ports)

(defprotocol IRealtimeService
  (connect [this ws-connection query-params]
    "Establish WebSocket connection with JWT auth. Returns connection-id.")
  (disconnect [this connection-id]
    "Close connection and clean up.")
  (send-to-user [this user-id message]
    "Send message to all connections for a user.")
  (send-to-role [this role message]
    "Send message to all connections with a role.")
  (broadcast [this message]
    "Send message to all connections."))

(defprotocol IConnectionRegistry
  (register [this connection-id connection ws-connection]
    "Register a new connection.")
  (unregister [this connection-id]
    "Remove connection from registry.")
  (find-by-user [this user-id]
    "Find all WebSocket connections for a user.")
  (find-by-role [this role]
    "Find all WebSocket connections with a role.")
  (all-connections [this]
    "Get all active WebSocket connections."))

(defprotocol IWebSocketConnection
  (send-message [this message]
    "Send message over WebSocket.")
  (close [this]
    "Close WebSocket connection."))
```

### Schemas

```clojure
(ns boundary.realtime.schema
  (:require [malli.core :as m]))

(def Connection
  [:map
   [:id :uuid]
   [:user-id :uuid]
   [:roles [:set :keyword]]
   [:metadata [:map]]
   [:created-at inst?]])

(def Message
  [:map
   [:type [:enum :broadcast :user :role :connection]]
   [:payload :map]
   [:target {:optional true} [:or :uuid :keyword]]])

(def QueryParams
  [:map
   [:token :string]])
```

---

## Authentication Approach

### JWT in Query Parameters
WebSocket connections authenticate via JWT token in query parameters:

```
ws://localhost:3000/ws?token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**Why query params instead of headers?**
- Browser WebSocket API does not support custom headers
- Standard practice for WebSocket auth
- Simple and widely supported

**Security considerations:**
- Token appears in server logs (use short-lived tokens)
- Token visible in browser network tab (use HTTPS/WSS)
- Validate token on every connection attempt
- Implement token rotation for long-lived connections

### Integration with boundary/user

```clojure
;; User module provides JWT verification
(require '[boundary.user.ports :as user-ports])

(defn connect [this ws-connection query-params]
  (let [token (get query-params "token")
        ;; Delegate JWT verification to user module
        jwt-claims (user-ports/verify-jwt user-service token)
        connection (conn/create-connection 
                     (:user-id jwt-claims) 
                     (:roles jwt-claims) 
                     {})]
    (ports/register connection-registry (:id connection) connection ws-connection)
    (:id connection)))
```

---

## Message Format

### JSON (Initial Implementation)
Messages use JSON for broad client compatibility:

```json
{
  "type": "notification",
  "payload": {
    "message": "New order received",
    "order_id": "123",
    "timestamp": "2026-02-04T20:00:00Z"
  }
}
```

**Rationale:**
- Universal browser support
- Easy debugging (human-readable)
- Standard tooling (JSON.parse/stringify)

### EDN (Future Consideration)
For Clojure/ClojureScript clients, EDN support may be added later:

```clojure
{:type :notification
 :payload {:message "New order received"
           :order-id "123"
           :timestamp #inst "2026-02-04T20:00:00Z"}}
```

**Deferred because:**
- Requires transit or custom encoding
- Not all clients are Clojure-based
- JSON sufficient for MVP

---

## Message Routing

### Point-to-Point (User)
Send message to specific user (all their connections):

```clojure
(realtime/send-to-user realtime-service user-id
  {:type :notification
   :payload {:message "Hello user"}})
```

### Broadcast (All Connections)
Send message to all connected clients:

```clojure
(realtime/broadcast realtime-service
  {:type :system-announcement
   :payload {:message "Maintenance in 5 minutes"}})
```

### Role-Based
Send message to all users with a specific role:

```clojure
(realtime/send-to-role realtime-service :admin
  {:type :admin-alert
   :payload {:message "High CPU usage detected"}})
```

### Connection-Specific
Send message to a specific connection (for job progress tracking):

```clojure
(realtime/send-to-connection realtime-service connection-id
  {:type :job-progress
   :payload {:progress 75}})
```

---

## Explicit Non-Goals (Phase 5)

### No Rooms/Channels
**Not implementing:**
- `join-room`, `leave-room` APIs
- Room-based message routing
- Per-room connection lists

**Rationale:**
- Adds significant complexity (~300 extra LOC)
- Most use cases satisfied by user/role routing
- Can be added in Phase 6+ if demand exists

**Workaround for room-like behavior:**
- Use role-based routing: `send-to-role :chatroom-123 message`
- Use metadata in connection: `{:metadata {:room "chatroom-123"}}`

### No Presence Tracking
**Not implementing:**
- "Who's online" queries
- Online/offline events
- Last-seen timestamps

**Rationale:**
- Requires heartbeat/ping mechanisms
- State management complexity
- Not in primary use cases

**Workaround:**
- Query active connections: `(count (ports/all-connections registry))`
- Store last-activity in user module (HTTP-based)

### No Redis Pub/Sub
**Not implementing:**
- Multi-server WebSocket coordination
- Redis-backed connection registry
- Horizontal scaling

**Rationale:**
- Adds external dependency
- Most deployments are single-server initially
- Can be added later via adapter swap

**Workaround for multi-server:**
- Use load balancer with sticky sessions
- Defer to Phase 6+ if scaling required

---

## HTTP Integration

### WebSocket Endpoint
Add to Reitit routes:

```clojure
["/ws" {:get {:handler (websocket-handler realtime-service)}}]
```

### Handler Implementation
```clojure
(defn websocket-handler [realtime-service]
  (fn [request]
    (if (ws/upgrade-request? request)
      {:status 101
       :headers {"Upgrade" "websocket"
                 "Connection" "Upgrade"}
       :ws {:on-connect (fn [ws]
                          (let [params (:query-params request)
                                conn-id (ports/connect realtime-service ws params)]
                            (println "Connected:" conn-id)))
            :on-text (fn [ws text]
                       (println "Received:" text))
            :on-close (fn [ws status-code reason]
                        (println "Disconnected"))}}
      {:status 400
       :body {:error "WebSocket upgrade required"}})))
```

---

## Testing Strategy

### Unit Tests (Core)
Pure functions tested in isolation:

```clojure
(deftest create-connection-test
  (let [conn (conn/create-connection user-id #{:admin} {})]
    (is (uuid? (:id conn)))
    (is (= user-id (:user-id conn)))
    (is (contains? (:roles conn) :admin))))

(deftest route-message-test
  (let [connections [(map->Connection {:user-id user-1 :roles #{:admin}})
                     (map->Connection {:user-id user-2 :roles #{:user}})]
        message {:type :role :target :admin :payload {}}
        targets (msg/route-message message connections)]
    (is (= 1 (count targets)))))
```

### Integration Tests (Shell)
Test with mock WebSocket connections:

```clojure
(deftest send-to-user-test
  (let [registry (create-registry)
        ws-mock (reify ports/IWebSocketConnection
                  (send-message [this msg] (swap! sent-messages conj msg)))
        service (->RealtimeService registry jwt-service logger)]
    (ports/register registry conn-id connection ws-mock)
    (ports/send-to-user service user-id {:type :test :payload {}})
    (is (= 1 (count @sent-messages)))))
```

### Manual Testing
Browser-based testing:

```javascript
const ws = new WebSocket('ws://localhost:3000/ws?token=' + jwt);
ws.onopen = () => console.log('Connected');
ws.onmessage = (event) => console.log('Received:', JSON.parse(event.data));
ws.send(JSON.stringify({type: 'ping', payload: {}}));
```

---

## Observability

### Logging
All WebSocket events logged via boundary/observability:

```clojure
(log :info "WebSocket connection established" {:connection-id conn-id :user-id user-id})
(log :debug "Message sent" {:type :notification :user-id user-id})
(log :warn "Connection closed unexpectedly" {:connection-id conn-id :reason reason})
```

### Metrics
Track connection and message statistics:

```clojure
(metrics/gauge "realtime.connections.active" (count (ports/all-connections registry)))
(metrics/increment "realtime.messages.sent" {:type :notification})
(metrics/increment "realtime.connections.opened")
(metrics/increment "realtime.connections.closed" {:reason :client-disconnect})
```

---

## Migration Path

### Phase 5 (Current)
- Minimal WebSocket implementation
- JWT authentication
- Point-to-point and broadcast messaging
- Single-server deployment

### Phase 6+ (Future Enhancements)
- **Rooms/Channels**: If demand exists, add room abstraction
- **Presence Tracking**: Heartbeat and online/offline events
- **Redis Pub/Sub**: Multi-server scaling via Redis adapter
- **EDN Support**: Binary message format for Clojure clients
- **Compression**: Message compression for large payloads
- **Reconnection Logic**: Automatic reconnect with backoff

---

## Consequences

### Positive Consequences
- **Simplicity**: Minimal code (~400 LOC total) with clear architecture
- **FC/IS Compliance**: Pure core, side effects in shell
- **No External Deps**: Works out-of-the-box with existing infrastructure
- **Extensible**: Protocol-based design allows future adapter swaps
- **Testable**: Pure functions easy to unit test, shell mocked in integration tests

### Negative Consequences
- **Single-Server Only**: No built-in multi-server support (acceptable for MVP)
- **No Presence**: Cannot query "who's online" (workaround: poll HTTP endpoint)
- **No Rooms**: Manual routing required for group messages (acceptable for initial use cases)
- **JSON Only**: No EDN support initially (can add later)

---

## References

- **Phoenix Channels**: https://hexdocs.pm/phoenix/channels.html (inspiration for architecture)
- **WebSocket RFC 6455**: https://datatracker.ietf.org/doc/html/rfc6455
- **JWT Best Practices**: https://tools.ietf.org/html/rfc8725
- **Boundary FC/IS Pattern**: `docs/ARCHITECTURE.md`

---

## Approval Checklist

- [ ] Primary use cases documented (notifications, dashboards, job progress)
- [ ] Authentication approach documented (JWT in query params)
- [ ] Message format documented (JSON initially)
- [ ] Scope boundaries documented (no rooms, no presence, no Redis)
- [ ] FC/IS architecture defined (core vs shell separation)
- [ ] Protocol definitions (ports) specified
- [ ] Testing strategy defined
- [ ] Observability integration planned

---

**Next Steps After Approval:**
1. Create `libs/realtime/` directory structure (Task 5.1)
2. Implement core layer (connection, message, auth)
3. Implement shell layer (service, registry, adapter)
4. Write tests (unit + integration)
5. Write README matching `libs/jobs/README.md` quality
6. Integration with boundary/user for JWT verification
