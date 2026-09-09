(ns wagoe.jobs-runtime-test
  "Does `java -jar wagoe.jar worker` actually process a job?

   Until BOU-418 the answer was no, and nothing said so: jobs' wiring was a
   settings passthrough, so worker mode booted \"the full system minus HTTP\"
   and then sat there. The defect was invisible from any unit test — every
   piece worked, none of them was connected.

   So this boots the documented mode through `main/worker-ig-config` and asks
   the graph to do the thing it claims: run a job."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.config :as config]
            [wagoe.jobs.ports :as job-ports]
            [wagoe.main :as main]
            [wagoe.system-config :as sys-config]))

(defn- jobs-config
  "The test profile with jobs and push switched on."
  []
  (-> (config/load-config {:profile :test})
      (assoc-in [:active :wagoe/jobs] {:provider :memory
                                       :workers  {:count 1 :poll-interval-ms 25}})
      (assoc-in [:active :wagoe/push] {:enabled? true})))

(defn- with-worker-system
  [f]
  (let [system (ig/init (main/worker-ig-config (sys-config/ig-config (jobs-config))))]
    (try (f system) (finally (ig/halt! system)))))

(defn- wait-for
  "Poll `pred` for up to `ms`. Returns its value, or nil on timeout."
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 25)
            (recur))))))

(deftest ^:integration worker-mode-boots-a-job-runtime
  (with-worker-system
    (fn [system]
      (testing "the queue, store, registry and workers are all in the graph"
        (is (some? (:wagoe/job-queue system))    "no job queue")
        (is (some? (:wagoe/job-store system))    "no job store")
        (is (some? (:wagoe/job-registry system)) "no job registry")
        (is (= 1 (count (:wagoe/job-workers system)))
            "the worker pool is empty — nothing would process a queued job"))

      (testing "and there is no HTTP surface, because this is worker mode"
        (is (nil? (:wagoe/http-server system)))))))

(deftest ^:integration a-module-that-contributes-handlers-reaches-the-registry
  ;; Push contributes `:wagoe.push/job-handlers`; jobs registers whatever the
  ;; modules contributed. Before this, `schedule-push!` enqueued into a nil
  ;; queue and the handler map had no registry to land in — a scheduled push
  ;; vanished with no error at all.
  (with-worker-system
    (fn [system]
      (let [registered (set (job-ports/list-handlers (:wagoe/job-registry system)))]
        (is (contains? registered :push/send)
            (str "push's handlers did not reach the registry; it holds "
                 (pr-str (sort registered))))
        (is (contains? registered :push/broadcast))))))

(deftest ^:integration an-enqueued-job-is-actually-executed
  ;; The end of the loop, and the part no unit test can reach: a job put on the
  ;; queue component is picked up by the worker component and run against the
  ;; registry component.
  ;;
  ;; The handler is registered here rather than reusing push's, deliberately:
  ;; push's handler reads device rows, and this is a statement about the job
  ;; runtime, not about push's delivery logic (which has its own tests). What
  ;; the previous test pins is that a module's handlers arrive in this same
  ;; registry — together they cover the path end to end.
  (with-worker-system
    (fn [system]
      (let [ran     (promise)
            queue   (:wagoe/job-queue system)
            job-id  (random-uuid)]
        (job-ports/register-handler! (:wagoe/job-registry system)
                                     :test/probe
                                     (fn [args] (deliver ran args) {:ok true}))
        (job-ports/enqueue-job! queue :default
                                {:id          job-id
                                 :job-type    :test/probe
                                 :queue       :default
                                 :priority    :normal
                                 :status      :pending
                                 :args        {:marker "BOU-418"}
                                 :retry-count 0
                                 :max-retries 3
                                 :created-at  (java.time.Instant/now)
                                 :updated-at  (java.time.Instant/now)})
        (is (= {:marker "BOU-418"} (deref ran 5000 ::timeout))
            "the worker never ran the enqueued job")
        (is (some? (wait-for 5000 #(when (zero? (job-ports/queue-size queue :default)) true)))
            "the job was run but never left the queue")))))
