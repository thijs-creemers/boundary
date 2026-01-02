(ns boundary.platform.migrations.shell.locking.postgres
  "PostgreSQL advisory lock implementation for migration coordination.
   
   Responsibilities:
   - Acquire/release PostgreSQL advisory locks
   - Lock status checking
   - Force release (admin operations)
   
   IMPERATIVE SHELL: Contains database I/O operations.
   
   PostgreSQL Advisory Locks:
   - Session-level locks (released when session ends)
   - No deadlock detection needed (single lock key)
   - Automatically released on connection close
   - Lock key: Hash of 'schema_migrations' table name
   
   Example:
     (def lock (create-postgres-lock db-ctx))
     (when (ports/acquire-lock lock \"migration-process\" 30000)
       (try
         ;; Run migrations
         (finally
           (ports/release-lock lock \"migration-process\"))))"
  (:require [boundary.platform.migrations.ports :as ports]
            [next.jdbc :as jdbc])
  (:import [java.time Instant]))

;; -----------------------------------------------------------------------------
;; Lock Key Generation
;; -----------------------------------------------------------------------------

(def ^:private ^:const migration-lock-key
  "PostgreSQL advisory lock key for migrations.
   
   Uses a fixed positive integer key derived from 'schema_migrations'.
   PostgreSQL advisory locks use 64-bit integers.
   
   Value: 1234567890 (arbitrary but consistent)"
  1234567890)

(defn- lock-key
  "Get the advisory lock key for migrations.
   
   Returns:
     Long - PostgreSQL advisory lock key"
  []
  migration-lock-key)

;; -----------------------------------------------------------------------------
;; Lock Operations
;; -----------------------------------------------------------------------------

(defn- acquire-advisory-lock
  "Acquire PostgreSQL advisory lock with timeout.
   
   Args:
     db-ctx - Database context (next.jdbc connection/datasource)
     lock-key - Long advisory lock key
     timeout-ms - Timeout in milliseconds
     
   Returns:
     Boolean - true if lock acquired, false otherwise
     
   Note: Uses pg_try_advisory_lock for non-blocking acquisition"
  [db-ctx lock-key timeout-ms]
  (let [start-time (System/currentTimeMillis)
        deadline (+ start-time timeout-ms)]
    (loop []
      (let [result (jdbc/execute-one! 
                     db-ctx
                     ["SELECT pg_try_advisory_lock(?) AS acquired" lock-key])]
        (cond
          ;; Lock acquired successfully
          (:acquired result)
          true
          
          ;; Timeout exceeded
          (>= (System/currentTimeMillis) deadline)
          false
          
          ;; Retry after short wait
          :else
          (do
            (Thread/sleep 100)  ; Wait 100ms before retry
            (recur)))))))

(defn- release-advisory-lock
  "Release PostgreSQL advisory lock.
   
   Args:
     db-ctx - Database context
     lock-key - Long advisory lock key
     
   Returns:
     Boolean - true if lock released, false if not held"
  [db-ctx lock-key]
  (let [result (jdbc/execute-one! 
                 db-ctx
                 ["SELECT pg_advisory_unlock(?) AS released" lock-key])]
    (:released result)))

(defn- check-lock-held
  "Check if advisory lock is currently held.
   
   Args:
     db-ctx - Database context
     lock-key - Long advisory lock key
     
   Returns:
     Boolean - true if lock is held by any session"
  [db-ctx lock-key]
  (let [result (jdbc/execute-one!
                 db-ctx
                 ["SELECT COUNT(*) AS count 
                   FROM pg_locks 
                   WHERE locktype = 'advisory' 
                   AND objid = ?" 
                  lock-key])]
    (> (:count result 0) 0)))

(defn- force-release-all-locks
  "Force release all advisory locks with given key (admin operation).
   
   Args:
     db-ctx - Database context
     lock-key - Long advisory lock key
     
   Returns:
     Integer - Number of locks released
     
   Note: This terminates all sessions holding the lock"
  [db-ctx lock-key]
  (let [result (jdbc/execute!
                 db-ctx
                 ["SELECT pg_terminate_backend(pid) AS terminated
                   FROM pg_locks 
                   WHERE locktype = 'advisory' 
                   AND objid = ?" 
                  lock-key])]
    (count result)))

;; -----------------------------------------------------------------------------
;; Lock Status Tracking
;; -----------------------------------------------------------------------------

(defn- lock-status-info
  "Get detailed lock status information.
   
   Args:
     db-ctx - Database context
     lock-key - Long advisory lock key
     
   Returns:
     Map with:
     - :held? - Boolean, is lock currently held
     - :holder-pid - Process ID holding lock (nil if not held)
     - :acquired-at - When lock was acquired (nil if not held)"
  [db-ctx lock-key]
  (let [result (jdbc/execute-one!
                 db-ctx
                 ["SELECT 
                     pid,
                     pg_blocking_pids(pid) AS blocking_pids,
                     query_start
                   FROM pg_locks
                   LEFT JOIN pg_stat_activity USING (pid)
                   WHERE locktype = 'advisory'
                   AND objid = ?
                   LIMIT 1"
                  lock-key])]
    (if result
      {:held? true
       :holder-pid (:pid result)
       :acquired-at (:query_start result)}
      {:held? false
       :holder-pid nil
       :acquired-at nil})))

;; -----------------------------------------------------------------------------
;; Protocol Implementation
;; -----------------------------------------------------------------------------

(defrecord PostgresAdvisoryLock [db-ctx lock-holder-ref]
  ports/IMigrationLock
  
  (acquire-lock [_ holder-id timeout-ms]
    (let [key (lock-key)
          acquired? (acquire-advisory-lock db-ctx key timeout-ms)]
      (when acquired?
        (reset! lock-holder-ref {:holder-id holder-id
                                 :acquired-at (Instant/now)
                                 :lock-key key}))
      acquired?))
  
  (release-lock [_ holder-id]
    (let [current-holder @lock-holder-ref]
      (if (= holder-id (:holder-id current-holder))
        (let [key (lock-key)
              released? (release-advisory-lock db-ctx key)]
          (when released?
            (reset! lock-holder-ref nil))
          released?)
        false)))  ; Not the lock holder
  
  (check-lock-status [_]
    (let [key (lock-key)
          status (lock-status-info db-ctx key)
          current-holder @lock-holder-ref]
      (merge status
             {:local-holder (when current-holder
                             (:holder-id current-holder))})))
  
  (force-release-lock [_ admin-id]
    (let [key (lock-key)
          terminated-count (force-release-all-locks db-ctx key)]
      (reset! lock-holder-ref nil)
      {:force-released true
       :admin-id admin-id
       :sessions-terminated terminated-count
       :timestamp (Instant/now)})))

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(defn create-postgres-lock
  "Create PostgreSQL advisory lock implementation.
   
   Args:
     db-ctx - Database context (next.jdbc connection/datasource)
     
   Returns:
     IMigrationLock implementation
     
   Example:
     (def lock (create-postgres-lock db-ctx))
     (ports/acquire-lock lock \"process-1\" 30000)"
  [db-ctx]
  (->PostgresAdvisoryLock db-ctx (atom nil)))

;; -----------------------------------------------------------------------------
;; Utilities
;; -----------------------------------------------------------------------------

(defn check-advisory-lock-support
  "Check if PostgreSQL supports advisory locks.
   
   Args:
     db-ctx - Database context
     
   Returns:
     Boolean - true if advisory locks are supported"
  [db-ctx]
  (try
    (let [result (jdbc/execute-one! 
                   db-ctx
                   ["SELECT current_setting('server_version_num')::int AS version"])]
      ;; Advisory locks available in PostgreSQL 8.2+ (version >= 80200)
      (>= (:version result 0) 80200))
    (catch Exception _
      false)))

(defn list-active-migration-locks
  "List all active migration advisory locks.
   
   Args:
     db-ctx - Database context
     
   Returns:
     Vector of maps with lock information"
  [db-ctx]
  (let [key (lock-key)
        results (jdbc/execute!
                  db-ctx
                  ["SELECT 
                      pid,
                      usename AS username,
                      application_name,
                      client_addr,
                      state,
                      query_start AS acquired_at
                    FROM pg_locks
                    LEFT JOIN pg_stat_activity USING (pid)
                    WHERE locktype = 'advisory'
                    AND objid = ?"
                   key])]
    (mapv #(hash-map
             :holder-pid (:pid %)
             :username (:username %)
             :application (:application_name %)
             :client-addr (str (:client_addr %))
             :state (:state %)
             :acquired-at (:acquired_at %))
          results)))
