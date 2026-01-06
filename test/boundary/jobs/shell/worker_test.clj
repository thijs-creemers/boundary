(ns boundary.jobs.shell.worker-test
  "Tests for background job worker."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.jobs.shell.worker :as worker]
            [boundary.jobs.shell.adapters.in-memory :as in-memory]
            [boundary.jobs.core.job :as job]
            [boundary.jobs.ports :as ports])
  (:import [java.time Instant Duration]
           [java.util UUID]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *system* nil)
(def ^:dynamic *registry* nil)

(defn with-clean-system
  "Fixture that creates a fresh system for each test."
  [f]
  (let [system (in-memory/create-in-memory-jobs-system)
        registry (worker/create-job-registry)]
    (binding [*system* system
              *registry* registry]
      (f))))

(use-fixtures :each with-clean-system)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn create-test-job
  "Create a test job with defaults."
  ([]
   (create-test-job {}))
  ([overrides]
   (job/create-job
    (merge {:job-type :test-job
            :args {:foo "bar"}
            :queue :default}
           overrides)
    (UUID/randomUUID))))

(defn wait-for
  "Wait for a condition to become true, up to timeout-ms."
  [condition-fn timeout-ms]
  (let [start (System/currentTimeMillis)
        end (+ start timeout-ms)]
    (loop []
      (cond
        (condition-fn) true
        (> (System/currentTimeMillis) end) false
        :else (do (Thread/sleep 50) (recur))))))

;; =============================================================================
;; Job Registry Tests
;; =============================================================================

(deftest register-handler-test
  (testing "Register job handler"
    (let [handler-fn (fn [args] {:success? true :result "ok"})
          job-type (ports/register-handler! *registry* :send-email handler-fn)]
      (is (= :send-email job-type))
      (is (= handler-fn (ports/get-handler *registry* :send-email))))))

(deftest unregister-handler-test
  (testing "Unregister job handler"
    (let [handler-fn (fn [args] {:success? true})]
      (ports/register-handler! *registry* :send-email handler-fn)
      (is (true? (ports/unregister-handler! *registry* :send-email)))
      (is (nil? (ports/get-handler *registry* :send-email)))
      ;; Unregistering again returns false
      (is (false? (ports/unregister-handler! *registry* :send-email))))))

