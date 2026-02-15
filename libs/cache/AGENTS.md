# Cache Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Distributed caching with TTL, atomic operations, pattern matching, and multi-tenant isolation. Provides in-memory (dev/test) and Redis (production) backends behind a unified protocol.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.cache.ports` | Protocols: ICache, IBatchCache, IAtomicCache, IPatternCache, INamespacedCache, ICacheStats |
| `boundary.cache.schema` | Malli schemas for CacheKey, CacheValue, TTL, CacheConfig, RedisConfig |
| `boundary.cache.shell.adapters.in-memory` | In-memory adapter (atoms, LRU eviction, TTL) |
| `boundary.cache.shell.adapters.redis` | Redis adapter (Jedis, connection pooling, JSON serialization) |
| `boundary.cache.shell.tenant-cache` | Tenant-scoped wrapper with automatic key prefixing |

## Usage Patterns

```clojure
;; In-memory (dev/test)
(require '[boundary.cache.shell.adapters.in-memory :as in-mem])
(def cache (in-mem/create-in-memory-cache {:max-size 1000 :track-stats? true}))

;; Basic operations via ports
(require '[boundary.cache.ports :as ports])
(ports/set-value! cache :user-123 {:name "Alice"} 3600)  ; TTL in seconds
(ports/get-value cache :user-123)

;; Atomic operations
(ports/increment! cache :counter)
(ports/set-if-absent! cache :lock "holder-id" 30)  ; SETNX pattern

;; Tenant-scoped cache
(require '[boundary.cache.shell.tenant-cache :as tenant-cache])
(def tenant-a-cache (tenant-cache/create-tenant-cache cache "tenant-a"))
;; Keys automatically prefixed: "tenant:tenant-a:user-123"
```

## Important Conventions

- **Keys**: Can be strings or keywords, internally converted to strings. Use consistent format
- **TTL**: In seconds. `nil` from `ttl()` means no expiration set
- **Serialization**: In-memory stores Clojure values as-is; Redis uses JSON (no Java objects or functions)
- **Tenant isolation**: `flush-all!` on tenant cache only deletes that tenant's keys (safe)

## Gotchas

1. **Pattern matching performance**: In-memory uses O(n) regex scan; Redis uses SCAN (cursor-based). Expensive on large caches
2. **Statistics are global**: Tenant cache returns stats from base cache, not tenant-specific
3. **Expired keys**: Lazily deleted on access (in-memory) or auto-expired (Redis)
4. **LRU eviction**: In-memory only. Tracks `last-accessed-at` per entry

## Testing

```bash
clojure -M:test:db/h2 :cache
```
