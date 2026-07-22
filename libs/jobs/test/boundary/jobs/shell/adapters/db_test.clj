(ns boundary.jobs.shell.adapters.db-test
  "Reliability + round-trip tests for the DB-backed job queue, on in-memory H2.
   Unlike the Redis adapter tests these need no external service, so they run in
   the default CI test database."
  (:require [clojure.test :refer [deftest testing is]]
            [next.jdbc :as jdbc]
            [boundary.jobs.ports :as ports]
            [boundary.jobs.shell.adapters.db :as db])
  (:import [java.time Instant]))

(defn- fresh-queue
  "A DbJobQueue over a private in-memory H2 db with the table created."
  [& {:keys [lease-ms] :or {lease-ms db/default-lease-ms}}]
  (let [ds (jdbc/get-datasource
            {:dbtype "h2:mem"
             ;; unique db name per call + keep it alive for the JVM
             :dbname (str "jobs_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})]
    (db/create-jobs-table! ds)
    (db/create-db-job-queue ds :lease-ms lease-ms)))

(defn- mk-job [& {:keys [priority queue] :or {priority :normal queue :default}}]
  {:id         (random-uuid)
   :job-type   :send-email
   :queue      queue
   :priority   priority
   :status     :pending
   :args       {:to "a@b.c"}
   :created-at (Instant/now)
   :updated-at (Instant/now)})

(deftest ^:integration enqueue-dequeue-round-trips-the-job
  (let [q   (fresh-queue)
        job (mk-job)]
    (is (= (:id job) (ports/enqueue-job! q :default job)))
    (is (= 1 (ports/queue-size q :default)))
    (testing "dequeue returns the job with keyword + instant fields intact"
      (let [d (ports/dequeue-job! q :default "w1")]
        (is (= (:id job) (:id d)))
        (is (= :send-email (:job-type d)))
        (is (= :default (:queue d)))
        (is (instance? Instant (:created-at d)))))
    (testing "a claimed job is no longer on the ready queue"
      (is (zero? (ports/queue-size q :default)))
      (is (nil? (ports/dequeue-job! q :default "w2"))))))

(deftest ^:integration dequeue-honours-priority-then-fifo
  (let [q (fresh-queue)
        low  (mk-job :priority :low)
        crit (mk-job :priority :critical)
        norm (mk-job :priority :normal)]
    (doseq [j [low norm crit]] (ports/enqueue-job! q :default j))
    (is (= (:id crit) (:id (ports/dequeue-job! q :default "w"))) "critical first")
    (is (= (:id norm) (:id (ports/dequeue-job! q :default "w"))) "then normal")
    (is (= (:id low)  (:id (ports/dequeue-job! q :default "w"))) "low last")))

(deftest ^:integration reliable-queue-reclaims-crashed-worker
  (testing "a job dequeued by a crashed worker is reclaimed once its lease expires"
    (let [q   (fresh-queue :lease-ms 40)
          job (mk-job)]
      (ports/enqueue-job! q :default job)
      ;; worker takes it but never acks (crash mid-job)
      (is (= (:id job) (:id (ports/dequeue-job! q :default "worker-dead"))))
      (is (zero? (ports/queue-size q :default)) "in-flight, not on ready")
      (Thread/sleep 60) ; let the 40ms lease expire
      (is (= 1 (:reclaimed (ports/reclaim-abandoned-jobs! q :default))))
      (is (= 1 (ports/queue-size q :default)) "job back on the ready queue")
      ;; a fresh worker picks it up again (at-least-once)
      (is (= (:id job) (:id (ports/dequeue-job! q :default "worker-live"))))
      (is (true? (ports/ack-job! q :default "worker-live" (:id job))))))

  (testing "an acked job is not reclaimed"
    (let [q   (fresh-queue :lease-ms 0)
          job (mk-job)]
      (ports/enqueue-job! q :default job)
      (ports/dequeue-job! q :default "worker-ack")
      (ports/ack-job! q :default "worker-ack" (:id job))
      (Thread/sleep 5)
      (is (zero? (:reclaimed (ports/reclaim-abandoned-jobs! q :default))))))

  (testing "a live worker's in-flight job (lease not expired) is not reclaimed"
    (let [q   (fresh-queue :lease-ms 60000)
          job (mk-job)]
      (ports/enqueue-job! q :default job)
      (ports/dequeue-job! q :default "worker-live")
      (is (zero? (:reclaimed (ports/reclaim-abandoned-jobs! q :default)))))))

(deftest ^:integration scheduled-jobs-become-ready-when-due
  (let [q    (fresh-queue)
        job  (mk-job)]
    (testing "a job scheduled in the future is not ready"
      (ports/schedule-job! q :default job (.plusSeconds (Instant/now) 3600))
      (is (zero? (ports/queue-size q :default)))
      (is (nil? (ports/dequeue-job! q :default "w"))))
    (testing "a due scheduled job is promoted to ready by process-scheduled-jobs!"
      (let [due (mk-job)]
        (ports/schedule-job! q :default due (.minusSeconds (Instant/now) 1))
        (is (= 1 (ports/process-scheduled-jobs! q)))
        (is (= (:id due) (:id (ports/dequeue-job! q :default "w"))))))))

(deftest ^:integration peek-delete-and-list-queues
  (let [q (fresh-queue)
        a (mk-job :queue :alpha)
        b (mk-job :queue :beta)]
    (ports/enqueue-job! q :alpha a)
    (ports/enqueue-job! q :beta b)
    (testing "peek returns the next ready job without claiming it"
      (is (= (:id a) (:id (ports/peek-job q :alpha))))
      (is (= 1 (ports/queue-size q :alpha)) "peek did not claim"))
    (testing "list-queues reports the distinct queues"
      (is (= #{:alpha :beta} (set (ports/list-queues q)))))
    (testing "delete-job! removes a job"
      (is (true? (ports/delete-job! q (:id a))))
      (is (false? (ports/delete-job! q (random-uuid))))
      (is (zero? (ports/queue-size q :alpha))))))
