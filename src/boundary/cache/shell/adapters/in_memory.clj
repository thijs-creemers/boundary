(ns boundary.cache.shell.adapters.in-memory
  "In-memory cache implementation for development and testing.

   Features:
   - Thread-safe operations using Clojure atoms
   - TTL support with automatic expiration
   - LRU eviction policy
   - Statistics tracking
   - Pattern matching
   - Namespace support

   Suitable for:
   - Local development without Redis
   - Fast unit testing
   - CI/CD pipelines
   - Single-process applications

   NOT suitable for:
   - Distributed systems (not shared across processes)
   - Production use (no persistence)
   - High-memory workloads (limited by JVM heap)"
  (:require [boundary.cache.ports :as ports]
            [boundary.cache.schema :as schema]
            [clojure.string :as str])
  (:import [java.time Instant Duration]))

;; =============================================================================
;; State Management
;; =============================================================================

(defrecord CacheEntry
           [value created-at expires-at access-count last-accessed-at])

(defrecord InMemoryState
           [entries         ; atom: map of key -> CacheEntry
            stats           ; atom: {:hits :misses :evictions}
            config          ; {:max-size :default-ttl :track-stats?}
            namespace])     ; optional namespace prefix

(defn- create-state
  "Create initial cache state."
  ([config] (create-state config nil))
  ([config namespace]
   (->InMemoryState
    (atom {})
    (atom {:hits 0 :misses 0 :evictions 0})
    config
    namespace)))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- now
  "Get current time as Instant."
  []
  (Instant/now))

(defn- add-namespace
  "Add namespace prefix to key if namespace is set."
  [namespace key]
  (if namespace
    (str namespace ":" (name key))
    (name key)))

(defn- strip-namespace
  "Remove namespace prefix from key."
  [namespace key]
  (if namespace
    (let [prefix (str namespace ":")]
      (if (str/starts-with? key prefix)
        (subs key (count prefix))
        key))
    key))

(defn- expired?
  "Check if cache entry has expired."
  [entry]
  (when-let [expires-at (:expires-at entry)]
    (.isAfter (now) expires-at)))

(defn- calculate-expires-at
  "Calculate expiration time from TTL seconds."
  [ttl-seconds]
  (when ttl-seconds
    (.plusSeconds (now) ttl-seconds)))

(defn- clean-expired!
  "Remove expired entries from cache."
  [entries-atom]
  (swap! entries-atom
         (fn [entries]
           (into {}
                 (remove (fn [[_ entry]] (expired? entry))
                         entries)))))

(defn- record-hit!
  "Record cache hit in statistics."
  [stats-atom track-stats?]
  (when track-stats?
    (swap! stats-atom update :hits inc)))

(defn- record-miss!
  "Record cache miss in statistics."
  [stats-atom track-stats?]
  (when track-stats?
    (swap! stats-atom update :misses inc)))

(defn- record-eviction!
  "Record cache eviction in statistics."
  [stats-atom track-stats?]
  (when track-stats?
    (swap! stats-atom update :evictions inc)))

(defn- evict-lru!
  "Evict least recently used entry."
  [entries-atom stats-atom track-stats?]
  (let [entries @entries-atom]
    (when (seq entries)
      (let [lru-key (first (sort-by (fn [[_ entry]]
                                      (:last-accessed-at entry))
                                    entries))]
        (swap! entries-atom dissoc (first lru-key))
        (record-eviction! stats-atom track-stats?)))))

(defn- maybe-evict!
  "Evict entries if cache is over max-size."
  [entries-atom stats-atom config]
  (when-let [max-size (:max-size config)]
    (let [current-size (count @entries-atom)]
      (when (> current-size max-size)
        (evict-lru! entries-atom stats-atom (:track-stats? config))))))

(defn- wildcard-pattern->regex
  "Convert wildcard pattern to regex.
   Example: 'user:*' -> #'user:.*'"
  [pattern]
  (-> pattern
      (str/replace "*" ".*")
      (str/replace "?" ".")
      re-pattern))

;; =============================================================================
;; Cache Operations
;; =============================================================================

