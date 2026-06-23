# BOU-85 — Realtime: Redis-backed pub/sub + connection registry adapter

**Status:** Approved (design)
**Ticket:** [BOU-85](https://linear.app/boundary-app/issue/BOU-85) — High, from the BOU-84 scaling spike
**ADR:** `dev-docs/adr/ADR-035-realtime-redis-scaling.adoc` (implements ADR-003's deferred v0.2.0 Redis pub/sub)
**Date:** 2026-06-19
**Module:** `libs/realtime`

---

## Problem

`libs/realtime` ships only an in-memory `InMemoryConnectionRegistry` (atom) and an
atom-based `AtomPubSubManager`. A WebSocket broadcast reaches only clients
connected to the *same* JVM instance. Running WebSocket across multiple replicas
is therefore impossible — the one hard horizontal-scaling blocker identified in
BOU-84.

Goal: messages published on one instance must reach WebSocket clients on **any**
instance, selectable via config (`:provider :in-memory | :redis`), following the
same pattern as `libs/cache` and `libs/jobs`.

## Key architectural constraint

A `ws-adapter` (`IWebSocketConnection`) wraps a **live socket handle** that
physically lives on the node that accepted the connection. It cannot be
serialized into Redis. Therefore a literal "Redis registry that stores
ws-adapters" is impossible. Cross-node delivery requires a **message bus / relay**:

1. The originating node **publishes a routing envelope** (not the socket) onto a
   shared channel.
2. **Every node** (including the origin) receives the envelope via its subscriber
   and delivers the message to its **own local sockets** by resolving the
   envelope against its local registry.

Because each WebSocket connection lives on exactly one node, each socket is
delivered to exactly once — **no dedup logic required**, provided delivery happens
*exclusively* through the registered delivery-fn (never inline on the origin).
Redis pub/sub delivers a published message to **all** subscribers *including the
publisher*, so the origin node delivers its own sockets via its own subscriber
thread — there is no separate inline path to double-count.

**Single delivery seam (both providers).** `start-subscriber!` registers one
node-local `delivery-fn`. That exact function is what performs delivery in both
providers: the in-memory bus calls it synchronously inside `publish` (so a
recipient count is available); the Redis bus calls it from its subscriber thread
when the envelope arrives off the channel (so `publish` returns `nil`). The
in-memory and Redis paths therefore share the same delivery logic and the same
seam — the 2-node in-memory test exercises the real relay callback, not a
parallel approximation.

This is the Socket.io Redis-adapter / Phoenix.PubSub model.

## Scope decisions (locked during brainstorming)

| Decision | Choice |
|---|---|
| Redis state scope | **Bus + global topic subscriptions.** Local registry keeps live sockets; Redis pub/sub relays routing; topic subscriptions stored in Redis so `get-topic-subscribers` is global. No full registry mirror. `connection-count` stays node-local. |
| Bus integration | **New `IMessageBus` port; service publishes.** Service send-path builds an envelope and calls `(publish bus envelope)`. Delivery moves out of the service into a node-local delivery-fn invoked by the bus subscriber. |
| Delivery model | **Exclusively via the registered delivery-fn** — the *same* callback for both providers. In-memory `publish` invokes it synchronously; Redis `publish` hands the envelope to the channel and the subscriber thread invokes it. No inline delivery on origin → exactly-once without dedup. |
| Wire format | **Nippy** (`taoensso.nippy` 3.6.2) — same as cache adapter; round-trips UUIDs, keywords, sets, `java.time/Instant` with full fidelity. |
| `send-*` return value | **`nil` under `:redis`** (async fan-out), **count under `:in-memory`** (synchronous). `send-to-connection` keeps a boolean shape: `(pos? count)` under in-memory, `nil` under redis. Documented difference; existing single-node tests unaffected (see "Single-node default & service tests"). |

## Architecture

```
                         ┌──────────── Redis PUB/SUB channel "realtime:bus" ───────────┐
                         │                                                              │
   Node A                ▼                          Node B                              ▼
 ┌──────────────────────────────┐         ┌──────────────────────────────┐
 │ RealtimeService.broadcast     │         │ RedisMessageBus subscriber    │
 │   → build envelope            │         │   thread (BinaryJedisPubSub)  │
 │   → (publish bus envelope) ───┼─PUBLISH─┤   → thaw → delivery-fn        │
 │                               │         │   → local registry lookup     │
 │ RedisMessageBus subscriber    │◀────────┤   → ws-adapter send-message   │
 │   thread → delivery-fn        │         │                               │
 │   → local registry → send     │         │ InMemoryConnectionRegistry    │
 │ InMemoryConnectionRegistry    │         │ RedisPubSubManager            │
 │ RedisPubSubManager            │         └──────────────────────────────┘
 └──────────────────────────────┘
```

Single-node default (`:in-memory`): the bus is an `InMemoryMessageBus` whose
`publish` fans synchronously to the one registered delivery-fn — behaviour
identical to today, plus a real recipient count.

## New port

Added to `libs/realtime/src/boundary/realtime/ports.clj`:

```clojure
(defprotocol IMessageBus
  "Cross-instance routing transport for realtime messages.
   Publishes routing envelopes to all nodes; each node delivers to its local
   sockets via a registered delivery-fn. In-memory = synchronous loopback;
   Redis = async PUB/SUB fan-out."
  (publish [this envelope]
    "Publish a routing envelope. Returns local recipient count (in-memory,
     synchronous) or nil (redis, async).")
  (start-subscriber! [this delivery-fn]
    "Register the node-local delivery-fn and begin receiving envelopes.
     delivery-fn is (fn [envelope] -> int) performing local delivery.")
  (stop-subscriber! [this]
    "Stop receiving and release resources. Idempotent."))
```

### Envelope shape (pure data)

```clojure
{:route   :user | :role | :broadcast | :connection | :topic
 :target  <user-uuid | role-kw | conn-uuid | topic-str | nil>   ; nil for :broadcast
 :message {:type ... :payload ... :timestamp <Instant>}}
```

`:route` is deliberately named distinctly from the message's own `:type`
(`MessageType` enum in `schema.clj` is `:broadcast :user :role :connection` and
does **not** include `:topic`) — the envelope route and the message type are
independent concerns.

### Registry port addition

`IConnectionRegistry` currently exposes `find-connection` (returns the Connection
*record*, not the ws-adapter); today `service.clj` reaches into the raw
`@(:state registry)` atom to get an adapter by id. The delivery-fn must resolve
**local ws-adapters by connection-id** without poking atom internals and without
recursing through `send-to-connection` (which now publishes to the bus). Add:

```clojure
(find-adapters-by-ids [this connection-ids]
  "Return a vector of IWebSocketConnection adapters for the given ids that are
   present in THIS node's registry (missing ids skipped).")
```

Implemented by `InMemoryConnectionRegistry`. Used by the delivery-fn for the
`:connection` and `:topic` routes; `service.clj`'s old atom-poke in
`send-to-connection` is removed.

## Components

| Unit | File | Responsibility |
|---|---|---|
| `IMessageBus` port | `src/.../ports.clj` (append) | Bus contract (above). |
| Envelope helpers | `src/.../core/bus.clj` (new, pure) | `user-envelope`, `role-envelope`, `broadcast-envelope`, `connection-envelope`, `topic-envelope` constructors; keeps envelope-shaping pure and testable. |
| `InMemoryMessageBus` | `src/.../shell/bus/in_memory.clj` (new) | Holds a `subscribers` atom `{node-id delivery-fn}`. `publish` invokes every registered delivery-fn **synchronously** (the same callbacks registered via `start-subscriber!`), summing returned counts. `start-subscriber!` registers a node's delivery-fn; `stop-subscriber!` deregisters. Default factory = fresh atom (single node). A shared-atom factory lets a test register two nodes' delivery-fns on one bus → `publish` fans to both, faithfully modelling the relay. |
| `RedisMessageBus` | `src/.../shell/bus/redis.clj` (new) | Two connection concerns: a `JedisPool` for `publish` (borrow/return) and **one dedicated raw `Jedis`** for the blocking subscribe. `publish`: Nippy-freeze envelope → binary `PUBLISH channel-bytes`; best-effort (logged, not thrown); returns `nil`. `start-subscriber!`: spawn a daemon thread running `(.subscribe dedicated-jedis pubsub channel-bytes)` with a `BinaryJedisPubSub` (`onMessage [^bytes channel ^bytes message]`) that thaws `message` → calls delivery-fn inside try/catch+`log/error` (never throws into the Jedis callback thread). Block the caller on a `CountDownLatch` released in `onSubscribe` so the subscription is live before `start-subscriber!` returns. **Singleton subscriber**: `start-subscriber!` is idempotent — a `running?` flag/atom makes a second call a no-op, so a node never holds two channel subscriptions (which would double-deliver). **Reconnect loop**: the daemon wraps `subscribe` in a loop with backoff; on connection loss it fully tears down the old `BinaryJedisPubSub` + connection before re-subscribing (never stacks two live subscriptions); messages during the gap are missed (at-most-once, accepted). `stop-subscriber!`: set stopped, call `(.unsubscribe pubsub)` **first**, then close the dedicated connection + pool, then `join` the daemon thread with a timeout; idempotent, close errors swallowed/logged. |
| `RedisPubSubManager` | `src/.../shell/adapters/redis_pubsub.clj` (new) | Implements `IPubSubManager` over Redis sets: `topic:{t}` → set of conn-id strings, `conn:{id}` → set of topic strings (reverse index for `unsubscribe-from-all-topics` and `get-connection-subscriptions`). `subscribe` = the two SADDs in a single `MULTI/EXEC` (atomic, no torn two-set state); `unsubscribe` / `unsubscribe-from-all-topics` = the SREMs in a `MULTI/EXEC` with **no** explicit DEL — Redis auto-deletes a set key when its last member is removed, so an empty topic simply disappears and the "check-then-act DEL" race cannot occur. `get-topic-subscribers` = SMEMBERS (missing key → empty set) → parse to UUIDs. Validates topic via existing `schema/valid-topic?`. Conn-ids stored as `(str uuid)`, parsed back via `UUID/fromString`. |
| node-local delivery-fn | built by `create-realtime-service` / `module_wiring.clj` | Closure over the local registry + pubsub-manager. Given an envelope, resolves `:route` to local ws-adapters and calls `send-message` on the open ones; returns local count. `:user`/`:role` → `find-by-user`/`find-by-role`. `:broadcast` → `all-connections`. `:connection` → `find-adapters-by-ids [target]`. `:topic` → `get-topic-subscribers` (global, from pubsub-mgr) → `find-adapters-by-ids` (local intersection). Never calls service send methods (no re-publish recursion). |
| integrant wiring | `src/.../shell/module_wiring.clj` (new) | `defmethod ig/init-key :boundary/realtime` selecting provider (`case` on `:provider`, mirroring `boundary.cache.shell.module-wiring`). Builds local registry, pubsub-manager (atom or Redis), bus (in-memory or Redis), and service; constructs the delivery-fn closure and calls `start-subscriber!` (which blocks until the subscription is live). **The web/WS server component must depend on `:boundary/realtime`** (Integrant ref) so the subscriber is guaranteed live before any WebSocket connection is accepted — no startup window where broadcasts are silently dropped. `ig/halt-key!` calls `stop-subscriber!` and closes Redis pools. |

## Service change

`src/.../shell/service.clj` — `send-to-user`, `send-to-role`, `broadcast`,
`send-to-connection`, `publish-to-topic` change from inline
`find-* → doseq → send-message` to:

```clojure
(ports/publish bus (bus-core/user-envelope user-id (stamp message)))
```

The removed inline delivery logic relocates into the delivery-fn (shell).
`connect` and `disconnect` are unchanged except that disconnect's topic-cleanup
now runs against whichever `IPubSubManager` is wired (atom or Redis). The
`RealtimeService` record gains a `bus` field.

`send-to-connection` keeps a boolean-ish contract: it returns `(some-> (publish
bus env) pos?)` — `true`/`false` under in-memory (count 1/0), `nil` under redis
(can't know remotely). The existing test runs in-memory and still sees `true`/
`false`.

Timestamp stamping (`current-timestamp` when `:timestamp` absent) stays in the
service so the envelope carries a stamped message before it crosses the wire.

### Single-node default & service tests

`create-realtime-service` must keep working when called **without** a bus (as the
existing `service_test.clj` does, asserting synchronous counts). Resolution: when
no `:bus` is supplied, `create-realtime-service` constructs a default
`InMemoryMessageBus`, builds the node-local delivery-fn from its own
`connection-registry` + `pubsub-manager` fields, and calls `(start-subscriber!
bus delivery-fn)` during construction. Because the in-memory bus delivers
synchronously inside `publish`, every existing count assertion
(`send-to-user`=2, `broadcast`=3, `publish-to-topic`=2/1/0, `send-to-connection`
true/false, timestamp stamping) holds unchanged.

To avoid a core→shell or service→wiring violation, the service ns requires only
`shell.bus.in-memory` (shell→shell is allowed); it never references the Redis bus
or `module_wiring`. `module_wiring` injects the Redis bus via the `:bus` option
instead.

## Dependencies

`libs/realtime/deps.edn` — add (pinned to the versions used by `libs/cache` /
`libs/jobs`):

- `redis.clients/jedis {:mvn/version "7.5.2"}`
- `com.taoensso/nippy {:mvn/version "3.6.2"}`

## Data flow examples

**`broadcast` on a 2-node cluster:**
1. Node A: `(broadcast svc msg)` → `(publish redis-bus {:route :broadcast :message msg})`.
2. Redis fans the Nippy bytes to both nodes' subscriber threads (A included).
3. Each node thaws → delivery-fn → `(all-connections local-registry)` → `send-message` to each open local adapter.
4. Every client on A and B receives the message exactly once.

**`publish-to-topic "order:123"`:**
1. Originating node publishes `{:route :topic :target "order:123" :message msg}`.
2. Each node: delivery-fn → `(get-topic-subscribers pubsub-mgr "order:123")` (global, from Redis) → `(find-adapters-by-ids local-registry subscriber-ids)` → send to the local subset.

## Error handling

- Redis subscriber `onMessage` wraps delivery in try/catch + `log/error`; a bad
  envelope never kills the subscriber thread.
- `publish` on Redis is best-effort; failure is logged, not thrown (matches
  fire-and-forget send semantics).
- `RedisPubSubManager` ops wrap Jedis calls; topic validation throws
  `:validation-error` (consistent with the atom manager).
- `stop-subscriber!` and pool close are idempotent and swallow/log close errors.

## Concurrency & multi-replica races

When one deployment runs N replicas they share exactly two things: the Redis
topic-subscription **sets** and the pub/sub **channel**. Local registries are
node-local atoms — no cross-node race there. The shared state is made
race-safe as follows:

| # | Race | Resolution |
|---|---|---|
| 1 | **Torn two-set write** — `subscribe` does two SADDs, `unsubscribe` two SREMs; interleaving leaves `topic:{t}` and `conn:{id}` disagreeing. | Apply each pair atomically in a single `MULTI/EXEC` (pure writes, no WATCH). |
| 2 | **Check-then-act DEL** — "SREM last member, see empty, DEL" loses a concurrent SADD. | **No explicit DEL.** Redis auto-removes a set key when its last member is SREM'd; `SMEMBERS` of a missing key returns empty. Race eliminated by construction. |
| 3 | **Per-(conn,topic) contention** | None in practice: a connection lives on one node, so its subscribe/unsubscribe originate there and are serialized. subscribe-vs-unsubscribe of the same (c,t) = last-writer-wins = intended. Safe given #1. |
| 4 | **Double-subscribe → double-delivery** — exactly-once holds only if a node has exactly one channel subscription. | Subscriber is a **singleton per bus component** (Integrant inits once); `start-subscriber!` is **idempotent** (`running?` guard); reconnect tears down before re-subscribing — never stacks. |
| 5 | **Subscriber drop / reconnect gap** — blocked `subscribe` thread dies on connection loss → node goes deaf. | Daemon **reconnect loop** with backoff; fully tears down old `BinaryJedisPubSub` + connection before re-subscribing. Gap messages missed (at-most-once, accepted). |
| 6 | **Startup ordering** — node accepting WS before its subscriber is live drops broadcasts. | `onSubscribe` latch blocks `start-subscriber!` until live; the web/WS component **depends on `:boundary/realtime`** so Integrant orders the subscriber before traffic. |

With #1, #2, #4, #5, #6 pinned there are **no correctness races** — delivery is
exactly-once. Each connection lives on one node; that node receives a channel
message once and delivers once; remote nodes find nothing for non-local ids.

**Inherent distributed limits (explicit non-goals, see Out of scope):**
- **No global message ordering** — messages to the same target from *different*
  nodes may arrive in either order (Redis preserves order only per-publisher per
  channel). A sequencer is out of scope.
- **At-most-once** — no replay; a node deaf during reconnect misses messages.
  Acceptable for realtime push.
- **Orphan subscriptions on node crash** — a crashed node leaves dead
  `conn:{id}`/`topic` entries (no TTL). **Harmless to correctness**:
  `find-adapters-by-ids` returns nothing for dead ids on every node, so no wrong
  or double delivery — only a leak + wasted lookup. Presence/TTL sweep is out of
  scope.

## Testing

| Test | Location | Notes |
|---|---|---|
| Envelope constructors (pure) | `test/.../core/bus_test.clj` | Unit, no I/O. |
| In-memory bus fan-out + count | `test/.../shell/bus/in_memory_test.clj` | Unit. |
| **2-node cross-instance relay** | `test/.../shell/cross_instance_test.clj` | Two `RealtimeService` instances + two registries share **one in-memory bus**; assert a broadcast/publish on service A reaches service B's `TestWebSocketAdapter`. Deterministic, runs in CI, **no Redis**. Satisfies acceptance "verified with 2 nodes". |
| Redis bus integration | `test/.../shell/bus/redis_test.clj` | skip-if-unavailable fixture copied from `cache/.../redis_test.clj` (localhost:6379, db 15). Publish on one bus, assert delivery-fn invoked on a second bus subscribed to the same channel. |
| Redis pubsub manager integration | `test/.../shell/adapters/redis_pubsub_test.clj` | skip-if-unavailable; subscribe/unsubscribe/get-subscribers/unsubscribe-all round-trips; **last-member SREM auto-removes the key** (#2 — assert `EXISTS topic:{t}` is false after the final unsubscribe, no manual DEL); concurrent subscribe/unsubscribe leaves the two sets consistent (#1). |
| Idempotent subscriber (#4) | `test/.../shell/bus/redis_test.clj` | calling `start-subscriber!` twice yields a single subscription → a published message is delivered exactly once (skip-if-unavailable). |
| Existing service tests | unchanged | Use in-memory provider; counts still returned → keep passing. |

Tag Redis-touching tests `^:integration` + `^:redis`, matching cache.

## Documentation

- `ports.clj` `IRealtimeService` docstrings (`send-to-user`/`send-to-role`/
  `broadcast`/`send-to-connection`/`publish-to-topic`) currently promise
  "integer >= 0" / boolean — update each to note the `nil` return under `:redis`
  so the contract stays truthful.
- `libs/realtime/AGENTS.md` — add provider-selection section + the `nil`-count
  caveat under `:redis`.
- `docs/modules/architecture/pages/scaling.adoc` — flip realtime from "not yet
  replica-safe" to "replica-safe via `:provider :redis`"; keep the
  sticky-session/single-node note as the `:in-memory` fallback.

## FC/IS compliance

- `core/bus.clj` — pure envelope constructors, no I/O.
- All Redis/Jedis/Nippy/threading lives in `shell/`.
- New port in `ports.clj`; service depends on the `IMessageBus` port, not the
  concrete bus. `bb check:fcis` and `bb check:ports` must pass.

## Out of scope (YAGNI)

- Full Redis registry mirror / globally-accurate `connection-count`.
- Presence/TTL crash cleanup of stale connection metadata.
- Redis Streams / guaranteed delivery (pub/sub is at-most-once, acceptable for
  realtime push).
- Backpressure / per-node rate limiting.
```
