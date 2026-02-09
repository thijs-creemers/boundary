(ns boundary.jobs.shell.tenant-context
  "Tenant context support for background job processing.
   
   This namespace provides utilities for executing background jobs within
   tenant-specific database schema contexts. It integrates with the multi-tenant
   architecture by extracting tenant-id from job metadata and setting the
   appropriate PostgreSQL search_path before job execution.
   
   Key Features:
   - Extract tenant-id from job metadata
   - Set database search_path to tenant schema
   - Execute job handlers in tenant context
   - Automatic fallback to public schema if no tenant
   
   Usage:
     (require '[boundary.jobs.shell.tenant-context :as tenant-jobs])
     
     ;; Enqueue job with tenant context
     (tenant-jobs/enqueue-tenant-job! job-queue tenant-id :send-email
                                      {:to \"user@example.com\"})
     
     ;; Process job with tenant context
     (tenant-jobs/process-tenant-job! job handler-fn db-ctx tenant-service)
   
   See ADR-004 for architecture details (lines 525-554)."
  (:require [boundary.jobs.ports :as ports]
            [boundary.tenant.ports :as tenant-ports]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Job Creation with Tenant Context
;; =============================================================================

(defn enqueue-tenant-job!
  "Enqueue a background job with tenant context.
   
   This stores the tenant-id in job metadata so the worker can execute
   the job in the correct tenant schema context.
   
   Args:
     job-queue: IJobQueue implementation
     tenant-id: Tenant UUID
     job-type: Job type keyword (e.g., :send-email, :process-upload)
     args: Job arguments map
     options: Optional map with:
              :priority - :critical, :high, :normal (default), :low
              :max-retries - Retry count (default: 3)
              :queue-name - Queue name (default: :default)
   
   Returns:
     Job UUID
   
   Example:
     (enqueue-tenant-job! job-queue
                          tenant-uuid
                          :send-email
                          {:to \"user@example.com\"
                           :subject \"Welcome!\"
                           :body \"Thanks for signing up.\"}
                          {:priority :high})
   
   Notes:
     - Tenant-id is stored in job :metadata
     - Jobs without tenant-id run in public schema
     - Worker must have access to db-context and tenant-service"
  ([job-queue tenant-id job-type args]
   (enqueue-tenant-job! job-queue tenant-id job-type args {}))
  ([job-queue tenant-id job-type args {:keys [priority max-retries queue-name]
                                       :or {priority :normal
                                            max-retries 3
                                            queue-name :default}}]
   (let [job-input {:job-type job-type
                    :args args
                    :metadata {:tenant-id tenant-id}  ; Store tenant context
                    :priority priority
                    :max-retries max-retries
                    :queue queue-name}
         job-id (java.util.UUID/randomUUID)]
     
     (log/info "Enqueueing tenant job"
               {:job-type job-type
                :tenant-id tenant-id
                :priority priority
                :queue queue-name})
     
     ;; Note: create-job is in boundary.jobs.core.job, but we're in shell
     ;; We use the IJobQueue port directly
     (ports/enqueue-job! job-queue queue-name
                         (assoc job-input :id job-id
                                :status :pending
                                :created-at (java.time.Instant/now)
                                :updated-at (java.time.Instant/now)
                                :retry-count 0))
     job-id)))

;; =============================================================================
;; Tenant Context Extraction
;; =============================================================================

(defn extract-tenant-context
  "Extract tenant context from job metadata.
   
   Args:
     job: Job map with :metadata
     tenant-service: ITenantService implementation
   
   Returns:
     Map with:
       :tenant-id - Tenant UUID (or nil if not found)
       :tenant-schema - Tenant schema name (or nil)
       :tenant-entity - Full tenant entity (or nil)
   
   Notes:
     - Returns nil values if job has no tenant-id
     - Logs warning if tenant-id present but tenant not found
     - Jobs without tenant run in public schema (multi-tenant optional)"
  [job tenant-service]
  (if-let [tenant-id (get-in job [:metadata :tenant-id])]
    (do
      (log/debug "Extracting tenant context for job"
                 {:job-id (:id job)
                  :tenant-id tenant-id})
      
      (if-let [tenant (tenant-ports/get-tenant tenant-service tenant-id)]
        {:tenant-id tenant-id
         :tenant-schema (:schema-name tenant)
         :tenant-entity tenant}
        (do
          (log/warn "Tenant not found for job, using public schema"
                    {:job-id (:id job)
                     :tenant-id tenant-id})
          {:tenant-id tenant-id
           :tenant-schema nil
           :tenant-entity nil})))
    
    ;; No tenant-id in metadata - not a tenant-scoped job
    (do
      (log/debug "Job has no tenant context, using public schema"
                 {:job-id (:id job)})
      {:tenant-id nil
       :tenant-schema nil
       :tenant-entity nil})))

;; =============================================================================
;; Tenant-Aware Job Execution
;; =============================================================================

(defn process-tenant-job!
  "Process a job with tenant schema context.
   
   This wraps job handler execution to:
   1. Extract tenant-id from job metadata
   2. Lookup tenant entity to get schema name
   3. Set PostgreSQL search_path to tenant schema
   4. Execute job handler with database connection
   5. Restore search_path after execution
   
   Args:
     job: Job map with :id, :job-type, :args, :metadata
     handler-fn: Job handler function (fn [args db-ctx] -> result)
     db-ctx: Database context with :datasource, :database-type
     tenant-service: ITenantService implementation
   
   Returns:
     Result map with :success?, :result or :error
   
   Example:
     (process-tenant-job! job
                          (fn [args db-ctx]
                            ;; Handler logic here
                            {:success? true :result {:rows 5}})
                          db-ctx
                          tenant-service)
   
   Notes:
     - Handler receives db-ctx as second argument
     - Handler should use db-ctx for database operations
     - search_path is automatically managed
     - Falls back to public schema if no tenant
     - Only works with PostgreSQL (other databases use public schema)
   
   Security:
     - Tenant isolation enforced via search_path
     - Each job execution gets isolated transaction
     - Schema validation before execution
     - Automatic cleanup on error"
  [job handler-fn db-ctx tenant-service]
  (let [job-id (:id job)
        job-type (:job-type job)
        {:keys [tenant-id tenant-schema]} (extract-tenant-context job tenant-service)]
    
    (try
      (if (and tenant-schema (= (:database-type db-ctx) :postgresql))
        ;; Execute in tenant schema context (PostgreSQL only)
        (do
          (log/info "Executing job in tenant schema"
                    {:job-id job-id
                     :job-type job-type
                     :tenant-id tenant-id
                     :schema tenant-schema})
          
          ;; Use with-tenant-schema to set search_path
          ;; Note: This requires boundary.tenant.shell.provisioning
          (let [provisioning-ns 'boundary.tenant.shell.provisioning
                with-tenant-schema-fn (requiring-resolve
                                       (symbol (str provisioning-ns)
                                               "with-tenant-schema"))]
            
            (if with-tenant-schema-fn
              ;; Call handler within tenant schema context
              (with-tenant-schema-fn db-ctx tenant-schema
                (fn [tx]
                  ;; Handler receives args and db context with transaction
                  (handler-fn (:args job) (assoc db-ctx :tx tx))))
              
              ;; Fallback if with-tenant-schema not available
              (do
                (log/warn "with-tenant-schema not found, using public schema"
                          {:job-id job-id})
                (handler-fn (:args job) db-ctx)))))
        
        ;; No tenant or non-PostgreSQL - use public schema
        (do
          (when tenant-id
            (log/debug "Job has tenant but not PostgreSQL, using default schema"
                       {:job-id job-id
                        :tenant-id tenant-id
                        :database-type (:database-type db-ctx)}))
          
          (handler-fn (:args job) db-ctx)))
      
      (catch Exception e
        (log/error e "Error processing tenant job"
                   {:job-id job-id
                    :job-type job-type
                    :tenant-id tenant-id
                    :error (.getMessage e)})
        {:success? false
         :error {:message (.getMessage e)
                 :type (-> e class .getName)
                 :stacktrace (with-out-str (.printStackTrace e))}}))))

;; =============================================================================
;; Handler Wrapper for Existing Workers
;; =============================================================================

(defn wrap-handler-with-tenant-context
  "Wrap an existing job handler to add tenant context support.
   
   This is a convenience function for integrating tenant context into
   existing job handlers without modifying them.
   
   Args:
     handler-fn: Original handler (fn [args] -> result)
     db-ctx: Database context
     tenant-service: ITenantService implementation
   
   Returns:
     Wrapped handler (fn [job] -> result)
   
   Example:
     (def my-handler
       (wrap-handler-with-tenant-context
         (fn [args db-ctx]
           ;; Original handler logic
           {:success? true :result {}})
         db-ctx
         tenant-service))
     
     ;; Register wrapped handler
     (ports/register-handler! registry :send-email my-handler)
   
   Notes:
     - Wrapped handler expects job (not just args)
     - Tenant context extracted and applied automatically
     - Original handler must accept db-ctx as second arg"
  [handler-fn db-ctx tenant-service]
  (fn [job]
    (process-tenant-job! job handler-fn db-ctx tenant-service)))
