(ns boundary.jobs.shell.adapters.redis
  "Redis-backed job queue implementation.

   Uses Redis for distributed job queuing with the following Redis data structures:
   - Sorted Sets: For scheduled jobs (scored by execute-at timestamp)
   - Lists: For priority queues (critical, high, normal, low)
   - Hashes: For job data storage
   - Sets: For tracking workers

   This adapter provides production-grade job queuing with:
   - Distributed queue across multiple workers
   - Priority-based job processing
   - Scheduled job execution
   - Job persistence
   - Atomic operations"
  (:require [boundary.jobs.ports :as ports]
            [boundary.jobs.core.job :as job]
            [boundary.jobs.schema :as schema]
            [clojure.tools.logging :as log]
            [cheshire.core :as json])
  (:import [redis.clients.jedis Jedis JedisPool JedisPoolConfig]
           [java.time Instant]))

;; =============================================================================
;; Redis Key Management
;; =============================================================================

(defn- job-key
  "Generate Redis key for job data."
  [job-id]
  (str "job:" job-id))

(defn- queue-key
  "Generate Redis key for queue list."
  [queue-name]
  (str "queue:" (name queue-name)))

(defn- scheduled-key
  "Generate Redis key for scheduled jobs sorted set."
  []
  "jobs:scheduled")

(defn- dead-letter-key
  "Generate Redis key for dead letter queue."
  []
  "jobs:failed")

(defn- stats-key
  "Generate Redis key for statistics."
  [queue-name]
  (str "stats:" (name queue-name)))

;; =============================================================================
;; Job Serialization
;; =============================================================================

