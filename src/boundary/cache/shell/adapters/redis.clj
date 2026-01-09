(ns boundary.cache.shell.adapters.redis
  "Redis-backed distributed cache implementation.

   Features:
   - Distributed caching across multiple processes
   - Automatic TTL and expiration
   - Atomic operations (INCR, DECR, SETNX)
   - Pattern matching with SCAN
   - Namespace support
   - Connection pooling

   Suitable for:
   - Production deployments
   - Distributed systems
   - High-availability applications
   - Microservices architecture

   Redis Data Structures Used:
   - Strings: For cache values (JSON serialized)
   - TTL: Built-in Redis expiration
   - Atomic ops: INCR, DECR, SETNX, etc."
  (:require [boundary.cache.ports :as ports]
            [boundary.cache.schema :as schema]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [redis.clients.jedis Jedis JedisPool JedisPoolConfig ScanParams]
           [redis.clients.jedis.params SetParams]
           [java.util ArrayList]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- with-redis
  "Execute function with Redis connection from pool."
  [^JedisPool pool f]
  (with-open [^Jedis redis (.getResource pool)]
    (f redis)))

(defn- add-namespace
  "Add namespace prefix to key."
  [namespace key]
  (if namespace
    (str namespace ":" (name key))
    (name key)))

(defn- strip-namespace
  "Remove namespace prefix from key."
  [namespace key]
  (if namespace
    (let [prefix (str namespace ":")]
      (if (.startsWith key prefix)
        (.substring key (count prefix))
        key))
    key))

(defn- serialize-value
  "Serialize value to JSON string."
  [value]
  (json/generate-string value))

(defn- deserialize-value
  "Deserialize value from JSON string."
  [json-str]
  (when json-str
    (json/parse-string json-str true)))

;; =============================================================================
;; Cache Operations
;; =============================================================================

(defrecord RedisCache [^JedisPool pool config namespace]
  ports/ICache

  (get-value [_ key]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)
              json-str (.get redis namespaced-key)]
          (when json-str
            (deserialize-value json-str))))))

  (set-value! [_ key value]
    (ports/set-value! this key value (:default-ttl config)))

  (set-value! [_ key value ttl-seconds]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)
              serialized (serialize-value value)]
          (if ttl-seconds
            (.setex redis namespaced-key (long ttl-seconds) serialized)
            (.set redis namespaced-key serialized))
          true))))

  (delete-key! [_ key]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)
              result (.del redis (into-array String [namespaced-key]))]
          (pos? result)))))

  (exists? [_ key]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)]
          (.exists redis (into-array String [namespaced-key]))))))

  (ttl [_ key]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)
              ttl (.ttl redis namespaced-key)]
          (when (pos? ttl)
            ttl)))))

  (expire! [_ key ttl-seconds]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)
              result (.expire redis namespaced-key (long ttl-seconds))]
          (= result 1)))))

  ;; =============================================================================
  ;; Batch Operations
  ;; =============================================================================

  ports/IBatchCache

  (get-many [_ keys]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-keys (mapv #(add-namespace namespace %) keys)
              values (.mget redis (into-array String namespaced-keys))]
          (into {}
                (keep-indexed
                 (fn [idx value]
                   (when value
                     [(nth keys idx) (deserialize-value value)]))
                 values))))))

  (set-many! [_ key-value-map]
    (ports/set-many! this key-value-map (:default-ttl config)))

  (set-many! [_ key-value-map ttl-seconds]
    (with-redis pool
      (fn [^Jedis redis]
        ;; Use pipeline for efficiency
        (let [pipeline (.pipelined redis)]
          (doseq [[k v] key-value-map]
            (let [namespaced-key (add-namespace namespace k)
                  serialized (serialize-value v)]
              (if ttl-seconds
                (.setex pipeline namespaced-key (long ttl-seconds) serialized)
                (.set pipeline namespaced-key serialized))))
          (.sync pipeline)
          (count key-value-map)))))

  (delete-many! [_ keys]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-keys (mapv #(add-namespace namespace %) keys)
              result (.del redis (into-array String namespaced-keys))]
          result))))

  ;; =============================================================================
  ;; Atomic Operations
  ;; =============================================================================

  ports/IAtomicCache

  (increment! [_ key]
    (ports/increment! this key 1))

  (increment! [_ key delta]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)]
          (.incrBy redis namespaced-key (long delta))))))

  (decrement! [_ key]
    (ports/decrement! this key 1))

  (decrement! [_ key delta]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)]
          (.decrBy redis namespaced-key (long delta))))))

  (set-if-absent! [_ key value]
    (ports/set-if-absent! this key value (:default-ttl config)))

  (set-if-absent! [_ key value ttl-seconds]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)
              serialized (serialize-value value)
              params (SetParams/setParams)]
          (.nx params)
          (when ttl-seconds
            (.ex params (long ttl-seconds)))
          (let [result (.set redis namespaced-key serialized params)]
            (= result "OK"))))))

  (compare-and-set! [_ key expected-value new-value]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)]
          ;; Watch key for changes
          (.watch redis (into-array String [namespaced-key]))
          (let [current-json (.get redis namespaced-key)
                current-value (when current-json (deserialize-value current-json))]
            (if (= current-value expected-value)
              (let [transaction (.multi redis)
                    new-json (serialize-value new-value)]
                (.set transaction namespaced-key new-json)
                (let [result (.exec transaction)]
                  (some? result)))
              (do
                (.unwatch redis)
                false)))))))

  ;; =============================================================================
  ;; Pattern Operations
  ;; =============================================================================

  ports/IPatternCache

  (keys-matching [_ pattern]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-pattern (add-namespace namespace pattern)
              scan-params (doto (ScanParams.)
                            (.match namespaced-pattern)
                            (.count (int 100)))
              keys (java.util.HashSet.)]
          ;; Use SCAN for better performance than KEYS
          (loop [cursor "0"]
            (let [result (.scan redis cursor scan-params)]
              (.addAll keys (.getResult result))
              (let [next-cursor (.getCursor result)]
                (when-not (= next-cursor "0")
                  (recur next-cursor)))))
          (into #{}
                (map #(strip-namespace namespace %))
                keys)))))

  (delete-matching! [_ pattern]
    (let [matching-keys (ports/keys-matching this pattern)]
      (if (seq matching-keys)
        (ports/delete-many! this matching-keys)
        0)))

  (count-matching [_ pattern]
    (count (ports/keys-matching this pattern)))

  ;; =============================================================================
  ;; Namespace Operations
  ;; =============================================================================

  ports/INamespacedCache

  (with-namespace [_ new-namespace]
    (->RedisCache pool config new-namespace))

  (clear-namespace! [_ ns]
    (let [pattern (str ns ":*")]
      (ports/delete-matching! this pattern)))

  ;; =============================================================================
  ;; Cache Statistics
  ;; =============================================================================

  ports/ICacheStats

  (cache-stats [this]
    (with-redis pool
      (fn [^Jedis redis]
        (let [info (.info redis "stats")
              ;; Parse Redis INFO output
              stats-map (into {}
                              (map (fn [line]
                                     (let [[k v] (clojure.string/split line #":")]
                                       [(keyword k) v]))
                                   (clojure.string/split-lines info)))
              hits (Long/parseLong (get stats-map :keyspace_hits "0"))
              misses (Long/parseLong (get stats-map :keyspace_misses "0"))
              total-requests (+ hits misses)
              hit-rate (if (pos? total-requests)
                         (/ hits (double total-requests))
                         0.0)]
          {:size (.dbSize redis)
           :hits hits
           :misses misses
           :hit-rate hit-rate
           :memory-usage nil
           :evictions nil}))))

  (clear-stats! [this]
    (with-redis pool
      (fn [^Jedis redis]
        (.configResetStat redis)
        true)))

  ;; =============================================================================
  ;; Cache Management
  ;; =============================================================================

  ports/ICacheManagement

  (flush-all! [this]
    (with-redis pool
      (fn [^Jedis redis]
        (let [size (.dbSize redis)]
          (.flushDB redis)
          size))))

  (ping [this]
    (try
      (with-redis pool
        (fn [^Jedis redis]
          (= "PONG" (.ping redis))))
      (catch Exception e
        (log/error e "Redis ping failed")
        false)))

  (close! [this]
    (try
      (.close pool)
      true
      (catch Exception e
        (log/error e "Failed to close Redis pool")
        false))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-redis-pool
  "Create a Jedis connection pool.

   Args:
     config - Configuration map:
              :host - Redis host (default: localhost)
              :port - Redis port (default: 6379)
              :password - Redis password (optional)
              :database - Redis database number (default: 0)
              :timeout - Connection timeout in ms (default: 2000)
              :max-total - Max connections (default: 20)
              :max-idle - Max idle connections (default: 10)
              :min-idle - Min idle connections (default: 2)

   Returns:
     JedisPool instance"
  [config]
  (let [pool-config (doto (JedisPoolConfig.)
                      (.setMaxTotal (or (:max-total config) 20))
                      (.setMaxIdle (or (:max-idle config) 10))
                      (.setMinIdle (or (:min-idle config) 2))
                      (.setTestOnBorrow true)
                      (.setTestOnReturn true))
        host (or (:host config) "localhost")
        port (or (:port config) 6379)
        timeout (or (:timeout config) 2000)
        password (:password config)
        database (or (:database config) 0)]

    (if password
      (JedisPool. pool-config host port timeout password database)
      (JedisPool. pool-config host port timeout))))

(defn create-redis-cache
  "Create Redis cache instance.

   Args:
     pool - JedisPool instance
     config - Optional configuration map:
              :default-ttl - Default TTL in seconds
              :prefix - Key prefix for all operations

   Returns:
     RedisCache instance implementing all cache protocols"
  ([pool]
   (create-redis-cache pool {}))
  ([pool config]
   (->RedisCache pool config (:prefix config))))

(defn close-redis-pool!
  "Close Redis connection pool.

   Args:
     pool - JedisPool instance"
  [^JedisPool pool]
  (.close pool))
