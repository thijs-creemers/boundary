# Distributed Caching Module

Production-grade distributed caching for the Boundary Framework, providing high-performance key-value storage with TTL, atomic operations, and pattern matching.

## Features

- **Multiple Backends**: Redis (production) and In-Memory (development/testing)
- **TTL Support**: Automatic expiration with configurable time-to-live
- **Batch Operations**: Efficient multi-key get/set/delete operations
- **Atomic Operations**: Thread-safe increment, decrement, and compare-and-set
- **Pattern Matching**: Find and delete keys by wildcard patterns
- **Namespace Support**: Logical partitioning with namespaced cache views
- **Statistics**: Hit/miss tracking and performance monitoring
- **LRU Eviction**: Automatic eviction when cache reaches max size (in-memory)
- **Protocol-Based**: Easy to swap implementations without changing code

## Quick Start

### In-Memory Cache (Development)

```clojure
(ns my-app.cache-example
  (:require [boundary.cache.ports :as cache]
            [boundary.cache.shell.adapters.in-memory :as mem-cache]))

;; Create cache
(def cache (mem-cache/create-in-memory-cache))

;; Set values
(cache/set-value! cache :user:123 {:name "Alice" :age 30})
(cache/set-value! cache :session:abc "token-xyz" 3600)  ; TTL: 1 hour

;; Get values
(cache/get-value cache :user:123)
;; => {:name "Alice", :age 30}

;; Check existence
(cache/exists? cache :user:123)
;; => true

;; Delete
(cache/delete-key! cache :user:123)
;; => true
```

### Redis Cache (Production)

```clojure
(ns my-app.cache-example
  (:require [boundary.cache.ports :as cache]
            [boundary.cache.shell.adapters.redis :as redis-cache]))

;; Create Redis connection pool
(def redis-pool (redis-cache/create-redis-pool
                 {:host "localhost"
                  :port 6379
                  :max-total 20}))

;; Create cache
(def cache (redis-cache/create-redis-cache redis-pool))

;; Use same API as in-memory
(cache/set-value! cache :user:123 {:name "Bob"})
(cache/get-value cache :user:123)
;; => {:name "Bob"}

;; Cleanup
(cache/close! cache)
```

## Core Operations

### Get/Set/Delete

```clojure
;; Set value
(cache/set-value! cache :key "value")

;; Set with TTL (seconds)
(cache/set-value! cache :key "value" 300)  ; Expires in 5 minutes

;; Get value
(cache/get-value cache :key)
;; => "value"

;; Check if key exists
(cache/exists? cache :key)
;; => true

;; Delete key
(cache/delete-key! cache :key)
;; => true
```

### TTL Management

```clojure
;; Set value with 1 hour TTL
(cache/set-value! cache :session:abc "token" 3600)

;; Check remaining TTL
(cache/ttl cache :session:abc)
;; => 3599 (seconds)

;; Update TTL on existing key
(cache/expire! cache :session:abc 7200)  ; Extend to 2 hours
```

### Batch Operations

```clojure
;; Set multiple values at once
(cache/set-many! cache
                 {:user:1 "Alice"
                  :user:2 "Bob"
                  :user:3 "Charlie"})

;; Set multiple with TTL
(cache/set-many! cache
                 {:session:a "token-a"
                  :session:b "token-b"}
                 3600)  ; All expire in 1 hour

;; Get multiple values
(cache/get-many cache [:user:1 :user:2 :user:3])
;; => {:user:1 "Alice", :user:2 "Bob", :user:3 "Charlie"}

;; Delete multiple keys
(cache/delete-many! cache [:user:1 :user:2])
;; => 2 (number of keys deleted)
```

## Atomic Operations

### Increment/Decrement

```clojure
;; Initialize counter
(cache/increment! cache :page:views)
;; => 1

(cache/increment! cache :page:views)
;; => 2

(cache/increment! cache :page:views 10)
;; => 12

;; Decrement
(cache/decrement! cache :page:views)
;; => 11

(cache/decrement! cache :page:views 5)
;; => 6
```

### Set If Absent (SETNX)