(defn- serialize-job
  "Serialize job to JSON string."
  [job]
  (json/generate-string
   (-> job
       (update :execute-at #(when % (.toEpochMilli %)))
       (update :created-at #(.toEpochMilli %))
       (update :updated-at #(.toEpochMilli %))
       (update :started-at #(when % (.toEpochMilli %)))
       (update :completed-at #(when % (.toEpochMilli %))))))

(defn- deserialize-job
  "Deserialize job from JSON string."
  [json-str]
  (when json-str
    (-> (json/parse-string json-str true)
        (update :id #(java.util.UUID/fromString %))
        (update :execute-at #(when % (Instant/ofEpochMilli %)))
        (update :created-at #(Instant/ofEpochMilli %))
        (update :updated-at #(Instant/ofEpochMilli %))
        (update :started-at #(when % (Instant/ofEpochMilli %)))
        (update :completed-at #(when % (Instant/ofEpochMilli %))))))

;; =============================================================================
;; Redis Operations
;; =============================================================================

(defn- with-redis
  "Execute function with Redis connection from pool."
  [^JedisPool pool f]
  (with-open [^Jedis redis (.getResource pool)]
    (f redis)))

;; =============================================================================
;; Job Queue Implementation
;; =============================================================================

(defrecord RedisJobQueue [^JedisPool pool]
  ports/IJobQueue

  (enqueue-job! [_ queue-name job]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-id (:id job)
              job-key (job-key job-id)
              queue-key (queue-key queue-name)
              serialized (serialize-job job)]

          ;; Store job data
          (.set redis job-key serialized)

          ;; Add to appropriate queue based on priority and execute-at
          (if (:execute-at job)
            ;; Scheduled job: add to sorted set with execute-at as score
            (.zadd redis (scheduled-key)
                   (double (.toEpochMilli (:execute-at job)))
                   (str job-id))

            ;; Immediate job: add to priority queue
            (case (:priority job :normal)
              :critical (.lpush redis (str queue-key ":critical") (into-array String [(str job-id)]))
              :high (.lpush redis (str queue-key ":high") (into-array String [(str job-id)]))
              :normal (.lpush redis queue-key (into-array String [(str job-id)]))
              :low (.rpush redis (str queue-key ":low") (into-array String [(str job-id)]))))

          (log/info "Enqueued job" {:job-id job-id :queue queue-name :priority (:priority job)})
          job-id))))

  (schedule-job! [this queue-name job execute-at]
    (let [scheduled-job (assoc job :execute-at execute-at)]
      (ports/enqueue-job! this queue-name scheduled-job)))

  (dequeue-job! [_ queue-name]
    (with-redis pool
      (fn [^Jedis redis]
        (let [queue-key (queue-key queue-name)
              ;; Try to dequeue from priority queues in order
              job-id (or (.rpop redis (str queue-key ":critical"))
                         (.rpop redis (str queue-key ":high"))
                         (.rpop redis queue-key)
                         (.rpop redis (str queue-key ":low")))]

          (when job-id
            (let [job-key (job-key (java.util.UUID/fromString job-id))
                  job-data (.get redis job-key)]
              (when job-data
                (deserialize-job job-data))))))))

  (peek-job [_ queue-name]
    (with-redis pool
      (fn [^Jedis redis]
        (let [queue-key (queue-key queue-name)
              job-ids (.lrange redis queue-key -1 -1)]
          (when-let [job-id (first job-ids)]
            (let [job-key (job-key (java.util.UUID/fromString job-id))
                  job-data (.get redis job-key)]
              (when job-data
                (deserialize-job job-data))))))))

  (delete-job! [_ job-id]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-key (job-key job-id)
              result (.del redis (into-array String [job-key]))]
          ;; Also remove from scheduled set if present
          (.zrem redis (scheduled-key) (str job-id))
          (pos? result)))))

  (queue-size [_ queue-name]
    (with-redis pool
      (fn [^Jedis redis]
        (let [queue-key (queue-key queue-name)]
          (+ (.llen redis (str queue-key ":critical"))
             (.llen redis (str queue-key ":high"))
             (.llen redis queue-key)
             (.llen redis (str queue-key ":low")))))))

  (list-queues [_this]
    (with-redis pool
      (fn [^Jedis redis]
        (let [keys (.keys redis "queue:*")]
          (->> keys
               (map #(second (re-find #"queue:([^:]+)" %)))
               (filter some?)
               (map keyword)
               vec))))))

;; =============================================================================
;; Scheduled Job Processor
;; =============================================================================

(defn process-scheduled-jobs!
  "Move scheduled jobs that are due to execution queues.

   This should be called periodically (e.g., every 5 seconds) by a worker.

   Args:
     queue - RedisJobQueue instance

   Returns:
     Number of jobs moved to execution queues"
  [^RedisJobQueue queue]
  (with-redis (:pool queue)
    (fn [^Jedis redis]
      (let [now (Instant/now)
            now-ms (.toEpochMilli now)
            ;; Get all jobs with score <= now
            due-job-ids (.zrangeByScore redis (scheduled-key) 0.0 (double now-ms))]

        (doseq [job-id-str due-job-ids]
          (let [job-id (java.util.UUID/fromString job-id-str)
                job-key (job-key job-id)
                job-data (.get redis job-key)]

            (when job-data
              (let [job (deserialize-job job-data)
                    queue-name (:queue job)
                    queue-key (queue-key queue-name)]

                ;; Remove from scheduled set
                (.zrem redis (scheduled-key) job-id-str)

                ;; Add to execution queue based on priority
                (case (:priority job :normal)
                  :critical (.lpush redis (str queue-key ":critical") (into-array String [job-id-str]))
                  :high (.lpush redis (str queue-key ":high") (into-array String [job-id-str]))
                  :normal (.lpush redis queue-key (into-array String [job-id-str]))
                  :low (.rpush redis (str queue-key ":low") (into-array String [job-id-str])))

                (log/debug "Moved scheduled job to execution queue"
                           {:job-id job-id :queue queue-name})))))

        (count due-job-ids)))))

;; =============================================================================
;; Job Store Implementation
;; =============================================================================

(defrecord RedisJobStore [^JedisPool pool]
  ports/IJobStore

  (save-job! [_ job]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-key (job-key (:id job))
              serialized (serialize-job job)]
          (.set redis job-key serialized)
          ;; Set expiration: keep completed jobs for 7 days
          (when (#{:completed :failed :cancelled} (:status job))
            (.expire redis job-key (int (* 7 24 60 60))))
          job))))

  (find-job [_ job-id]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-key (job-key job-id)
              job-data (.get redis job-key)]
          (when job-data
            (deserialize-job job-data))))))

  (update-job-status! [_ job-id status result]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-key (job-key job-id)
              job-data (.get redis job-key)]
          (when job-data
            (let [job (deserialize-job job-data)
                  updated-job (case status
                                :running (job/start-job job)
                                :completed (job/complete-job job result)
                                :failed (job/fail-job job result)
                                :cancelled (job/cancel-job job)
                                job)
                  serialized (serialize-job updated-job)]
              (.set redis job-key serialized)

              ;; If job failed and no more retries, move to dead letter queue
              (when (and (= status :failed) (not (job/can-retry? updated-job)))
                (.lpush redis (dead-letter-key) (into-array String [(str job-id)])))

              updated-job))))))

  (find-jobs [_ filters]
    ;; Note: For production, consider using Redis Search module for complex queries
    ;; This implementation scans all job keys (not optimal for large datasets)
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-keys (.keys redis "job:*")]
          (->> job-keys
               (map (fn [key]
                      (let [job-data (.get redis key)]
                        (when job-data
                          (deserialize-job job-data)))))
               (filter some?)
               (filter (fn [job]
                         (and (or (nil? (:status filters))
                                  (= (:status filters) (:status job)))
                              (or (nil? (:job-type filters))
                                  (= (:job-type filters) (:job-type job)))
                              (or (nil? (:queue filters))
                                  (= (:queue filters) (:queue job))))))
               vec)))))

  (failed-jobs [_ limit]
    (with-redis pool
      (fn [^Jedis redis]
        (let [failed-job-ids (.lrange redis (dead-letter-key) 0 (dec limit))]
          (->> failed-job-ids
               (map (fn [job-id-str]
                      (let [job-id (java.util.UUID/fromString job-id-str)
                            job-key (job-key job-id)
                            job-data (.get redis job-key)]
                        (when job-data
                          (deserialize-job job-data)))))
               (filter some?)
               vec)))))

  (retry-job! [_ job-id]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-key (job-key job-id)
              job-data (.get redis job-key)]
          (when job-data
            (let [job (deserialize-job job-data)
                  retry-config {:backoff-strategy :exponential
                                :initial-delay-ms 1000
                                :max-delay-ms 60000}
                  retry-job (job/prepare-retry job retry-config)
                  serialized (serialize-job retry-job)]

              ;; Update job data
              (.set redis job-key serialized)

              ;; Remove from dead letter queue
              (.lrem redis (dead-letter-key) 0 (str job-id))

              ;; Add back to scheduled jobs
              (.zadd redis (scheduled-key)
                     (double (.toEpochMilli (:execute-at retry-job)))
                     (str job-id))

              retry-job)))))))

