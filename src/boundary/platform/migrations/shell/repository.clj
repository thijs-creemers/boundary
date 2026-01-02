(ns boundary.platform.migrations.shell.repository
  "Database repository implementation for schema_migrations ledger.
   
   Provides concrete implementation of IMigrationRepository protocol
   for CRUD operations on the migrations tracking table.
   
   Architecture:
   - Implements: boundary.platform.migrations.ports/IMigrationRepository
   - Uses: next.jdbc for database operations
   - Uses: HoneySQL for query building
   - Pure delegation to database adapter layer
   
   Responsibilities:
   - Schema migrations table CRUD operations
   - Migration history queries and filtering
   - Checksum verification
   - Applied migrations tracking
   
   Non-responsibilities:
   - Migration execution (see executor.clj)
   - Lock management (see locking/)
   - File discovery (see discovery.clj)
   - Business logic (see core/planning.clj)"
  (:require [boundary.platform.migrations.ports :as ports]
            [boundary.platform.migrations.schema :as schema]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.time Instant]))

;; -----------------------------------------------------------------------------
;; Schema Definition
;; -----------------------------------------------------------------------------

(def ^:private schema-migrations-ddl
  "DDL for creating the schema_migrations tracking table.
   
   Design:
   - version: Primary key, 14-char timestamp (YYYYMMDDhhmmss)
   - name: Human-readable migration name
   - module: Module scope (e.g., 'user', 'billing', 'platform')
   - applied_at: When migration was executed
   - checksum: SHA-256 hash for tamper detection
   - execution_time_ms: Performance tracking
   - status: 'success', 'failed', 'rolled-back'
   - db_type: Database type ('postgresql', 'h2', 'sqlite')
   - error_message: Failure details (nullable)"
  "CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(14) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    module VARCHAR(100) NOT NULL,
    applied_at TIMESTAMP NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    execution_time_ms INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    db_type VARCHAR(20) NOT NULL,
    error_message TEXT
  );

  CREATE INDEX IF NOT EXISTS idx_schema_migrations_module 
    ON schema_migrations(module);
  
  CREATE INDEX IF NOT EXISTS idx_schema_migrations_applied_at 
    ON schema_migrations(applied_at);
  
  CREATE INDEX IF NOT EXISTS idx_schema_migrations_status 
    ON schema_migrations(status);")

;; -----------------------------------------------------------------------------
;; Database Row Transformations
;; -----------------------------------------------------------------------------

(defn- db-row->migration
  "Transform database row to SchemaMigration entity.
   
   Handles key normalization and type conversions:
   - Snake_case (DB) -> kebab-case (Clojure)
   - Timestamp/String -> Instant objects
   - NULL error_message -> nil
   
   Pure: true"
  [row]
  {:version (:version row)
   :name (:name row)
   :module (:module row)
   :applied-at (cond
                 (instance? Instant (:applied_at row))
                 (:applied_at row)
                 
                 (instance? java.sql.Timestamp (:applied_at row))
                 (.toInstant (:applied_at row))
                 
                 (string? (:applied_at row))
                 (Instant/parse (:applied_at row))
                 
                 :else
                 nil)
   :checksum (:checksum row)
   :execution-time-ms (:execution_time_ms row)
   :status (keyword (:status row))
   :db-type (:db_type row)
   :error-message (:error_message row)})

(defn- migration->db-row
  "Transform SchemaMigration entity to database row.
   
   Handles:
   - Kebab-case -> snake_case
   - Instant -> Timestamp
   - Keyword status -> String
   
   Pure: true"
  [migration]
  {:version (:version migration)
   :name (:name migration)
   :module (:module migration)
   :applied_at (:applied-at migration)
   :checksum (:checksum migration)
   :execution_time_ms (:execution-time-ms migration)
   :status (name (:status migration))
   :db_type (:db-type migration)
   :error_message (:error-message migration)})

;; -----------------------------------------------------------------------------
;; Repository Implementation
;; -----------------------------------------------------------------------------

