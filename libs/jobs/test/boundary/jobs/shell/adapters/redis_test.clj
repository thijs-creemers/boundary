(ns boundary.jobs.shell.adapters.redis-test
  "Integration tests for the Redis job queue adapter.

   These tests require a running Redis instance.
   If Redis is not available on localhost:6379 the tests are skipped."
  {:kaocha.testable/meta {:integration true :redis true}}
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.jobs.shell.adapters.redis :as redis-adapter]
            [boundary.jobs.core.job :as job]
            [boundary.jobs.ports :as ports])
  (:import [java.time Instant]
           [java.util UUID]
           [redis.clients.jedis JedisPool]))

;; =============================================================================
;; Redis availability check
;; =============================================================================

(defn redis-available?
  "Check if Redis is reachable on localhost:6379."
  []
  (try
    (let [pool (redis-adapter/create-redis-pool {:host "localhost" :port 6379 :timeout 1000})
          ^redis.clients.jedis.Jedis jedis (.getResource pool)
          pong (.ping jedis)]
      (.close jedis)
      (redis-adapter/close-redis-pool! pool)
      (= "PONG" pong))
    (catch Exception _
      false)))

;; =============================================================================
;; Test fixtures
;; =============================================================================

(def ^:dynamic *queue* nil)
(def ^:dynamic *store* nil)
(def ^:dynamic *stats* nil)
(def ^:dynamic *pool* nil)

(defn flush-test-db!
  "Flush Redis DB 14 used for tests."
  [^JedisPool pool]
  (let [jedis (.getResource pool)]
    (try
      (.flushDB jedis)
      (finally
        (.close jedis)))))

(defn with-redis-jobs
  "Fixture that creates Redis-backed jobs components using DB 14."
  [f]
  (if (redis-available?)
    (let [pool (redis-adapter/create-redis-pool {:host "localhost" :port 6379 :database 14})]
      (try
        (flush-test-db! pool)
        (binding [*pool*  pool
                  *queue* (redis-adapter/create-redis-job-queue pool)
                  *store* (redis-adapter/create-redis-job-store pool)
                  *stats* (redis-adapter/create-redis-job-stats pool)]
          (f))
        (finally
          (redis-adapter/close-redis-pool! pool))))
    (f)))

(use-fixtures :each with-redis-jobs)

(defmacro when-redis [& body]
  `(if (redis-available?)
     (do ~@body)
     (is true "Redis not available — test skipped")))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- make-job
  ([] (make-job {}))
  ([overrides]
   (job/create-job
    (merge {:job-type :send-email
            :args {:to "user@example.com"}
            :queue :default}
           overrides)
    (UUID/randomUUID)
    (Instant/now))))

;; =============================================================================
;; Queue operations
;; =============================================================================

(deftest ^:integration redis-enqueue-dequeue-test
  (when-redis
    (let [test-job (make-job)]

      (testing "enqueue adds job to queue"
        (ports/enqueue-job! *queue* :default test-job)
        (is (= 1 (ports/queue-size *queue* :default))))

      (testing "dequeue returns job"
        (let [dequeued (ports/dequeue-job! *queue* :default)]
          (is (= (:id test-job) (:id dequeued)))
          (is (zero? (ports/queue-size *queue* :default))))))))

(deftest ^:integration redis-priority-queue-test
  (when-redis
    (let [low      (make-job {:priority :low})
          high     (make-job {:priority :high})
          critical (make-job {:priority :critical})]

      (ports/enqueue-job! *queue* :default low)
      (ports/enqueue-job! *queue* :default high)
      (ports/enqueue-job! *queue* :default critical)

      (testing "dequeues in priority order (critical first)"
        (is (= (:id critical) (:id (ports/dequeue-job! *queue* :default))))
        (is (= (:id high)     (:id (ports/dequeue-job! *queue* :default))))
        (is (= (:id low)      (:id (ports/dequeue-job! *queue* :default))))))))

(deftest ^:integration redis-queue-size-test
  (when-redis
    (dotimes [_ 5]
      (ports/enqueue-job! *queue* :default (make-job)))

    (testing "queue-size returns correct count"
      (is (= 5 (ports/queue-size *queue* :default))))))

;; =============================================================================
;; Job Store operations
;; =============================================================================

(deftest ^:integration redis-save-find-job-test
  (when-redis
    (let [test-job (make-job)]

      (ports/save-job! *store* test-job)

      (testing "find-job retrieves saved job"
        (let [found (ports/find-job *store* (:id test-job))]
          (is (= (:id test-job) (:id found)))
          (is (= :send-email (:job-type found)))
          (is (= :pending (:status found))))))))

(deftest ^:integration redis-update-job-status-test
  (when-redis
    (let [test-job (make-job {:max-retries 3})]

      (ports/save-job! *store* test-job)

      (testing "update to :running sets started-at"
        (ports/update-job-status! *store* (:id test-job) :running nil)
        (let [running (ports/find-job *store* (:id test-job))]
          (is (= :running (:status running)))
          (is (some? (:started-at running)))))

      (testing "update to :completed sets result and completed-at"
        (ports/update-job-status! *store* (:id test-job) :completed {:ok true})
        (let [done (ports/find-job *store* (:id test-job))]
          (is (= :completed (:status done)))
          (is (= {:ok true} (:result done)))
          (is (some? (:completed-at done))))))))

(deftest ^:integration redis-find-jobs-by-status-test
  (when-redis
    (let [job1 (make-job)
          job2 (make-job)]

      (ports/save-job! *store* job1)
      (ports/save-job! *store* job2)
      (ports/update-job-status! *store* (:id job1) :running nil)
      (ports/update-job-status! *store* (:id job1) :completed {:result "ok"})

      (testing "finds completed jobs"
        (let [completed (ports/find-jobs *store* {:status :completed})]
          (is (= 1 (count completed)))
          (is (= (:id job1) (:id (first completed)))))))))

;; =============================================================================
;; Scheduled jobs
;; =============================================================================

(deftest ^:integration redis-scheduled-jobs-test
  (when-redis
    (let [past-time   (.minusSeconds (Instant/now) 30)
          future-time (.plusSeconds (Instant/now) 300)
          due-job     (make-job {:execute-at past-time})
          future-job  (make-job {:execute-at future-time})]

      (ports/enqueue-job! *queue* :default due-job)
      (ports/enqueue-job! *queue* :default future-job)

      (testing "scheduled jobs are not in execution queue immediately"
        (is (zero? (ports/queue-size *queue* :default))))

      (testing "process-scheduled-jobs! moves due jobs to execution queue"
        (let [moved (ports/process-scheduled-jobs! *queue*)]
          (is (= 1 moved))
          (is (= 1 (ports/queue-size *queue* :default))))))))