```clojure
;; Distributed locking pattern
(if (cache/set-if-absent! cache :lock:resource-1 "worker-123" 30)
  (do
    ;; Got the lock!
    (process-resource)
    (cache/delete-key! cache :lock:resource-1))
  ;; Lock already held by someone else
  (println "Resource is locked"))
```

### Compare and Set

```clojure
;; Optimistic locking pattern
(let [current-value (cache/get-value cache :inventory:widget)
      new-value (dec current-value)]
  (if (cache/compare-and-swap! cache :inventory:widget current-value new-value)
    (println "Successfully decremented inventory")
    (println "Inventory changed, retry needed")))
```

## Pattern Operations

### Find Keys by Pattern

```clojure
;; Set some values
(cache/set-value! cache :user:1 "Alice")
(cache/set-value! cache :user:2 "Bob")
(cache/set-value! cache :session:a "token-a")

;; Find all user keys
(cache/keys-matching cache "user:*")
;; => #{"user:1" "user:2"}

;; Count matching keys
(cache/count-matching cache "user:*")
;; => 2

;; Delete all matching keys
(cache/delete-matching! cache "user:*")
;; => 2
```

## Namespace Support

Namespaces provide logical partitioning of cache keys:

```clojure
;; Create namespaced cache views
(def user-cache (cache/with-namespace cache "users"))
(def product-cache (cache/with-namespace cache "products"))

;; Set values in different namespaces
(cache/set-value! user-cache :123 {:name "Alice"})
(cache/set-value! product-cache :123 {:name "Widget"})

;; Get values from namespaces
(cache/get-value user-cache :123)
;; => {:name "Alice"}

(cache/get-value product-cache :123)
;; => {:name "Widget"}

;; Clear entire namespace
(cache/clear-namespace! cache "users")
;; => 1 (number of keys deleted)
```

## Cache Statistics

```clojure
;; Create cache with stats tracking
(def cache (mem-cache/create-in-memory-cache {:track-stats? true}))

;; Generate some hits and misses
(cache/set-value! cache :key1 "value1")
(cache/get-value cache :key1)  ; hit
(cache/get-value cache :key1)  ; hit
(cache/get-value cache :key2)  ; miss
(cache/get-value cache :key3)  ; miss

;; Get statistics
(cache/cache-stats cache)
;; => {:size 1
;;     :hits 2
;;     :misses 2
;;     :hit-rate 0.5
;;     :evictions 0
;;     :memory-usage nil}

;; Clear statistics
(cache/clear-stats! cache)
```

## Configuration

### In-Memory Cache Configuration

```clojure
(def cache (mem-cache/create-in-memory-cache
            {:default-ttl 3600        ; Default TTL in seconds
             :max-size 10000          ; Max entries (LRU eviction)
             :track-stats? true}))    ; Track hit/miss stats
```

### Redis Cache Configuration

```clojure
(def redis-pool (redis-cache/create-redis-pool
                 {:host "redis.example.com"
                  :port 6379
                  :password "secret"
                  :database 0
                  :timeout 2000         ; Connection timeout (ms)
                  :max-total 20         ; Max connections
                  :max-idle 10          ; Max idle connections
                  :min-idle 2}))        ; Min idle connections

(def cache (redis-cache/create-redis-cache
            redis-pool
            {:default-ttl 3600          ; Default TTL
             :prefix "myapp"}))         ; Key prefix
```

## Use Cases

### Session Management

```clojure
(def session-cache (cache/with-namespace cache "sessions"))

(defn create-session! [user-id]
  (let [session-id (str (java.util.UUID/randomUUID))
        session {:user-id user-id
                 :created-at (java.time.Instant/now)}]
    (cache/set-value! session-cache session-id session 3600)  ; 1 hour
    session-id))

(defn get-session [session-id]
  (cache/get-value session-cache session-id))

(defn refresh-session! [session-id]
  (cache/expire! session-cache session-id 3600))

(defn destroy-session! [session-id]
  (cache/delete-key! session-cache session-id))
```

### Rate Limiting

