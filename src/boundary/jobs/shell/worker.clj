(ns boundary.jobs.shell.worker
  "Background job worker implementation.

   Workers poll job queues, execute jobs, and handle retries. This module
   provides a production-ready worker with:
   - Graceful shutdown
   - Configurable polling intervals
   - Automatic retry handling
   - Scheduled job processing
   - Job handler registry
   - Comprehensive error handling"
  (:require [boundary.jobs.ports :as ports]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Job Handler Registry
;; =============================================================================

(defrecord JobRegistry [handlers]  ; atom: job-type -> handler-fn
  ports/IJobRegistry

  (register-handler! [_ job-type handler-fn]
    (swap! handlers assoc job-type handler-fn)
    (log/info "Registered job handler" {:job-type job-type})
    job-type)

  (unregister-handler! [_ job-type]
    (let [existed? (contains? @handlers job-type)]
      (swap! handlers dissoc job-type)
      (when existed?
        (log/info "Unregistered job handler" {:job-type job-type}))
      existed?))

  (get-handler [_ job-type]
    (get @handlers job-type))

  (list-handlers [_this]
    (vec (keys @handlers))))

(defn create-job-registry
  "Create a new job handler registry.

   Returns:
     JobRegistry implementing IJobRegistry"
  []
  (->JobRegistry (atom {})))

;; =============================================================================
;; Worker State
;; =============================================================================

(defrecord WorkerState
           [id              ; UUID
            queue-name      ; Keyword
            running?        ; atom: boolean
            current-job     ; atom: job or nil
            processed-count ; atom: integer
            failed-count    ; atom: integer
            started-at      ; Instant
            last-heartbeat]) ; atom: Instant

(defn- create-worker-state
  "Create initial worker state."
  [queue-name]
  (->WorkerState
   (UUID/randomUUID)
   queue-name
   (atom true)
   (atom nil)
   (atom 0)
   (atom 0)
   (Instant/now)
   (atom (Instant/now))))

;; =============================================================================
;; Job Execution
;; =============================================================================

(defn- execute-job-handler
  "Execute job handler function with error handling.

   Args:
     handler-fn - Function (fn [args] -> {:success? boolean :result any :error map})
     job-args - Job arguments map

   Returns:
     Result map with :success?, :result, :error"
  [handler-fn job-args]
  (try
    (let [result (handler-fn job-args)]
      (if (:success? result)
        result
        (assoc result :success? false)))
    (catch Exception e
      {:success? false
       :error {:message (.getMessage e)
               :type (-> e class .getName)
               :stacktrace (with-out-str (.printStackTrace e))}})))

(defn- process-single-job!
  "Process a single job from queue.

   Args:
     queue - IJobQueue implementation
     store - IJobStore implementation
     registry - IJobRegistry implementation
     worker-state - WorkerState

   Returns:
     true if job was processed, false if queue was empty"
  [queue store registry worker-state]
  (when-let [job (ports/dequeue-job! queue (:queue-name worker-state))]
    (let [job-id (:id job)
          job-type (:job-type job)]
      (reset! (:current-job worker-state) job)
      (log/info "Processing job" {:job-id job-id :job-type job-type})

      (try
        ;; Mark job as running
        (ports/update-job-status! store job-id :running nil)

        ;; Get handler
        (if-let [handler-fn (ports/get-handler registry job-type)]
          (let [result (execute-job-handler handler-fn (:args job))]
            (if (:success? result)
              (do
                ;; Success
                (ports/update-job-status! store job-id :completed (:result result))
                (swap! (:processed-count worker-state) inc)
                (log/info "Job completed successfully" {:job-id job-id}))
              (do
                ;; Handler returned failure
                (ports/update-job-status! store job-id :failed (:error result))
                (swap! (:failed-count worker-state) inc)
                (log/warn "Job failed" {:job-id job-id :error (:error result)}))))

          ;; No handler registered
          (let [error {:message (str "No handler registered for job type: " job-type)
                       :type "NoHandlerError"}]
            (ports/update-job-status! store job-id :failed error)
            (swap! (:failed-count worker-state) inc)
            (log/error "No handler for job type" {:job-id job-id :job-type job-type})))

        (catch Exception e
          ;; Unexpected error during processing
          (let [error {:message (.getMessage e)
                       :type (-> e class .getName)
                       :stacktrace (with-out-str (.printStackTrace e))}]
            (ports/update-job-status! store job-id :failed error)
            (swap! (:failed-count worker-state) inc)
            (log/error e "Unexpected error processing job" {:job-id job-id})))

        (finally
          (reset! (:current-job worker-state) nil)
          (reset! (:last-heartbeat worker-state) (Instant/now))))

      true)))

;; =============================================================================
;; Scheduled Job Processing
;; =============================================================================

(defn- process-scheduled-jobs!
  "Process scheduled jobs that are due for execution.

   Args:
     queue - IJobQueue implementation

   Returns:
     Number of jobs moved to execution queues"
  [queue]
  (try
    (ports/process-scheduled-jobs! queue)
    (catch Exception e
      (log/error e "Error processing scheduled jobs")
      0)))

;; =============================================================================
;; Worker Loop
;; =============================================================================

(defn- worker-loop
  "Main worker loop that polls queue and processes jobs.

   Args:
     config - Worker configuration map
     queue - IJobQueue implementation
     store - IJobStore implementation
     registry - IJobRegistry implementation
     worker-state - WorkerState"
  [config queue store registry worker-state]
  (let [poll-interval-ms (or (:poll-interval-ms config) 1000)
        scheduled-interval-ms (or (:scheduled-interval-ms config) 5000)
        last-scheduled-check (atom (Instant/now))]

    (while @(:running? worker-state)
      (try
        ;; Process scheduled jobs periodically
        (let [now (Instant/now)
              elapsed-ms (.toMillis (java.time.Duration/between @last-scheduled-check now))]
          (when (>= elapsed-ms scheduled-interval-ms)
            (let [moved (process-scheduled-jobs! queue)]
              (when (pos? moved)
                (log/debug "Moved scheduled jobs to execution queues" {:count moved})))
            (reset! last-scheduled-check now)))

        ;; Process next job from queue
        (let [processed? (process-single-job! queue store registry worker-state)]
          (when-not processed?
            ;; No jobs available, sleep before polling again
            (Thread/sleep poll-interval-ms)))

        (catch InterruptedException _e
          (log/info "Worker interrupted, shutting down" {:worker-id (:id worker-state)})
          (reset! (:running? worker-state) false))

        (catch Exception e
          (log/error e "Unexpected error in worker loop" {:worker-id (:id worker-state)})
          (Thread/sleep poll-interval-ms)))))  ; Sleep before retrying

  (log/info "Worker stopped" {:worker-id (:id worker-state)
                              :processed (:processed-count worker-state)
                              :failed (:failed-count worker-state)}))

;; =============================================================================
;; Worker Management
;; =============================================================================

(defrecord Worker
           [state         ; WorkerState
            thread        ; Thread running worker-loop
            queue         ; IJobQueue
            store         ; IJobStore
            registry      ; IJobRegistry
            config]       ; Configuration map

  ports/IJobWorker

  (process-job! [_ job]
    ;; Manual job processing (for testing or direct invocation)
    (let [_job-id (:id job)
          job-type (:job-type job)]
      (if-let [handler-fn (ports/get-handler registry job-type)]
        (execute-job-handler handler-fn (:args job))
        {:success? false
         :error {:message (str "No handler registered for job type: " job-type)
                 :type "NoHandlerError"}})))

  (start-worker! [_this]
    (:id state))  ; Already started in create-worker

  (stop-worker! [_ worker-id]
    (when (= worker-id (:id state))
      (log/info "Stopping worker" {:worker-id worker-id})
      (reset! (:running? state) false)
      (when thread
        (.join thread 5000))  ; Wait up to 5 seconds for graceful shutdown
      true))

  (worker-status [_ worker-id]
    (when (= worker-id (:id state))
      {:id (:id state)
       :queue-name (:queue-name state)
       :status (if @(:running? state) :running :stopped)
       :current-job @(:current-job state)
       :processed-count @(:processed-count state)
       :failed-count @(:failed-count state)
       :started-at (:started-at state)
       :last-heartbeat @(:last-heartbeat state)})))

(defn create-worker
  "Create and start a background job worker.

   Args:
     config - Worker configuration map with:
              :queue-name - Queue to process (keyword, required)
              :poll-interval-ms - Milliseconds between polls (default 1000)
              :scheduled-interval-ms - Check scheduled jobs interval (default 5000)
     queue - IJobQueue implementation
     store - IJobStore implementation
     registry - IJobRegistry implementation

   Returns:
     Worker implementing IJobWorker"
  [config queue store registry]
  (let [queue-name (or (:queue-name config) :default)
        worker-state (create-worker-state queue-name)
        worker (->Worker worker-state nil queue store registry config)
        thread (Thread. #(worker-loop config queue store registry worker-state)
                        (str "job-worker-" (:id worker-state)))]

    (.setDaemon thread true)
    (.start thread)

    (log/info "Started worker" {:worker-id (:id worker-state) :queue-name queue-name})

    (assoc worker :thread thread)))

(defn create-worker-pool
  "Create multiple workers for parallel job processing.

   Args:
     config - Worker pool configuration with:
              :queue-name - Queue to process
              :worker-count - Number of workers (default 4)
              :poll-interval-ms - Polling interval
              :scheduled-interval-ms - Scheduled check interval
     queue - IJobQueue implementation
     store - IJobStore implementation
     registry - IJobRegistry implementation

   Returns:
     Vector of Worker instances"
  [config queue store registry]
  (let [worker-count (or (:worker-count config) 4)]
    (log/info "Creating worker pool" {:worker-count worker-count :queue-name (:queue-name config)})
    (vec (repeatedly worker-count #(create-worker config queue store registry)))))

(defn stop-worker-pool!
  "Stop all workers in a pool gracefully.

   Args:
     workers - Vector of Worker instances"
  [workers]
  (log/info "Stopping worker pool" {:worker-count (count workers)})
  (doseq [worker workers]
    (ports/stop-worker! worker (:id (:state worker)))))

(defn worker-pool-status
  "Get status of all workers in a pool.

   Args:
     workers - Vector of Worker instances

   Returns:
     Vector of worker status maps"
  [workers]
  (mapv #(ports/worker-status % (:id (:state %))) workers))
