# Realtime Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

WebSocket-based real-time communication with JWT authentication, message routing, and topic-based pub/sub. Supports point-to-point, role-based, broadcast, and connection-specific messaging.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.realtime.core.connection` | Pure: connection records, authorization, filtering |
| `boundary.realtime.core.message` | Pure: message creation, routing logic (4 types) |
| `boundary.realtime.core.auth` | Pure: JWT extraction, claims validation, permission checks |
| `boundary.realtime.core.pubsub` | Pure: topic subscription management |
| `boundary.realtime.ports` | Protocols: IRealtimeService, IConnectionRegistry, IWebSocketConnection, IJWTVerifier, IPubSubManager |
| `boundary.realtime.schema` | Malli schemas: Connection, Message, JWT Claims, Topic |
| `boundary.realtime.shell.service` | Service orchestrating core + adapters |
| `boundary.realtime.shell.connection-registry` | In-memory registry (atom-backed) |
| `boundary.realtime.shell.pubsub-manager` | Atom-backed pub/sub state management |
| `boundary.realtime.shell.adapters.websocket-adapter` | Ring/Jetty WebSocket adapter + TestWebSocketAdapter |
| `boundary.realtime.shell.adapters.jwt-adapter` | JWT verifier delegating to boundary/user module + TestJWTAdapter |

## Message Routing Types

| Type | Target | Example |
|------|--------|---------|
| `:broadcast` | All connections | System announcement |
| `:user` | Specific user-id | Direct message |
| `:role` | Users with specific role | Admin notification |
| `:connection` | Specific connection-id | Job progress update |

## Connection Lifecycle

1. Client connects with JWT: `ws://host/ws?token=<jwt>`
2. Server verifies JWT via `IJWTVerifier` adapter
3. Connection record created, registered in connection registry
4. Client can send/receive messages
5. On disconnect: cleanup registry + unsubscribe from all pub/sub topics

## Topic-Based Pub/Sub

```clojure
;; Subscribe connection to topic
(ports/subscribe-to-topic pubsub-mgr conn-id "order:123")

;; Publish to all subscribers of a topic
(ports/publish-to-topic service "order:123" {:type :order-updated :payload {...}})

;; Unsubscribe (automatic on disconnect)
(ports/unsubscribe-from-topic pubsub-mgr conn-id "order:123")
```

## Gotchas

1. **Single-server only** - current in-memory registry doesn't work across servers. Use sticky sessions for load balancing. Redis-backed registry planned for v0.2.0
2. **JWT adapter uses optional dependency** on boundary/user - `requiring-resolve` at load time. Throws `:type :internal-error` if user module unavailable
3. **Messages get JSON-encoded** via Cheshire before sending over WebSocket
4. **Topic subscriptions are server-side only** - no client-side subscribe/unsubscribe protocol messages
5. **Shell checks `ports/open?`** before sending to prevent errors on closed connections

## Test Adapters

```clojure
;; TestWebSocketAdapter - collects sent messages in atom
(def ws (websocket-adapter/create-test-websocket-adapter conn-id))
;; @(:sent-messages ws) => [{:type :welcome ...} ...]

;; TestJWTAdapter - returns mock claims
(def jwt (jwt-adapter/create-test-jwt-adapter
           (atom {:user-id user-id :expected-token "test-token" ...})))
```

## Testing

```bash
clojure -M:test:db/h2 :realtime
```
