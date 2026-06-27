(ns boundary.jobs.shell.jobs-hardening-test
  "BOU-88: jobs hardening.
   1. Missing-handler jobs are re-enqueued (bounded) rather than silently
      dead-lettered, so an instance that owns the handler can pick them up.
   2. Scheduled-job promotion is an atomic claim, so concurrent workers move a
      due scheduled job to an execution queue exactly once."
  (:require [boundary.jobs.core.job :as job]
            [boundary.jobs.ports :as ports]
            [boundary.jobs.shell.adapters.in-memory :as in-memory]
            [boundary.jobs.shell.worker :as worker]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]
           [java.util UUID]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private process-single-job! #'worker/process-single-job!)
(def ^:private create-worker-state #'worker/create-worker-state)

(defn- mk-job
  [overrides]
  (job/create-job (merge {:job-type :test-job :args {} :queue :default} overrides)
                  (UUID/randomUUID)
                  (Instant/now)))

;; =============================================================================
;; Gap 1 — missing-handler fail-fast (bounded re-enqueue → dead-letter)
;; =============================================================================

(deftest missing-handler-requeues-then-dead-letters
  (testing "no local handler: delayed re-enqueue up to the budget, then terminal DLQ"
    (let [{:keys [queue store]} (in-memory/create-in-memory-jobs-system)
          registry (worker/create-job-registry)        ; empty — no handlers
          ws       (create-worker-state :default)
          config   {:max-requeues 2 :requeue-delay-ms 0}
          the-job  (mk-job {:job-type :unhandled})]
      (ports/enqueue-job! queue :default the-job)

      ;; 1st pass: parked in the scheduled set (NOT the ready queue), budget +1
      (is (true? (process-single-job! config queue store registry ws)))
      (is (zero? (ports/queue-size queue :default))
          "removed from the ready queue — no tight-loop reacquire")
      (let [j (ports/find-job store (:id the-job))]
        (is (not= :failed (:status j)) "not dead-lettered yet")
        (is (= 1 (get-in j [:metadata :requeue-count]))))

      ;; promote the delayed job back to the ready queue, then 2nd pass: requeued again
      (Thread/sleep 5)
      (is (= 1 (ports/process-scheduled-jobs! queue)))
      (is (= 1 (ports/queue-size queue :default)))
      (is (true? (process-single-job! config queue store registry ws)))
      (is (= 2 (get-in (ports/find-job store (:id the-job)) [:metadata :requeue-count])))

      ;; 3rd pass: budget exhausted → terminal failure to the dead-letter queue
      (Thread/sleep 5)
      (is (= 1 (ports/process-scheduled-jobs! queue)))
      (is (true? (process-single-job! config queue store registry ws)))
      (let [j (ports/find-job store (:id the-job))]
        (is (= :failed (:status j)))
        (is (= "NoHandlerError" (get-in j [:error :type])))
        (is (re-find #"No handler registered" (get-in j [:error :message]))))
      (is (some #(= (:id the-job) (:id %)) (ports/failed-jobs store 10))
          "job landed in the dead-letter queue, not silently dropped")
      (is (zero? (ports/queue-size queue :default)) "not left on the queue"))))

(deftest handlerless-worker-does-not-burn-budget-on-idle-polls
  (testing "a parked re-enqueue is not reacquired by the same worker's subsequent polls"
    (let [{:keys [queue store]} (in-memory/create-in-memory-jobs-system)
          registry (worker/create-job-registry)        ; empty — no handlers
          ws       (create-worker-state :default)
          ;; Long delay so the job stays parked across the idle polls below.
          config   {:max-requeues 3 :requeue-delay-ms 60000}
          the-job  (mk-job {:job-type :unhandled})]
      (ports/enqueue-job! queue :default the-job)

      ;; First poll parks the job (delayed). Further polls must find the ready
      ;; queue empty and NOT reacquire it — so the budget is not burned in a loop
      ;; before a worker that owns the handler gets a chance to poll.
      (is (true? (process-single-job! config queue store registry ws)))
      (dotimes [_ 5]
        (is (nil? (process-single-job! config queue store registry ws))
            "idle poll finds no ready job, does not reacquire the parked one"))

      (let [j (ports/find-job store (:id the-job))]
        (is (= 1 (get-in j [:metadata :requeue-count])) "requeue budget untouched by idle polls")
        (is (not= :failed (:status j)) "job not prematurely dead-lettered")))))

(deftest handler-present-still-processes
  (testing "a job whose type has a handler is processed normally (no requeue path)"
    (let [{:keys [queue store]} (in-memory/create-in-memory-jobs-system)
          registry (worker/create-job-registry)
          ws       (create-worker-state :default)
          the-job  (mk-job {:job-type :handled})]
      (ports/register-handler! registry :handled (fn [_args] {:success? true :result :ok}))
      (ports/enqueue-job! queue :default the-job)
      (is (true? (process-single-job! {} queue store registry ws)))
      (is (= :completed (:status (ports/find-job store (:id the-job)))))
      (is (zero? (ports/queue-size queue :default))))))

;; =============================================================================
;; Gap 2 — scheduled-job atomic claim across concurrent workers
;; =============================================================================

(deftest scheduled-jobs-claimed-exactly-once-under-concurrency
  (testing "concurrent process-scheduled-jobs! promotes each due job exactly once"
    (let [{:keys [queue]} (in-memory/create-in-memory-jobs-system)
          n-jobs   200
          n-threads 8
          past     (.minusSeconds (Instant/now) 60)
          job-ids  (doall (for [_ (range n-jobs)]
                            (let [j (mk-job {:job-type :sched :execute-at past})]
                              (ports/enqueue-job! queue :default j)
                              (:id j))))
          latch    (CountDownLatch. 1)
          ;; Each thread waits on the latch, then races to promote due jobs.
          futures  (doall (for [_ (range n-threads)]
                            (future
                              (.await latch 5 TimeUnit/SECONDS)
                              (ports/process-scheduled-jobs! queue))))]
      (.countDown latch)                          ; start all threads together
      (let [moved-counts (mapv deref futures)
            total-moved  (reduce + moved-counts)
            ;; Drain the execution queue and collect promoted ids.
            drained      (loop [acc []]
                           (if-let [j (ports/dequeue-job! queue :default)]
                             (recur (conj acc (:id j)))
                             acc))]
        (is (= n-jobs total-moved)
            "exactly n-jobs promotions counted across all workers (no double-count)")
        (is (= n-jobs (count drained)) "every due job ended up in the execution queue")
        (is (= (set job-ids) (set drained)) "the right jobs were promoted")
        (is (= n-jobs (count (set drained))) "no job was promoted more than once")))))