(defrecord DatabaseMigrationRepository [db-ctx]
  ports/IMigrationRepository
  
  (find-all-applied [this]
    (let [query (sql/format {:select [:*]
                             :from [:schema_migrations]
                             :order-by [[:version :asc]]})
          rows (jdbc/execute! db-ctx query {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv db-row->migration rows)))
  
  (find-applied-by-module [this module-name]
    {:pre [(string? module-name)]}
    (let [query (sql/format {:select [:*]
                             :from [:schema_migrations]
                             :where [:= :module module-name]
                             :order-by [[:version :asc]]})
          rows (jdbc/execute! db-ctx query {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv db-row->migration rows)))
  
  (find-by-version [this version]
    {:pre [(string? version)]}
    (let [query (sql/format {:select [:*]
                             :from [:schema_migrations]
                             :where [:= :version version]})
          row (jdbc/execute-one! db-ctx query {:builder-fn rs/as-unqualified-lower-maps})]
      (when row
        (db-row->migration row))))
  
  (record-migration [this migration]
    {:pre [(m/validate schema/SchemaMigration migration)]}
    (let [db-row (migration->db-row migration)
          query (sql/format {:insert-into :schema_migrations
                             :values [db-row]})]
      (jdbc/execute-one! db-ctx query)
      migration))
  
  (update-migration-status [this version status execution-time-ms error-message]
    {:pre [(string? version)
           (keyword? status)
           (int? execution-time-ms)]}
    (let [query (sql/format {:update :schema_migrations
                             :set {:status (name status)
                                   :execution_time_ms execution-time-ms
                                   :error_message error-message}
                             :where [:= :version version]})]
      (jdbc/execute-one! db-ctx query)
      nil))
  
  (delete-migration [this version]
    {:pre [(string? version)]}
    (let [query (sql/format {:delete-from :schema_migrations
                             :where [:= :version version]})
          result (jdbc/execute-one! db-ctx query)]
      ;; Return true if any rows were affected
      (or (some? result) true)))
  
  (verify-checksum [this version expected-checksum]
    {:pre [(string? version) (string? expected-checksum)]}
    (if-let [migration (ports/find-by-version this version)]
      (= (:checksum migration) expected-checksum)
      false))
  
  (get-last-migration [this]
    (let [query (sql/format {:select [:*]
                             :from [:schema_migrations]
                             :where [:= :status "success"]
                             :order-by [[:version :desc]]
                             :limit 1})
          row (jdbc/execute-one! db-ctx query {:builder-fn rs/as-unqualified-lower-maps})]
      (when row
        (db-row->migration row))))
  
  (count-migrations [this]
    (let [query (sql/format {:select [[[:count :*] :cnt]]
                             :from [:schema_migrations]})
          result (jdbc/execute-one! db-ctx query {:builder-fn rs/as-unqualified-lower-maps})]
      (:cnt result 0))))

;; -----------------------------------------------------------------------------
;; Factory Functions
;; -----------------------------------------------------------------------------

(defn create-repository
  "Create a new DatabaseMigrationRepository instance.
   
   Args:
     db-ctx - next.jdbc database connection or datasource
     
   Returns:
     DatabaseMigrationRepository instance implementing IMigrationRepository
     
   Example:
     (def repo (create-repository db-ctx))
     (ports/find-all-applied repo)"
  [db-ctx]
  {:pre [db-ctx]}
  (->DatabaseMigrationRepository db-ctx))

(defn ensure-schema-migrations-table!
  "Ensure the schema_migrations tracking table exists.
   
   Idempotent: Safe to call multiple times.
   Creates table with indexes if not exists.
   
   Args:
     db-ctx - next.jdbc database connection
     
   Returns:
     nil
     
   Side Effects:
     - Creates schema_migrations table if missing
     - Creates indexes for module, applied_at, status
     
   Example:
     (ensure-schema-migrations-table! db-ctx)"
  [db-ctx]
  {:pre [db-ctx]}
  (jdbc/execute! db-ctx [schema-migrations-ddl])
  nil)

;; -----------------------------------------------------------------------------
;; Utility Functions
;; -----------------------------------------------------------------------------

(defn migration-exists?
  "Check if a migration version has been applied.
   
   Args:
     repository - IMigrationRepository instance
     version - Migration version string (YYYYMMDDhhmmss)
     
   Returns:
     Boolean - true if migration exists in ledger
     
   Pure: false (reads from database)"
  [repository version]
  (some? (ports/find-by-version repository version)))

(defn get-applied-versions
  "Get set of all applied migration versions.
   
   Args:
     repository - IMigrationRepository instance
     
   Returns:
     Set of version strings
     
   Example:
     (get-applied-versions repo)
     ;; => #{\"20240101120000\" \"20240102130000\"}
   
   Pure: false (reads from database)"
  [repository]
  (into #{} (map :version) (ports/find-all-applied repository)))

(defn get-failed-migrations
  "Get all migrations with 'failed' status.
   
   Args:
     repository - IMigrationRepository instance
     
   Returns:
     Vector of SchemaMigration entities with status=:failed
     
   Pure: false (reads from database)"
  [repository]
  (let [all-migrations (ports/find-all-applied repository)]
    (filterv #(= :failed (:status %)) all-migrations)))

(defn get-rollback-candidates
  "Get migrations that have been rolled back.
   
   Args:
     repository - IMigrationRepository instance
     
   Returns:
     Vector of SchemaMigration entities with status=:rolled-back
     
   Pure: false (reads from database)"
  [repository]
  (let [all-migrations (ports/find-all-applied repository)]
    (filterv #(= :rolled-back (:status %)) all-migrations)))

(defn get-module-history
  "Get migration history for a specific module, ordered by version.
   
   Args:
     repository - IMigrationRepository instance
     module-name - Module name string (e.g., 'user', 'billing')
     
   Returns:
     Vector of SchemaMigration entities, oldest first
     
   Example:
     (get-module-history repo \"user\")
   
   Pure: false (reads from database)"
  [repository module-name]
  (ports/find-applied-by-module repository module-name))