(defrecord InMemoryCache [state]
  ports/ICache

  (get-value [this key]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          entry (get @entries namespaced-key)]
      (cond
        (nil? entry)
        (do
          (record-miss! (:stats state) (:track-stats? (:config state)))
          nil)

        (expired? entry)
        (do
          (swap! entries dissoc namespaced-key)
          (record-miss! (:stats state) (:track-stats? (:config state)))
          nil)

        :else
        (do
          (record-hit! (:stats state) (:track-stats? (:config state)))
          ;; Update access count and last accessed time
          (swap! entries update namespaced-key
                 (fn [e]
                   (-> e
                       (update :access-count inc)
                       (assoc :last-accessed-at (now)))))
          (:value entry)))))

  (set-value! [this key value]
    (ports/set-value! this key value (:default-ttl (:config state))))

  (set-value! [this key value ttl-seconds]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          entry (->CacheEntry
                 value
                 (now)
                 (calculate-expires-at ttl-seconds)
                 0
                 (now))]
      (swap! entries assoc namespaced-key entry)
      (maybe-evict! entries (:stats state) (:config state))
      true))

  (delete-key! [this key]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)]
      (if (contains? @entries namespaced-key)
        (do
          (swap! entries dissoc namespaced-key)
          true)
        false)))

  (exists? [this key]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          entry (get @entries namespaced-key)]
      (boolean (and entry (not (expired? entry))))))

  (ttl [this key]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          entry (get @entries namespaced-key)]
      (when (and entry (not (expired? entry)))
        (when-let [expires-at (:expires-at entry)]
          (.getSeconds (Duration/between (now) expires-at))))))

  (expire! [this key ttl-seconds]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)]
      (if-let [entry (get @entries namespaced-key)]
        (do
          (swap! entries assoc-in [namespaced-key :expires-at]
                 (calculate-expires-at ttl-seconds))
          true)
        false)))

  ;; =============================================================================
  ;; Batch Operations
  ;; =============================================================================

  ports/IBatchCache

  (get-many [this keys]
    (into {}
          (keep (fn [k]
                  (when-let [v (ports/get-value this k)]
                    [k v]))
                keys)))

  (set-many! [this key-value-map]
    (ports/set-many! this key-value-map (:default-ttl (:config state))))

  (set-many! [this key-value-map ttl-seconds]
    (doseq [[k v] key-value-map]
      (ports/set-value! this k v ttl-seconds))
    (count key-value-map))

  (delete-many! [this keys]
    (reduce (fn [count k]
              (if (ports/delete-key! this k)
                (inc count)
                count))
            0
            keys))

  ;; =============================================================================
  ;; Atomic Operations
  ;; =============================================================================

  ports/IAtomicCache

  (increment! [this key]
    (ports/increment! this key 1))

  (increment! [this key delta]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)]
      (-> (swap! entries
                 (fn [cache]
                   (let [current-entry (get cache namespaced-key)
                         current-value (if current-entry
                                         (:value current-entry)
                                         0)
                         new-value (+ current-value delta)]
                     (assoc cache namespaced-key
                            (->CacheEntry new-value (now) nil 0 (now))))))
          (get namespaced-key)
          :value)))

  (decrement! [this key]
    (ports/decrement! this key 1))

  (decrement! [this key delta]
    (ports/increment! this key (- delta)))

  (set-if-absent! [this key value]
    (ports/set-if-absent! this key value (:default-ttl (:config state))))

  (set-if-absent! [this key value ttl-seconds]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)]
      (if (contains? @entries namespaced-key)
        false
        (do
          (ports/set-value! this key value ttl-seconds)
          true))))

  (compare-and-set! [this key expected-value new-value]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          result (atom false)]
      (swap! entries
             (fn [cache]
               (let [current-entry (get cache namespaced-key)
                     current-value (:value current-entry)]
                 (if (= current-value expected-value)
                   (do
                     (reset! result true)
                     (assoc cache namespaced-key
                            (->CacheEntry new-value (now) (:expires-at current-entry) 0 (now))))
                   cache))))
      @result))

  ;; =============================================================================
  ;; Pattern Operations
  ;; =============================================================================

  ports/IPatternCache

  (keys-matching [this pattern]
    (let [namespaced-pattern (add-namespace (:namespace state) pattern)
          regex (wildcard-pattern->regex namespaced-pattern)
          entries (:entries state)
          namespace (:namespace state)]
      (into #{}
            (comp
             (filter (fn [[k _]] (re-matches regex k)))
             (map (fn [[k _]] (strip-namespace namespace k))))
            @entries)))

  (delete-matching! [this pattern]
    (let [matching-keys (ports/keys-matching this pattern)]
      (ports/delete-many! this matching-keys)))

  (count-matching [this pattern]
    (count (ports/keys-matching this pattern)))

  ;; =============================================================================
  ;; Namespace Operations
  ;; =============================================================================

  ports/INamespacedCache

  (with-namespace [this namespace]
    (->InMemoryCache
     (->InMemoryState
      (:entries state)
      (:stats state)
      (:config state)
      namespace)))

  (clear-namespace! [this namespace]
    (let [pattern (str namespace ":*")]
      (ports/delete-matching! this pattern)))

  ;; =============================================================================
  ;; Cache Statistics
  ;; =============================================================================

  ports/ICacheStats

  (cache-stats [this]
    (let [entries @(:entries state)
          stats @(:stats state)
          total-requests (+ (:hits stats) (:misses stats))
          hit-rate (if (pos? total-requests)
                     (/ (:hits stats) (double total-requests))
                     0.0)]
      {:size (count entries)
       :hits (:hits stats)
       :misses (:misses stats)
       :hit-rate hit-rate
       :evictions (:evictions stats)
       :memory-usage nil}))  ; Not available for in-memory

  (clear-stats! [this]
    (reset! (:stats state) {:hits 0 :misses 0 :evictions 0})
    true)

  ;; =============================================================================
  ;; Cache Management
  ;; =============================================================================

  ports/ICacheManagement

  (flush-all! [this]
    (let [entries (:entries state)
          size (count @entries)]
      (reset! entries {})
      size))

  (ping [this]
    true)  ; In-memory cache is always available

  (close! [this]
    true))  ; Nothing to close for in-memory cache

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-in-memory-cache
  "Create in-memory cache instance.

   Args:
     config - Optional configuration map:
              :default-ttl - Default TTL in seconds
              :max-size - Maximum number of entries (LRU eviction)
              :track-stats? - Track hit/miss statistics (default true)

   Returns:
     InMemoryCache instance implementing all cache protocols"
  ([]
   (create-in-memory-cache {}))
  ([config]
   (let [default-config {:track-stats? true}
         merged-config (merge default-config config)
         state (create-state merged-config)]
     (->InMemoryCache state))))

;; =============================================================================
;; Testing Utilities
;; =============================================================================

(defn clear-all!
  "Clear all cache entries and statistics. Useful for testing.

   Args:
     cache - InMemoryCache instance"
  [cache]
  (ports/flush-all! cache)
  (ports/clear-stats! cache))

(defn get-all-entries
  "Get all cache entries. Useful for testing.

   Args:
     cache - InMemoryCache instance

   Returns:
     Map of key -> CacheEntry"
  [cache]
  @(:entries (:state cache)))
