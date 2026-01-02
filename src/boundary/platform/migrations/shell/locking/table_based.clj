(ns boundary.platform.migrations.shell.locking.table-based
  "Table-based lock implementation for databases without advisory locks.
   
   Responsibilities:
   - Acquire/release locks using migration_locks table
   - Lock status checking
   - Stale lock cleanup
   
   IMPERATIVE SHELL: Contains database I/O operations.
   
   Table-Based Locking:
   - Uses migration_locks table with timestamps
   - Manual cleanup required (no automatic release)
   - Stale lock detection via timeout
   - Suitable for SQLite, H2, and other databases without advisory locks
   
   Lock Schema:
     CREATE TABLE migration_locks (
       lock_key VARCHAR(255) PRIMARY KEY,
       holder_id VARCHAR(255) NOT NULL,
       acquired_at TIMESTAMP NOT NULL,
       expires_at TIMESTAMP NOT NULL
     );
   
   Example:
     (def lock (create-table-lock db-ctx))
     (when (ports/acquire-lock lock \"migration-process\" 30000)
       (try
         ;; Run migrations
         (finally
           (ports/release-lock lock \"migration-process\"))))"
  (:require [boundary.platform.migrations.ports :as ports]
            [honey.sql :as hsql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))

;; -----------------------------------------------------------------------------
;; Lock Key
;; -----------------------------------------------------------------------------

(def ^:private ^:const migration-lock-key
  "Lock key for migration coordination.
   
   All migration processes use the same lock key to ensure mutual exclusion."
  "schema_migrations_lock")

(defn- lock-key
  "Get the lock key for migrations.
   
   Returns:
     String - Lock key identifier"
  []
  migration-lock-key)

;; -----------------------------------------------------------------------------
;; Schema Management
;; -----------------------------------------------------------------------------

(defn- ensure-lock-table-exists
  "Ensure migration_locks table exists.
   
   Args:
     db-ctx - Database context
     
   Returns:
     Boolean - true if table exists or was created"
  [db-ctx]
  (try
    (jdbc/execute-one!
      db-ctx
      ["CREATE TABLE IF NOT EXISTS migration_locks (
          lock_key VARCHAR(255) PRIMARY KEY,
          holder_id VARCHAR(255) NOT NULL,
          acquired_at TIMESTAMP NOT NULL,
          expires_at TIMESTAMP NOT NULL
        )"])
    true
    (catch Exception e
      ;; Table might already exist (different DB error messages)
      (if (re-find #"(?i)(already exists|duplicate)" (.getMessage e))
        true
        (throw e)))))

;; -----------------------------------------------------------------------------
;; Time Utilities
;; -----------------------------------------------------------------------------

(defn- now
  "Get current timestamp.
   
   Returns:
     Instant - Current time"
  []
  (Instant/now))

(defn- add-milliseconds
  "Add milliseconds to a timestamp.
   
   Args:
     instant - Base timestamp
     ms - Milliseconds to add
     
   Returns:
     Instant - New timestamp"
  [instant ms]
  (.plus instant ms ChronoUnit/MILLIS))

(defn- expired?
  "Check if a timestamp has expired.
   
   Args:
     expires-at - Expiration timestamp
     
   Returns:
     Boolean - true if expired"
  [expires-at]
  (.isBefore expires-at (now)))

;; -----------------------------------------------------------------------------
;; Lock Operations
;; -----------------------------------------------------------------------------

(defn- try-insert-lock
  "Attempt to insert lock record (atomic operation).
   
   Args:
     db-ctx - Database context
     key - Lock key
     holder-id - Lock holder identifier
     expires-at - Lock expiration time
     
   Returns:
     Boolean - true if lock acquired (insert succeeded)"
  [db-ctx key holder-id expires-at]
  (try
    (let [sql (hsql/format {:insert-into :migration_locks
                           :values [{:lock_key key
                                    :holder_id holder-id
                                    :acquired_at (now)
                                    :expires_at expires-at}]})]
      (jdbc/execute-one! db-ctx sql)
      true)
    (catch Exception e
      ;; Primary key violation means lock already held
      (if (re-find #"(?i)(primary key|unique|duplicate)" (.getMessage e))
        false
        (throw e)))))

(defn- delete-lock
  "Delete lock record.
   
   Args:
     db-ctx - Database context
     key - Lock key
     holder-id - Lock holder identifier (optional, for safety)
     
   Returns:
     Boolean - true if lock was deleted"
  [db-ctx key holder-id]
  (let [sql (hsql/format {:delete-from :migration_locks
                         :where [:and
                                [:= :lock_key key]
                                [:= :holder_id holder-id]]})]
    (pos? (:next.jdbc/update-count
           (jdbc/execute-one! db-ctx sql
                             {:return-keys false})
           0))))

(defn- get-current-lock
  "Retrieve current lock record.
   
   Args:
     db-ctx - Database context
     key - Lock key
     
   Returns:
     Map with lock information or nil if not locked"
  [db-ctx key]
  (let [sql (hsql/format {:select [:*]
                         :from [:migration_locks]
                         :where [:= :lock_key key]})]
    (jdbc/execute-one! db-ctx sql
                      {:builder-fn rs/as-unqualified-lower-maps})))

(defn- cleanup-stale-locks
  "Remove expired locks.
   
   Args:
     db-ctx - Database context
     key - Lock key
     
   Returns:
     Integer - Number of stale locks removed"
  [db-ctx key]
  (let [sql (hsql/format {:delete-from :migration_locks
                         :where [:and
                                [:= :lock_key key]
                                [:< :expires_at (now)]]})]
    (or (:next.jdbc/update-count
          (jdbc/execute-one! db-ctx sql
                            {:return-keys false}))
        0)))

(defn- acquire-lock-with-retry
  "Attempt to acquire lock with retry logic.
   
   Args:
     db-ctx - Database context
     key - Lock key
     holder-id - Lock holder identifier
     timeout-ms - Total timeout in milliseconds
     
   Returns:
     Boolean - true if lock acquired within timeout"
  [db-ctx key holder-id timeout-ms]
  (let [start-time (System/currentTimeMillis)
        deadline (+ start-time timeout-ms)
        expires-at (add-milliseconds (now) (+ timeout-ms 60000))] ; +1 min safety margin
    
    ;; Ensure lock table exists
    (ensure-lock-table-exists db-ctx)
    
    ;; Clean up any stale locks first
    (cleanup-stale-locks db-ctx key)
    
    (loop []
      (cond
        ;; Try to acquire lock
        (try-insert-lock db-ctx key holder-id expires-at)
        true
        
        ;; Timeout exceeded
        (>= (System/currentTimeMillis) deadline)
        false
        
        ;; Retry after cleaning stale locks
        :else
        (do
          (cleanup-stale-locks db-ctx key)
          (Thread/sleep 100)  ; Wait 100ms before retry
          (recur))))))

;; -----------------------------------------------------------------------------
;; Lock Status
;; -----------------------------------------------------------------------------

(defn- lock-status-info
  "Get detailed lock status information.
   
   Args:
     db-ctx - Database context
     key - Lock key
     
   Returns:
     Map with:
     - :held? - Boolean, is lock currently held
     - :holder-id - Lock holder identifier (nil if not held)
     - :acquired-at - When lock was acquired (nil if not held)
     - :expires-at - When lock expires (nil if not held)
     - :is-stale? - Boolean, is lock expired"
  [db-ctx key]
  (cleanup-stale-locks db-ctx key)  ; Clean before checking
  (if-let [lock (get-current-lock db-ctx key)]
    {:held? true
     :holder-id (:holder_id lock)
     :acquired-at (:acquired_at lock)
     :expires-at (:expires_at lock)
     :is-stale? (expired? (:expires_at lock))}
    {:held? false
     :holder-id nil
     :acquired-at nil
     :expires-at nil
     :is-stale? false}))

;; -----------------------------------------------------------------------------
;; Protocol Implementation
;; -----------------------------------------------------------------------------

(defrecord TableBasedLock [db-ctx lock-holder-ref]
  ports/IMigrationLock
  
  (acquire-lock [_ holder-id timeout-ms]
    (let [key (lock-key)
          acquired? (acquire-lock-with-retry db-ctx key holder-id timeout-ms)]
      (when acquired?
        (reset! lock-holder-ref {:holder-id holder-id
                                :acquired-at (now)
                                :lock-key key}))
      acquired?))
  
  (release-lock [_ holder-id]
    (let [current-holder @lock-holder-ref]
      (if (= holder-id (:holder-id current-holder))
        (let [key (lock-key)
              released? (delete-lock db-ctx key holder-id)]
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
          sql (hsql/format {:delete-from :migration_locks
                           :where [:= :lock_key key]})
          deleted-count (or (:next.jdbc/update-count
                             (jdbc/execute-one! db-ctx sql
                                               {:return-keys false}))
                           0)]
      (reset! lock-holder-ref nil)
      {:force-released true
       :admin-id admin-id
       :locks-removed deleted-count
       :timestamp (now)})))

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(defn create-table-lock
  "Create table-based lock implementation.
   
   Args:
     db-ctx - Database context (next.jdbc connection/datasource)
     
   Returns:
     IMigrationLock implementation
     
   Example:
     (def lock (create-table-lock db-ctx))
     (ports/acquire-lock lock \"process-1\" 30000)"
  [db-ctx]
  (->TableBasedLock db-ctx (atom nil)))

;; -----------------------------------------------------------------------------
;; Utilities
;; -----------------------------------------------------------------------------

(defn cleanup-all-stale-locks
  "Remove all expired locks (maintenance operation).
   
   Args:
     db-ctx - Database context
     
   Returns:
     Integer - Number of stale locks removed"
  [db-ctx]
  (ensure-lock-table-exists db-ctx)
  (cleanup-stale-locks db-ctx (lock-key)))

(defn list-active-locks
  "List all active migration locks.
   
   Args:
     db-ctx - Database context
     
   Returns:
     Vector of maps with lock information"
  [db-ctx]
  (ensure-lock-table-exists db-ctx)
  (cleanup-stale-locks db-ctx (lock-key))
  (let [sql (hsql/format {:select [:*]
                         :from [:migration_locks]
                         :where [:= :lock_key (lock-key)]})
        results (jdbc/execute! db-ctx sql
                              {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv #(hash-map
             :holder-id (:holder_id %)
             :acquired-at (:acquired_at %)
             :expires-at (:expires_at %)
             :is-stale? (expired? (:expires_at %)))
          results)))

(defn force-cleanup-all-locks
  "Force remove all migration locks (admin operation).
   
   Args:
     db-ctx - Database context
     
   Returns:
     Integer - Number of locks removed"
  [db-ctx]
  (ensure-lock-table-exists db-ctx)
  (let [sql (hsql/format {:delete-from :migration_locks})
        result (jdbc/execute-one! db-ctx sql {:return-keys false})]
    (or (:next.jdbc/update-count result) 0)))