(deftest list-handlers-test
  (testing "List all registered handlers"
    (ports/register-handler! *registry* :send-email (fn [_] {:success? true}))
    (ports/register-handler! *registry* :generate-report (fn [_] {:success? true}))
    (ports/register-handler! *registry* :process-upload (fn [_] {:success? true}))

    (let [handlers (set (ports/list-handlers *registry*))]
      (is (= #{:send-email :generate-report :process-upload} handlers)))))

;; =============================================================================
;; Worker Tests
;; =============================================================================

(deftest worker-processes-jobs-test
  (testing "Worker processes jobs from queue"
    (let [queue (:queue *system*)
          store (:store *system*)
          results (atom [])
          handler-fn (fn [args]
                       (swap! results conj args)
                       {:success? true :result (str "Processed: " (:value args))})]

      ;; Register handler
      (ports/register-handler! *registry* :test-job handler-fn)

      ;; Create and enqueue jobs
      (let [job1 (create-test-job {:args {:value "job1"}})
            job2 (create-test-job {:args {:value "job2"}})]
        (ports/enqueue-job! queue :default job1)
        (ports/enqueue-job! queue :default job2)

        ;; Start worker
        (let [worker-instance (worker/create-worker
                               {:queue-name :default :poll-interval-ms 100}
                               queue store *registry*)]
          (try
            ;; Wait for jobs to be processed
            (is (wait-for #(= 2 (count @results)) 5000))

            ;; Verify both jobs were processed
            (is (= #{{:value "job1"} {:value "job2"}} (set @results)))

            ;; Verify jobs are marked as completed
            (let [completed1 (ports/find-job store (:id job1))
                  completed2 (ports/find-job store (:id job2))]
              (is (= :completed (:status completed1)))
              (is (= :completed (:status completed2)))
              (is (= "Processed: job1" (:result completed1)))
              (is (= "Processed: job2" (:result completed2))))

            (finally
              (ports/stop-worker! worker-instance (:id (:state worker-instance))))))))))

(deftest worker-handles-job-failure-test
  (testing "Worker handles job failures and retries"
    (let [queue (:queue *system*)
          store (:store *system*)
          attempt-count (atom 0)
          handler-fn (fn [args]
                       (swap! attempt-count inc)
                       {:success? false
                        :error {:message "Simulated failure"
                                :type "TestError"}})]

      ;; Register failing handler
      (ports/register-handler! *registry* :test-job handler-fn)

      ;; Create job with max-retries set to 0 so it fails immediately
      (let [test-job (create-test-job {:max-retries 0})]
        (ports/enqueue-job! queue :default test-job)

        ;; Start worker
        (let [worker-instance (worker/create-worker
                               {:queue-name :default :poll-interval-ms 100}
                               queue store *registry*)]
          (try
            ;; Wait for job to be processed
            (is (wait-for #(pos? @attempt-count) 5000))

            ;; Verify job failed
            (let [failed-job (ports/find-job store (:id test-job))]
              (is (= :failed (:status failed-job)))
              (is (= "Simulated failure" (get-in failed-job [:error :message]))))

            (finally
              (ports/stop-worker! worker-instance (:id (:state worker-instance))))))))))

(deftest worker-handles-missing-handler-test
  (testing "Worker handles jobs with no registered handler"
    (let [queue (:queue *system*)
          store (:store *system*)
          test-job (create-test-job {:job-type :unregistered-job
                                     :max-retries 0})]  ; No retries so it goes to :failed

      (ports/enqueue-job! queue :default test-job)

      ;; Start worker (no handler registered)
      (let [worker-instance (worker/create-worker
                             {:queue-name :default :poll-interval-ms 100}
                             queue store *registry*)]
        (try
          ;; Wait for job to be processed
          (Thread/sleep 500)

          ;; Verify job failed with appropriate error
          (let [failed-job (ports/find-job store (:id test-job))]
            (is (= :failed (:status failed-job)))
            (is (re-find #"No handler registered" (get-in failed-job [:error :message]))))

          (finally
            (ports/stop-worker! worker-instance (:id (:state worker-instance)))))))))

(deftest worker-processes-priority-jobs-test
  (testing "Worker processes jobs in priority order"
    (let [queue (:queue *system*)
          store (:store *system*)
          processed-order (atom [])
          handler-fn (fn [args]
                       (swap! processed-order conj (:priority args))
                       {:success? true})]

      ;; Register handler
      (ports/register-handler! *registry* :test-job handler-fn)

      ;; Enqueue jobs in random order
      (ports/enqueue-job! queue :default (create-test-job {:priority :low :args {:priority :low}}))
      (ports/enqueue-job! queue :default (create-test-job {:priority :normal :args {:priority :normal}}))
      (ports/enqueue-job! queue :default (create-test-job {:priority :high :args {:priority :high}}))
      (ports/enqueue-job! queue :default (create-test-job {:priority :critical :args {:priority :critical}}))

      ;; Start worker
      (let [worker-instance (worker/create-worker
                             {:queue-name :default :poll-interval-ms 50}
                             queue store *registry*)]
        (try
          ;; Wait for all jobs to be processed
          (is (wait-for #(= 4 (count @processed-order)) 5000))

          ;; Verify processing order: critical, high, normal, low
          (is (= [:critical :high :normal :low] @processed-order))

          (finally
            (ports/stop-worker! worker-instance (:id (:state worker-instance)))))))))

(deftest worker-status-test
  (testing "Worker status tracking"
    (let [queue (:queue *system*)
          store (:store *system*)
          handler-fn (fn [args]
                       (Thread/sleep 100)  ; Simulate work
                       {:success? true})]

      ;; Register handler
      (ports/register-handler! *registry* :test-job handler-fn)

      ;; Start worker
      (let [worker-instance (worker/create-worker
                             {:queue-name :test-queue :poll-interval-ms 100}
                             queue store *registry*)
            worker-id (:id (:state worker-instance))]
        (try
          (Thread/sleep 200)  ; Let worker start

          ;; Check status
          (let [status (ports/worker-status worker-instance worker-id)]
            (is (= worker-id (:id status)))
            (is (= :test-queue (:queue-name status)))
            (is (= :running (:status status)))
            (is (some? (:started-at status))))

          (finally
            (ports/stop-worker! worker-instance worker-id)))))))

(deftest worker-pool-test
  (testing "Worker pool processes jobs in parallel"
    (let [queue (:queue *system*)
          store (:store *system*)
          processed (atom #{})
          handler-fn (fn [args]
                       (Thread/sleep 50)  ; Simulate work
                       (swap! processed conj (:id args))
                       {:success? true})]

      ;; Register handler
      (ports/register-handler! *registry* :test-job handler-fn)

      ;; Create multiple jobs
      (dotimes [i 10]
        (ports/enqueue-job! queue :default
                            (create-test-job {:args {:id i}})))

      ;; Create worker pool with 3 workers
      (let [workers (worker/create-worker-pool
                     {:queue-name :default
                      :worker-count 3
                      :poll-interval-ms 50}
                     queue store *registry*)]
        (try
          ;; Wait for all jobs to be processed
          (is (wait-for #(= 10 (count @processed)) 5000))

          ;; Verify all jobs were processed
          (is (= #{0 1 2 3 4 5 6 7 8 9} @processed))

          ;; Check pool status
          (let [statuses (worker/worker-pool-status workers)]
            (is (= 3 (count statuses)))
            (is (every? #(= :running (:status %)) statuses)))

          (finally
            (worker/stop-worker-pool! workers))))))

  (deftest worker-graceful-shutdown-test
    (testing "Worker shuts down gracefully"
      (let [queue (:queue *system*)
            store (:store *system*)
            handler-fn (fn [args]
                         (Thread/sleep 100)
                         {:success? true})]

      ;; Register handler
        (ports/register-handler! *registry* :test-job handler-fn)

      ;; Start worker
        (let [worker-instance (worker/create-worker
                               {:queue-name :default :poll-interval-ms 100}
                               queue store *registry*)
              worker-id (:id (:state worker-instance))]

        ;; Enqueue a job
          (ports/enqueue-job! queue :default (create-test-job))

          (Thread/sleep 200)  ; Let worker start processing

        ;; Stop worker
          (let [stopped? (ports/stop-worker! worker-instance worker-id)]
            (is (true? stopped?))

          ;; Verify worker is stopped
            (let [status (ports/worker-status worker-instance worker-id)]
              (is (= :stopped (:status status)))))))))

  (deftest manual-job-processing-test
    (testing "Manual job processing with process-job!"
      (let [queue (:queue *system*)
            store (:store *system*)
            handler-fn (fn [args] {:success? true :result (str "Processed: " (:value args))})]

      ;; Register handler
        (ports/register-handler! *registry* :test-job handler-fn)

      ;; Create worker (but don't rely on automatic processing)
        (let [worker-instance (worker/create-worker
                               {:queue-name :default :poll-interval-ms 10000}  ; Long interval
                               queue store *registry*)
              test-job (create-test-job {:args {:value "manual"}})]

          (try
          ;; Process job manually
            (let [result (ports/process-job! worker-instance test-job)]
              (is (true? (:success? result)))
              (is (= "Processed: manual" (:result result))))

            (finally
              (ports/stop-worker! worker-instance (:id (:state worker-instance)))))))))

  (deftest worker-processes-scheduled-jobs-test
    (testing "Worker processes scheduled jobs when due"
      (let [queue (:queue *system*)
            store (:store *system*)
            processed (atom false)
            handler-fn (fn [args]
                         (reset! processed true)
                         {:success? true})]

      ;; Register handler
        (ports/register-handler! *registry* :test-job handler-fn)

      ;; Schedule a job for immediate execution (past time)
        (let [past-time (.minusSeconds (Instant/now) 5)
              scheduled-job (create-test-job {:execute-at past-time})]
          (ports/enqueue-job! queue :default scheduled-job)

        ;; Verify job is in scheduled queue, not execution queue
          (is (zero? (ports/queue-size queue :default)))

        ;; Start worker (should process scheduled jobs)
          (let [worker-instance (worker/create-worker
                                 {:queue-name :default
                                  :poll-interval-ms 100
                                  :scheduled-interval-ms 200}
                                 queue store *registry*)]
            (try
            ;; Wait for job to be processed
              (is (wait-for #(deref processed) 5000))

            ;; Verify job was completed
              (let [completed-job (ports/find-job store (:id scheduled-job))]
                (is (= :completed (:status completed-job))))

              (finally
                (ports/stop-worker! worker-instance (:id (:state worker-instance)))))))))))