```clojure
(defn rate-limit [user-id max-requests window-seconds]
  (let [key (str "rate-limit:" user-id)
        current (cache/get-value cache key)]
    (if (and current (>= current max-requests))
      false  ; Rate limit exceeded
      (do
        (if current
          (cache/increment! cache key)
          (cache/set-value! cache key 1 window-seconds))
        true))))  ; Request allowed

;; Usage
(rate-limit "user-123" 100 60)  ; Max 100 requests per minute
```

### Distributed Locking

```clojure
(defn acquire-lock! [resource-id timeout-seconds]
  (let [lock-key (str "lock:" resource-id)
        owner-id (str (java.util.UUID/randomUUID))]
    (when (cache/set-if-absent! cache lock-key owner-id timeout-seconds)
      owner-id)))

(defn release-lock! [resource-id owner-id]
  (let [lock-key (str "lock:" resource-id)
        current-owner (cache/get-value cache lock-key)]
    (when (= current-owner owner-id)
      (cache/delete-key! cache lock-key))))

;; Usage with automatic release
(when-let [lock-id (acquire-lock! "resource-1" 30)]
  (try
    ;; Critical section
    (process-resource)
    (finally
      (release-lock! "resource-1" lock-id))))
```

### Cache-Aside Pattern

```clojure
(defn get-user [user-id]
  (if-let [cached (cache/get-value cache (str "user:" user-id))]
    cached
    (let [user (db/find-user user-id)]
      (when user
        (cache/set-value! cache (str "user:" user-id) user 3600))
      user)))

(defn update-user! [user-id updates]
  (let [user (db/update-user! user-id updates)]
    ;; Invalidate cache
    (cache/delete-key! cache (str "user:" user-id))
    user))
```

### Computed Results Caching

```clojure
(defn expensive-computation [input]
  (let [cache-key (str "computation:" (hash input))]
    (if-let [cached (cache/get-value cache cache-key)]
      cached
      (let [result (do-expensive-work input)]
        (cache/set-value! cache cache-key result 7200)  ; Cache for 2 hours
        result))))
```

## Performance Characteristics

### In-Memory Adapter

- **Throughput**: 10,000+ ops/second
- **Latency**: < 1ms
- **Concurrency**: Thread-safe with atomic operations
- **Eviction**: LRU when max-size is reached
- **Limitations**: Single-process only, no persistence

### Redis Adapter

- **Throughput**: 1,000+ ops/second per connection
- **Latency**: < 10ms (local network)
- **Concurrency**: Fully distributed, atomic operations
- **Persistence**: Redis RDB/AOF
- **Scalability**: Horizontal with Redis Cluster

## Testing

The module includes comprehensive tests covering all operations:

```bash
# Run cache tests
clojure -X:test :dirs '["test"]' :patterns '["boundary.cache.*"]'
```

## Production Checklist

✅ **In-Memory Cache** (Development/Testing)
- Fast local development
- No external dependencies
- Perfect for unit tests
- CI/CD pipelines

✅ **Redis Cache** (Production)
- Distributed across processes
- Persistence and durability
- High availability with Redis Sentinel
- Horizontal scaling with Redis Cluster

## Migration Guide

### From Manual Caching

**Before:**
```clojure
(def cache-atom (atom {}))

(defn get-cached [key]
  (get @cache-atom key))

(defn set-cached! [key value]
  (swap! cache-atom assoc key value))
```

**After:**
```clojure
(def cache (mem-cache/create-in-memory-cache))

(cache/get-value cache key)
(cache/set-value! cache key value)
```

### From Other Libraries

Migration is straightforward - just implement the port protocols for your existing cache backend.

## Architecture

The cache module follows the **Functional Core / Imperative Shell** pattern:

- **Ports**: Protocol definitions (interface)
- **Adapters**: Redis and in-memory implementations (shell)
- **Schema**: Malli validation (core)

Benefits:
- Easy to test with in-memory adapter
- Swap implementations without code changes
- Type-safe with schema validation

## API Reference

See protocol definitions in `boundary.cache.ports` for complete API documentation.

## License

Copyright © 2026 Boundary Framework