;; =============================================================================
;; Job Statistics Implementation
;; =============================================================================

(defrecord RedisJobStats [^JedisPool pool]
  ports/IJobStats

(job-stats [this]
    (with-redis pool
      (fn [^Jedis redis]
        (let [queue-keys (.keys redis "queue:*")
              queues (->> queue-keys
                          (map #(second (re-find #"queue:([^:]+)" %)))
                          (filter some?)
                          (map keyword)
                          distinct)]
          {:total-processed 0  ; TODO: Implement counters
           :total-failed 0
           :total-succeeded 0
           :queues (mapv (fn [queue-name]
                           (let [stats (ports/queue-stats this queue-name)]
                             (assoc stats :queue-name queue-name)))
                         queues)
           :workers []}))))  ; TODO: Implement worker tracking

  (queue-stats [_ queue-name]
    (with-redis pool
      (fn [^Jedis redis]
        (let [queue-key (queue-key queue-name)]
          {:queue-name queue-name
           :size (+ (.llen redis (str queue-key ":critical"))
                    (.llen redis (str queue-key ":high"))
                    (.llen redis queue-key)
                    (.llen redis (str queue-key ":low")))
           :processed-total 0   ; TODO: Implement with Redis counters
           :failed-total 0
           :succeeded-total 0
           :avg-duration-ms nil}))))

  (job-history [_ job-type limit]
    (with-redis pool
      (fn [^Jedis redis]
        ;; Simplified implementation - in production, use time-series data
        (let [job-keys (.keys redis "job:*")]
          (->> job-keys
               (map (fn [key]
                      (let [job-data (.get redis key)]
                        (when job-data
                          (deserialize-job job-data)))))
               (filter #(= (:job-type %) job-type))
               (sort-by :created-at #(compare %2 %1))  ; Newest first
               (take limit)
               vec))))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-redis-pool
  "Create a Jedis connection pool.

   Args:
     config - Map with:
              :host - Redis host (default localhost)
              :port - Redis port (default 6379)
              :password - Redis password (optional)
              :database - Redis database number (default 0)
              :max-total - Max connections (default 20)
              :max-idle - Max idle connections (default 10)

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

(defn create-redis-job-queue
  "Create Redis-backed job queue.

   Args:
     pool - JedisPool instance

   Returns:
     RedisJobQueue implementing IJobQueue"
  [pool]
  (->RedisJobQueue pool))

(defn create-redis-job-store
  "Create Redis-backed job store.

   Args:
     pool - JedisPool instance

   Returns:
     RedisJobStore implementing IJobStore"
  [pool]
  (->RedisJobStore pool))

(defn create-redis-job-stats
  "Create Redis-backed job stats.

   Args:
     pool - JedisPool instance

   Returns:
     RedisJobStats implementing IJobStats"
  [pool]
  (->RedisJobStats pool))

(defn close-redis-pool!
  "Close Redis connection pool.

   Args:
     pool - JedisPool instance"
  [^JedisPool pool]
  (.close pool))
