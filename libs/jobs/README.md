# Background Jobs Module

**Production-ready asynchronous job processing for Boundary Framework**

Similar to **Sidekiq** (Ruby) or **Celery** (Python), this module provides robust background job processing with:

- ✅ Distributed job queuing (Redis-backed)
- ✅ Priority queues (critical, high, normal, low)
- ✅ Scheduled jobs (run at specific time)
- ✅ Automatic retries with exponential backoff
- ✅ Dead letter queue for failed jobs
- ✅ Job monitoring and statistics
- ✅ Pure functional core (FC/IS pattern)
- ✅ Pluggable adapters (Redis, in-memory)

---

## Table of Contents

- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
- [Usage Examples](#usage-examples)
- [Configuration](#configuration)
- [Job Handlers](#job-handlers)
- [Monitoring](#monitoring)
- [Production Deployment](#production-deployment)
- [API Reference](#api-reference)

---

## Quick Start

### 1. Add Redis Dependency

```clojure
;; deps.edn
{:deps {redis.clients/jedis {:mvn/version "5.2.0"}}}
```

### 2. Create Job Queue

```clojure
(require '[boundary.jobs.shell.adapters.redis :as redis-jobs])

;; Create Redis connection pool
(def redis-pool
  (redis-jobs/create-redis-pool
    {:host "localhost"
     :port 6379}))

;; Create job queue
(def job-queue (redis-jobs/create-redis-job-queue redis-pool))
(def job-store (redis-jobs/create-redis-job-store redis-pool))
```

### 3. Define Job Handler

```clojure
(require '[boundary.jobs.ports :as ports])

(defn send-email-handler
  "Job handler for sending emails."
  [job-args]
  (try
    (let [{:keys [to subject body]} job-args]
      ;; Send email logic here
      (println "Sending email to" to)
      {:success? true
       :result {:sent-at (java.time.Instant/now)
                :message-id "msg-123"}})
    (catch Exception e
      {:success? false
       :error {:message (.getMessage e)
               :type (.getName (.getClass e))}})))

;; Register handler
(def job-registry (atom {}))
(swap! job-registry assoc :send-email send-email-handler)
```

### 4. Enqueue Jobs

```clojure
(require '[boundary.jobs.core.job :as job])

;; Create and enqueue immediate job
(let [job-input {:job-type :send-email
                 :args {:to "user@example.com"
                        :subject "Welcome!"
                        :body "Thanks for signing up."}
                 :priority :high}
      new-job (job/create-job job-input (java.util.UUID/randomUUID))]

  (ports/enqueue-job! job-queue :default new-job))

;; Schedule job for future execution
(let [job-input {:job-type :send-reminder
                 :args {:user-id "123"}
                 :priority :normal}
      execute-at (.plusSeconds (java.time.Instant/now) 3600)  ; 1 hour from now
      new-job (job/schedule-job job-input (java.util.UUID/randomUUID) execute-at)]

  (ports/enqueue-job! job-queue :default new-job))
```

### 5. Process Jobs

```clojure
(defn process-next-job!
  "Process one job from queue."
  [job-queue job-store job-registry]
  (when-let [job (ports/dequeue-job! job-queue :default)]
    (let [handler (get @job-registry (:job-type job))]
      (if handler
        (let [started-job (job/start-job job)
              _ (ports/save-job! job-store started-job)
              result (handler (:args job))]

          (if (:success? result)
            (let [completed-job (job/complete-job started-job (:result result))]
              (ports/save-job! job-store completed-job))
            (let [failed-job (job/fail-job started-job (:error result))]
              (ports/save-job! job-store failed-job))))

        (println "No handler registered for" (:job-type job))))))

;; Process jobs in a loop
(future
  (loop []
    (process-next-job! job-queue job-store job-registry)
    (Thread/sleep 1000)  ; Poll every second
    (recur)))
```

---

## Core Concepts

### Job Lifecycle

```
1. Created (pending) → 2. Enqueued → 3. Dequeued → 4. Running → 5. Completed/Failed
                                                         ↓
                                                    6. Retry (if failed)
                                                         ↓
                                                    7. Dead Letter (max retries)
```

**Job States:**
- **pending**: Job is queued, waiting to be processed
- **running**: Job is currently being processed
- **completed**: Job completed successfully
- **failed**: Job failed after all retries
- **retrying**: Job failed but will be retried
- **cancelled**: Job was cancelled before execution

### Priority Levels

Jobs can have different priorities:

- **critical**: Processed first (use sparingly!)
- **high**: Important jobs (notifications, payments)
- **normal**: Standard priority (default)
- **low**: Background tasks (cleanup, analytics)

### Retry Behavior

Failed jobs are automatically retried with exponential backoff:

```
Retry 1: 1 second delay
Retry 2: 2 seconds delay
Retry 3: 4 seconds delay
Retry 4: 8 seconds delay
...
Max: 60 seconds delay
```

**Configuration:**
```clojure
{:max-retries 3                ; Total retry attempts (default: 3)
 :backoff-strategy :exponential ; :linear, :exponential, or :constant
 :initial-delay-ms 1000        ; Initial retry delay (default: 1 second)
 :max-delay-ms 60000           ; Max retry delay (default: 60 seconds)
 :jitter true}                 ; Add random jitter (default: true)
```

---

## Usage Examples

### Example 1: Send Welcome Email

```clojure
(defn enqueue-welcome-email!
  "Enqueue job to send welcome email to new user."
  [job-queue user]
  (let [job-input {:job-type :send-email
                   :args {:to (:email user)
                          :subject "Welcome to Boundary!"
                          :body (str "Hi " (:name user) ", welcome!")
                          :template :welcome}
                   :priority :high
                   :metadata {:user-id (:id user)}}
        new-job (job/create-job job-input (java.util.UUID/randomUUID))]

    (ports/enqueue-job! job-queue :emails new-job)))
```

### Example 2: Process Uploads

```clojure
(defn enqueue-image-processing!
  "Enqueue job to process uploaded image."
  [job-queue upload-id file-path]
  (let [job-input {:job-type :process-image
                   :args {:upload-id upload-id
                          :file-path file-path
                          :sizes [:thumbnail :medium :large]}
                   :priority :normal
                   :max-retries 5}  ; Retry up to 5 times
        new-job (job/create-job job-input (java.util.UUID/randomUUID))]

    (ports/enqueue-job! job-queue :media new-job)))
```

### Example 3: Scheduled Reports

```clojure
(defn schedule-daily-report!
  "Schedule daily report generation."
  [job-queue]
  (let [tomorrow-8am (-> (java.time.LocalDateTime/now)
                         (.plusDays 1)
                         (.withHour 8)
                         (.withMinute 0)
                         (.atZone (java.time.ZoneId/systemDefault))
                         (.toInstant))
        job-input {:job-type :generate-report
                   :args {:report-type :daily-summary
                          :recipients ["admin@example.com"]}
                   :priority :normal}
        new-job (job/schedule-job job-input (java.util.UUID/randomUUID) tomorrow-8am)]

    (ports/enqueue-job! job-queue :reports new-job)))
```

### Example 4: Bulk Operations

```clojure
(defn enqueue-bulk-export!
  "Enqueue job for bulk data export."
  [job-queue user-id filters]
  (let [job-input {:job-type :export-data
                   :args {:user-id user-id
                          :filters filters
                          :format :csv
                          :email-when-done true}
                   :priority :low  ; Low priority for bulk operations
                   :max-retries 1}  ; Don't retry long-running jobs
        new-job (job/create-job job-input (java.util.UUID/randomUUID))]

    (ports/enqueue-job! job-queue :bulk-operations new-job)))
```

---

## Configuration

### Redis Configuration

```clojure
(def redis-config
  {:host "localhost"              ; Redis host
   :port 6379                     ; Redis port (default: 6379)
   :password "secret"             ; Redis password (optional)
   :database 0                    ; Redis database number (default: 0)
   :max-total 20                  ; Max connections (default: 20)
   :max-idle 10                   ; Max idle connections (default: 10)
   :min-idle 2                    ; Min idle connections (default: 2)
   :timeout 2000})                ; Connection timeout ms (default: 2000)

(def pool (redis-jobs/create-redis-pool redis-config))
```

### Production Configuration

```clojure
;; config/prod.edn
{:boundary/jobs
 {:redis {:host #env REDIS_HOST
          :port #long #env [REDIS_PORT 6379]
          :password #env REDIS_PASSWORD
          :max-total 50
          :max-idle 20}

  :workers {:count 5                  ; Number of worker processes
            :queues [:critical        ; Queues to process (in order)
                     :default
                     :low-priority]
            :poll-interval 1000       ; Poll interval in ms
            :shutdown-timeout 30000}  ; Graceful shutdown timeout

  :scheduled-processor
  {:enabled true
   :interval 5000}}}  ; Check for scheduled jobs every 5 seconds
```

---

## Job Handlers

### Handler Function Signature

```clojure
(defn my-job-handler
  "Job handler function.

  Args:
    job-args - Map of job arguments

  Returns:
    Result map with:
      :success? - Boolean indicating success/failure
      :result - Result data (if successful)
      :error - Error map (if failed) with:
               :message - Error message
               :type - Error type
               :stacktrace - Stack trace (optional)"
  [job-args]
  {:success? true
   :result {:completed-at (java.time.Instant/now)}})
```

### Example Handlers

**Send Email:**
```clojure
(defn send-email-handler [job-args]
  (try
    (let [{:keys [to subject body]} job-args
          result (email/send! to subject body)]
      {:success? true
       :result {:message-id (:id result)
                :sent-at (java.time.Instant/now)}})
    (catch Exception e
      {:success? false
       :error {:message (.getMessage e)
               :type "EmailError"
               :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace e))}})))
```

**Process Payment:**
```clojure
(defn process-payment-handler [job-args]
  (try
    (let [{:keys [order-id amount payment-method]} job-args
          result (payment/charge! order-id amount payment-method)]

      (if (:success result)
        {:success? true
         :result {:transaction-id (:transaction-id result)
                  :status "completed"}}
        {:success? false
         :error {:message (:error-message result)
                 :type "PaymentFailed"}}))
    (catch Exception e
      {:success? false
       :error {:message (.getMessage e)
               :type "PaymentError"}})))
```

**Generate Report:**
```clojure
(defn generate-report-handler [job-args]
  (try
    (let [{:keys [report-type start-date end-date]} job-args
          report-data (reports/generate report-type start-date end-date)
          file-path (reports/save-to-file report-data)]

      {:success? true
       :result {:file-path file-path
                :rows (count report-data)
                :generated-at (java.time.Instant/now)}})
    (catch Exception e
      {:success? false
       :error {:message (.getMessage e)
               :type "ReportGenerationError"}})))
```

---

## Monitoring

### Job Statistics

```clojure
(require '[boundary.jobs.ports :as ports])

;; Get overall statistics
(ports/job-stats job-stats)
;; =>
{:total-processed 1500
 :total-failed 23
 :total-succeeded 1477
 :queues [{:queue-name :default
           :size 5
           :processed-total 1200
           :failed-total 15
           :succeeded-total 1185}
          {:queue-name :emails
           :size 0
           :processed-total 300
           :failed-total 8
           :succeeded-total 292}]
 :workers [{:id "worker-1" :status :running}
           {:id "worker-2" :status :idle}]}

;; Get queue-specific statistics
(ports/queue-stats job-stats :default)
;; =>
{:queue-name :default
 :size 5
 :processed-total 1200
 :failed-total 15
 :succeeded-total 1185
 :avg-duration-ms 250.5}
```

### Failed Jobs

```clojure
;; Get failed jobs from dead letter queue
(ports/failed-jobs job-store 10)
;; => Vector of 10 most recent failed jobs

;; Retry a failed job
(ports/retry-job! job-store job-id)
```

### Job History

```clojure
;; Get recent jobs of specific type
(ports/job-history job-stats :send-email 20)
;; => 20 most recent email jobs with results
```

---

## Production Deployment

### 1. Start Workers

```clojure
(ns my-app.workers
  (:require [boundary.jobs.shell.adapters.redis :as redis-jobs]
            [boundary.jobs.ports :as ports]
            [integrant.core :as ig]))

(defmethod ig/init-key :my-app/job-workers
  [_ config]
  (let [pool (redis-jobs/create-redis-pool (:redis config))
        job-queue (redis-jobs/create-redis-job-queue pool)
        job-store (redis-jobs/create-redis-job-store pool)
        job-registry (atom {})
        worker-count (:worker-count config 5)

        ;; Register all job handlers
        _ (do
            (swap! job-registry assoc :send-email send-email-handler)
            (swap! job-registry assoc :process-image process-image-handler)
            (swap! job-registry assoc :generate-report generate-report-handler))

        ;; Start worker threads
        workers (doall
                 (for [i (range worker-count)]
                   (future
                     (loop []
                       (try
                         (process-next-job! job-queue job-store job-registry)
                         (Thread/sleep (:poll-interval config 1000))
                         (catch Exception e
                           (log/error e "Worker error")))
                       (recur)))))]

    {:pool pool
     :queue job-queue
     :store job-store
     :registry job-registry
     :workers workers}))

(defmethod ig/halt-key! :my-app/job-workers
  [_ {:keys [pool workers]}]
  ;; Gracefully shutdown workers
  (doseq [worker workers]
    (future-cancel worker))
  ;; Close Redis pool
  (redis-jobs/close-redis-pool! pool))
```

### 2. Monitor with HTTP Endpoints

```clojure
(defn job-stats-handler
  "GET /api/admin/jobs/stats"
  [job-stats]
  (fn [request]
    {:status 200
     :body (ports/job-stats job-stats)}))

(defn failed-jobs-handler
  "GET /api/admin/jobs/failed"
  [job-store]
  (fn [request]
    (let [limit (or (get-in request [:query-params :limit]) 20)]
      {:status 200
       :body {:jobs (ports/failed-jobs job-store limit)}})))

(defn retry-job-handler
  "POST /api/admin/jobs/:id/retry"
  [job-store]
  (fn [request]
    (let [job-id (get-in request [:path-params :id])]
      (if-let [job (ports/retry-job! job-store (java.util.UUID/fromString job-id))]
        {:status 200
         :body {:message "Job queued for retry"
                :job job}}
        {:status 404
         :body {:error "Job not found"}}))))
```

### 3. Docker Deployment

**Dockerfile:**
```dockerfile
FROM clojure:temurin-17-tools-deps

WORKDIR /app
COPY . .

# Build uberjar
RUN clojure -T:build jar

# Run workers
CMD ["java", "-jar", "target/app-standalone.jar", "workers"]
```

**docker-compose.yml:**
```yaml
version: '3.8'

services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data

  workers:
    build: .
    depends_on:
      - redis
    environment:
      REDIS_HOST: redis
      REDIS_PORT: 6379
      WORKER_COUNT: 5
    deploy:
      replicas: 3  # Run 3 worker containers

volumes:
  redis-data:
```

---

## API Reference

### Core Functions

**boundary.jobs.core.job**

- `(create-job job-input job-id)` - Create new job
- `(schedule-job job-input job-id execute-at)` - Create scheduled job
- `(start-job job)` - Mark job as running
- `(complete-job job result)` - Mark job as completed
- `(fail-job job error)` - Mark job as failed
- `(cancel-job job)` - Cancel job
- `(calculate-retry-delay retry-count config)` - Calculate retry delay
- `(prepare-retry job retry-config)` - Prepare job for retry

### Ports (Protocols)

**IJobQueue** - Job queue operations
- `enqueue-job!` - Add job to queue
- `schedule-job!` - Schedule job for future
- `dequeue-job!` - Get next job
- `delete-job!` - Remove job
- `queue-size` - Get queue size

**IJobStore** - Job persistence
- `save-job!` - Save job to store
- `find-job` - Find job by ID
- `update-job-status!` - Update job status
- `failed-jobs` - Get failed jobs
- `retry-job!` - Retry failed job

**IJobStats** - Job statistics
- `job-stats` - Get overall stats
- `queue-stats` - Get queue stats
- `job-history` - Get job history

---

## Best Practices

### 1. Keep Jobs Small
Jobs should complete in seconds, not minutes.

**Good:**
```clojure
{:job-type :send-email
 :args {:to "user@example.com" :template :welcome}}
```

**Bad:**
```clojure
{:job-type :process-all-users  ; Too broad!
 :args {}}
```

### 2. Use Appropriate Priorities
- **critical**: Only for truly urgent jobs (< 1% of jobs)
- **high**: Important but not urgent (payments, notifications)
- **normal**: Default for most jobs
- **low**: Background cleanup, analytics

### 3. Set Realistic Retry Limits
```clojure
;; Short-lived external API calls
{:max-retries 5}

;; Long-running data processing
{:max-retries 1}  ; Don't retry

;; Idempotent operations
{:max-retries 10}
```

### 4. Add Metadata for Debugging
```clojure
{:job-type :process-order
 :args {:order-id "123"}
 :metadata {:user-id "user-456"
            :source "mobile-app"
            :api-version "2.0"}}
```

---

## Troubleshooting

### Jobs Not Processing

**Check workers are running:**
```clojure
(ports/job-stats job-stats)
;; Check :workers count
```

**Check Redis connection:**
```bash
redis-cli ping
# Should return PONG
```

### High Failure Rate

**Check job handler errors:**
```clojure
(ports/failed-jobs job-store 10)
;; Inspect :error field
```

**Enable debug logging:**
```clojure
;; In logback.xml
<logger name="boundary.jobs" level="DEBUG"/>
```

### Memory Issues

**Limit concurrent jobs:**
```clojure
;; Reduce worker count
{:worker-count 2}  ; Instead of 10

;; Increase poll interval
{:poll-interval 5000}  ; 5 seconds instead of 1
```

---

## License

Part of Boundary Framework - See main LICENSE file.

---

**Next:** See [Examples](../examples/) for complete working applications using background jobs.
