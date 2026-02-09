(ns boundary.jobs.shell.tenant-context-test
  "Tests for tenant-aware background job processing."
  (:require [boundary.jobs.core.job :as job]
            [boundary.jobs.ports :as ports]
            [boundary.jobs.shell.tenant-context :as tenant-jobs]
            [boundary.jobs.shell.worker :as worker]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn- create-mock-tenant-service
  "Create mock tenant service for testing."
  []
  (let [tenants (atom {"tenant-1" {:id "tenant-1"
                                   :slug "acme-corp"
                                   :schema-name "tenant_acme_corp"
                                   :status :active}
                      "tenant-2" {:id "tenant-2"
                                   :slug "globex"
                                   :schema-name "tenant_globex"
                                   :status :active}})]
    (reify
      boundary.tenant.ports/ITenantService
      (get-tenant [_this tenant-id]
        (get @tenants tenant-id))
      (get-tenant-by-slug [_this slug]
        (first (filter #(= slug (:slug %)) (vals @tenants))))
      (list-tenants [_this _options] (vec (vals @tenants)))
      (create-new-tenant [_this _tenant-input] nil)
      (update-existing-tenant [_this _tenant-id _update-data] nil)
      (delete-existing-tenant [_this _tenant-id] nil)
      (suspend-tenant [_this _tenant-id] nil)
      (activate-tenant [_this _tenant-id] nil))))

(defn- create-mock-db-context
  "Create mock database context for testing."
  []
  {:datasource (Object.)  ; Mock datasource
   :adapter :postgresql   ; Required by db-context validation
   :database-type :postgresql})

(defn- create-mock-job-queue
  "Create mock job queue for testing."
  []
  (let [queue (atom [])]
    (reify ports/IJobQueue
      (enqueue-job! [_this _queue-name job]
        (swap! queue conj job)
        (:id job))
      (schedule-job! [_this _queue-name job _execute-at]
        (swap! queue conj job)
        (:id job))
      (dequeue-job! [_this _queue-name]
        (let [job (first @queue)]
          (when job
            (swap! queue rest))
          job))
      (peek-job [_this _queue-name]
        (first @queue))
      (delete-job! [_this _job-id]
        (swap! queue (fn [q] (remove #(= (:id %) _job-id) q)))
        true)
      (queue-size [_this _queue-name]
        (count @queue))
      (list-queues [_this]
        [:default])
      (process-scheduled-jobs! [_this]
        0))))

;; =============================================================================
;; Unit Tests - Job Enqueueing
;; =============================================================================

(deftest ^:unit enqueue-tenant-job-test
  (testing "Enqueuing job with tenant context"
    (let [job-queue (create-mock-job-queue)
          tenant-id "tenant-1"
          job-id (tenant-jobs/enqueue-tenant-job!
                  job-queue
                  tenant-id
                  :send-email
                  {:to "user@example.com" :subject "Test"}
                  {:priority :high})]
      
      (is (uuid? job-id) "Should return job UUID")
      
      (let [queued-job (ports/peek-job job-queue :default)]
        (is (some? queued-job) "Job should be queued")
        (is (= :send-email (:job-type queued-job)))
        (is (= tenant-id (get-in queued-job [:metadata :tenant-id]))
            "Tenant ID should be stored in metadata")
        (is (= :high (:priority queued-job)))
        (is (= {:to "user@example.com" :subject "Test"} (:args queued-job))))))
  
  (testing "Enqueuing job with default options"
    (let [job-queue (create-mock-job-queue)
          job-id (tenant-jobs/enqueue-tenant-job!
                  job-queue
                  "tenant-2"
                  :process-upload
                  {:file-id "123"})]
      
      (let [queued-job (ports/peek-job job-queue :default)]
        (is (= :normal (:priority queued-job)) "Should use default priority")
        (is (= 3 (:max-retries queued-job)) "Should use default retry count")
        (is (= :default (:queue queued-job)) "Should use default queue")))))

;; =============================================================================
;; Unit Tests - Tenant Context Extraction
;; =============================================================================

(deftest ^:unit extract-tenant-context-test
  (testing "Extracting tenant context from job with valid tenant"
    (let [tenant-service (create-mock-tenant-service)
          job {:id (java.util.UUID/randomUUID)
               :job-type :send-email
               :metadata {:tenant-id "tenant-1"}}
          context (tenant-jobs/extract-tenant-context job tenant-service)]
      
      (is (= "tenant-1" (:tenant-id context)))
      (is (= "tenant_acme_corp" (:tenant-schema context)))
      (is (some? (:tenant-entity context)))
      (is (= "acme-corp" (get-in context [:tenant-entity :slug])))))
  
  (testing "Extracting context when tenant not found"
    (let [tenant-service (create-mock-tenant-service)
          job {:id (java.util.UUID/randomUUID)
               :job-type :send-email
               :metadata {:tenant-id "nonexistent"}}
          context (tenant-jobs/extract-tenant-context job tenant-service)]
      
      (is (= "nonexistent" (:tenant-id context)))
      (is (nil? (:tenant-schema context)) "Should have no schema")
      (is (nil? (:tenant-entity context)) "Should have no entity")))
  
  (testing "Extracting context from job without tenant"
    (let [tenant-service (create-mock-tenant-service)
          job {:id (java.util.UUID/randomUUID)
               :job-type :send-email
               :metadata {}}
          context (tenant-jobs/extract-tenant-context job tenant-service)]
      
      (is (nil? (:tenant-id context)))
      (is (nil? (:tenant-schema context)))
      (is (nil? (:tenant-entity context))))))

;; =============================================================================
;; Integration Tests - Tenant-Aware Job Processing
;; =============================================================================

(deftest ^:integration process-tenant-job-test
  (testing "Processing job with tenant context (non-PostgreSQL fallback)"
    (let [tenant-service (create-mock-tenant-service)
          ;; Use non-PostgreSQL to skip schema switching in tests
          db-ctx {:datasource (Object.)
                  :adapter :sqlite
                  :database-type :sqlite}
          executed-args (atom nil)
          executed-context (atom nil)
          
          ;; Handler that captures args and context
          handler-fn (fn [args ctx]
                      (reset! executed-args args)
                      (reset! executed-context ctx)
                      {:success? true :result {:rows 5}})
          
          job {:id (java.util.UUID/randomUUID)
               :job-type :send-email
               :args {:to "user@example.com" :subject "Test"}
               :metadata {:tenant-id "tenant-1"}}
          
          result (tenant-jobs/process-tenant-job!
                  job
                  handler-fn
                  db-ctx
                  tenant-service)]
      
      ;; Handler should be called with correct args
      (is (= {:to "user@example.com" :subject "Test"} @executed-args))
      
      ;; Handler should receive db-context
      (is (some? @executed-context))
      (is (= :sqlite (:database-type @executed-context)))
      
      ;; Result should indicate success
      (is (= true (:success? result)))
      (is (= {:rows 5} (:result result)))))
  
  (testing "Processing job without tenant context"
    (let [tenant-service (create-mock-tenant-service)
          ;; Use SQLite mock to skip schema switching in tests
          db-ctx {:datasource (Object.)
                  :adapter :sqlite
                  :database-type :sqlite}
          executed? (atom false)
          
          handler-fn (fn [args ctx]
                      (reset! executed? true)
                      {:success? true :result {}})
          
          job {:id (java.util.UUID/randomUUID)
               :job-type :cleanup
               :args {}
               :metadata {}}
          
          result (tenant-jobs/process-tenant-job!
                  job
                  handler-fn
                  db-ctx
                  tenant-service)]
      
      (is @executed? "Handler should be executed")
      (is (:success? result) "Should succeed without tenant")))
  
  (testing "Processing job with handler error"
    (let [tenant-service (create-mock-tenant-service)
          ;; Use SQLite mock to skip schema switching in tests
          db-ctx {:datasource (Object.)
                  :adapter :sqlite
                  :database-type :sqlite}
          
          handler-fn (fn [_args _ctx]
                      (throw (Exception. "Handler error")))
          
          job {:id (java.util.UUID/randomUUID)
               :job-type :send-email
               :args {}
               :metadata {:tenant-id "tenant-1"}}
          
          result (tenant-jobs/process-tenant-job!
                  job
                  handler-fn
                  db-ctx
                  tenant-service)]
      
      (is (false? (:success? result)))
      (is (some? (:error result)))
      (is (= "Handler error" (get-in result [:error :message]))))))

;; =============================================================================
;; Integration Tests - Handler Wrapper
;; =============================================================================

(deftest ^:integration wrap-handler-with-tenant-context-test
  (testing "Wrapping handler adds tenant context"
    (let [tenant-service (create-mock-tenant-service)
          ;; Use SQLite mock to skip schema switching in tests
          db-ctx {:datasource (Object.)
                  :adapter :sqlite
                  :database-type :sqlite}
          original-handler (fn [args ctx]
                            {:success? true
                             :result {:args args
                                     :has-tx (contains? ctx :tx)}})
          
          wrapped-handler (tenant-jobs/wrap-handler-with-tenant-context
                           original-handler
                           db-ctx
                           tenant-service)
          
          job {:id (java.util.UUID/randomUUID)
               :job-type :send-email
               :args {:to "test@example.com"}
               :metadata {:tenant-id "tenant-1"}}
          
          result (wrapped-handler job)]
      
      (is (:success? result))
      (is (= {:to "test@example.com"} (get-in result [:result :args])))))
  
  (testing "Wrapped handler handles errors"
    (let [tenant-service (create-mock-tenant-service)
          db-ctx (create-mock-db-context)
          original-handler (fn [_args _ctx]
                            (throw (Exception. "Test error")))
          
          wrapped-handler (tenant-jobs/wrap-handler-with-tenant-context
                           original-handler
                           db-ctx
                           tenant-service)
          
          job {:id (java.util.UUID/randomUUID)
               :job-type :send-email
               :args {}
               :metadata {:tenant-id "tenant-1"}}
          
          result (wrapped-handler job)]
      
      (is (false? (:success? result)))
      (is (some? (:error result))))))

;; =============================================================================
;; E2E Tests - Full Job Lifecycle with Tenant Context
;; =============================================================================

(deftest ^:integration full-job-lifecycle-test
  (testing "Complete job lifecycle with tenant context"
    (let [tenant-service (create-mock-tenant-service)
          ;; Use SQLite mock to skip schema switching in tests
          db-ctx {:datasource (Object.)
                  :adapter :sqlite
                  :database-type :sqlite}
          job-queue (create-mock-job-queue)
          
          ;; Track execution
          executions (atom [])
          
          ;; Handler that records execution
          handler-fn (fn [args ctx]
                      (swap! executions conj {:args args
                                              :tenant (get-in args [:metadata :tenant-id])})
                      {:success? true :result {:processed true}})
          
          ;; Enqueue jobs for different tenants
          job-id-1 (tenant-jobs/enqueue-tenant-job!
                    job-queue "tenant-1" :send-email
                    {:to "user1@example.com"})
          
          job-id-2 (tenant-jobs/enqueue-tenant-job!
                    job-queue "tenant-2" :send-email
                    {:to "user2@example.com"})]
      
      ;; Verify jobs are queued
      (is (= 2 (ports/queue-size job-queue :default)))
      
      ;; Process first job
      (let [job1 (ports/dequeue-job! job-queue :default)
            result1 (tenant-jobs/process-tenant-job!
                     job1 handler-fn db-ctx tenant-service)]
        (is (:success? result1))
        (is (= "tenant-1" (get-in job1 [:metadata :tenant-id]))))
      
      ;; Process second job
      (let [job2 (ports/dequeue-job! job-queue :default)
            result2 (tenant-jobs/process-tenant-job!
                     job2 handler-fn db-ctx tenant-service)]
        (is (:success? result2))
        (is (= "tenant-2" (get-in job2 [:metadata :tenant-id]))))
      
      ;; Verify both jobs executed
      (is (= 2 (count @executions)))
      
      ;; Verify queue is empty
      (is (= 0 (ports/queue-size job-queue :default))))))
