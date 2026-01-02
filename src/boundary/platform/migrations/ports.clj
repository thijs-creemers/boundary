(ns boundary.platform.migrations.ports
  "Protocol definitions for migration system.
   
   This namespace defines the abstract interfaces (ports) for:
   - Migration repository (ledger CRUD operations)
   - Migration lock (concurrency control)
   - Migration executor (SQL execution)
   - Migration file discovery
   
   These protocols enable dependency inversion and testability.")

;; Migration Repository Protocol

(defprotocol IMigrationRepository
  "Protocol for managing migration ledger (schema_migrations table).
   
   Responsibilities:
   - Record applied migrations
   - Query migration history
   - Update migration status
   - Verify migration integrity"
  
  (find-all-applied [this]
    "Retrieve all applied migrations from the ledger.
     
     Returns:
       Vector of migration maps with keys:
       - :version
       - :name
       - :module
       - :applied-at
       - :checksum
       - :execution-time-ms
       - :status (:success, :failed, :rolled-back)
       - :db-type
       - :error-message (optional)")
  
  (find-applied-by-module [this module-name]
    "Retrieve applied migrations for a specific module.
     
     Args:
       module-name - Module identifier string
       
     Returns:
       Vector of migration maps for the module")
  
  (find-by-version [this version]
    "Find a specific migration by version.
     
     Args:
       version - Version string (YYYYMMDDhhmmss)
       
     Returns:
       Migration map or nil if not found")
  
  (record-migration [this migration-result]
    "Record a successfully applied migration.
     
     Args:
       migration-result - Map with keys:
         - :version (required)
         - :name (required)
         - :module (required)
         - :checksum (required)
         - :execution-time-ms (required)
         - :db-type (required)
         - :status (required, e.g., :success)
         - :error-message (optional)
         
     Returns:
       Updated migration map with :applied-at timestamp")
  
  (update-migration-status [this version status execution-time-ms error-message]
    "Update the status of a migration (e.g., mark as failed).
     
     Args:
       version - Migration version
       status - New status keyword (:success, :failed, :rolled-back)
       execution-time-ms - Execution time in milliseconds
       error-message - Error message if failed (optional)
       
     Returns:
       Updated migration map")
  
  (delete-migration [this version]
    "Remove a migration from the ledger (for rollbacks).
     
     Args:
       version - Migration version to remove
       
     Returns:
       Boolean indicating success")
  
  (verify-checksum [this version expected-checksum]
    "Verify that recorded checksum matches expected.
     
     Args:
       version - Migration version
       expected-checksum - Expected SHA-256 checksum
       
     Returns:
       Boolean indicating if checksums match")
  
  (get-last-migration [this]
    "Get the most recently applied migration.
     
     Returns:
       Migration map or nil if no migrations applied")
  
  (count-migrations [this]
    "Count total applied migrations.
     
     Returns:
       Integer count"))

;; Migration Lock Protocol

(defprotocol IMigrationLock
  "Protocol for database-level migration locking.
   
   Prevents concurrent migrations across multiple processes/servers.
   
   Implementations:
   - PostgreSQL: Advisory locks
   - SQLite/H2: Table-based locking with timeouts"
  
  (acquire-lock [this holder-id timeout-ms]
    "Acquire migration lock with timeout.
     
     Args:
       holder-id - Unique identifier for the lock holder (e.g., process/thread ID)
       timeout-ms - Maximum time to wait for lock (milliseconds)
       
     Returns:
       Boolean indicating if lock was successfully acquired
       
     Throws:
       Exception if lock acquisition fails critically")
  
  (release-lock [this lock-id]
    "Release a previously acquired lock.
     
     Args:
       lock-id - Lock identifier from acquire-lock
       
     Returns:
       Boolean indicating successful release")
  
  (check-lock-status [this]
    "Check current lock status without acquiring.
     
     Returns:
       Map with:
       - :locked? boolean
       - :holder-info (if locked)
       - :acquired-at timestamp (if locked)")
  
  (force-release-lock [this]
    "Force release of migration lock (admin operation).
     
     WARNING: Only use when process holding lock has died.
     
     Returns:
       Boolean indicating successful force release"))

;; Migration Executor Protocol

(defprotocol IMigrationExecutor
  "Protocol for executing migration SQL.
   
   Responsibilities:
   - Execute SQL statements
   - Handle transactions
   - Capture execution metrics
   - Provide rollback capability"
  
  (execute-migration [this migration-file opts]
    "Execute a migration file (up or down).
     
     Args:
       migration-file - Map with:
         - :version
         - :name
         - :module
         - :content (SQL string)
         - :direction (:up or :down)
         - :path
       opts - Execution options:
         - :transaction? (default true)
         - :timeout-ms (optional)
         
     Returns:
       Map with:
       - :success? boolean
       - :execution-time-ms
       - :statements-executed
       - :error (if failed)")
  
  (execute-sql [this sql opts]
    "Execute raw SQL statement.
     
     Args:
       sql - SQL string to execute
       opts - Options map:
         - :transaction? (default false)
         - :timeout-ms (optional)
         
     Returns:
       Execution result map")
  
  (validate-sql [this sql]
    "Validate SQL syntax without executing.
     
     Args:
       sql - SQL string to validate
       
     Returns:
       Map with:
       - :valid? boolean
       - :errors (if invalid)")
  
  (supports-transactions? [this]
    "Check if database supports transactions.
     
     Returns:
       Boolean")
  
  (get-db-type [this]
    "Get database type identifier.
     
     Returns:
       Keyword - :postgresql, :mysql, :sqlite, :h2"))

;; Migration File Discovery Protocol

(defprotocol IMigrationFileDiscovery
  "Protocol for discovering migration files from filesystem.
   
   Responsibilities:
   - Scan migration directories
   - Parse migration files
   - Calculate checksums
   - Group by module"
  
  (discover-migrations [this base-path opts]
    "Discover all migration files in directory tree.
     
     Args:
       base-path - Root migrations directory path
       opts - Discovery options:
         - :module (optional) - Filter by module
         - :include-down? (default true) - Include _down.sql files
         - :extensions (default [\"sql\"]) - File extensions to scan
         
     Returns:
       Vector of migration file maps with:
       - :version
       - :name
       - :module
       - :direction (:up or :down)
       - :path (absolute file path)
       - :content (SQL string)
       - :checksum (SHA-256)")
  
  (read-migration-file [this file-path]
    "Read and parse a single migration file.
     
     Args:
       file-path - Absolute path to migration file
       
     Returns:
       Migration file map or nil if invalid")
  
  (list-migration-modules [this base-path]
    "List all module names found in migrations.
     
     Args:
       base-path - Root migrations directory
       
     Returns:
       Vector of module name strings")
  
  (validate-migration-structure [this base-path]
    "Validate migration directory structure.
     
     Args:
       base-path - Root migrations directory
       
     Returns:
       Map with:
       - :valid? boolean
       - :errors (if invalid)
       - :warnings (structural issues)"))

;; Helper Functions

(defn migration-applied?
  "Check if a migration version has been applied.
   
   Args:
     repository - IMigrationRepository instance
     version - Migration version string
     
   Returns:
     Boolean indicating if migration is in ledger"
  [repository version]
  (boolean (find-by-version repository version)))

(defn get-applied-versions
  "Get set of all applied migration versions.
   
   Args:
     repository - IMigrationRepository instance
     
   Returns:
     Set of version strings"
  [repository]
  (set (map :version (find-all-applied repository))))

(defn filter-unapplied-migrations
  "Filter list of migrations to only unapplied ones.
   
   Args:
     repository - IMigrationRepository instance
     migrations - Collection of migration maps
     
   Returns:
     Vector of unapplied migrations"
  [repository migrations]
  (let [applied (get-applied-versions repository)]
    (vec (remove #(contains? applied (:version %)) migrations))))

(defn get-migration-history
  "Get chronologically ordered migration history.
   
   Args:
     repository - IMigrationRepository instance
     opts - Options map:
       - :module (optional) - Filter by module
       - :limit (optional) - Max results
       - :status (optional) - Filter by status
       
   Returns:
     Vector of migrations ordered by applied-at"
  [repository opts]
  (let [migrations (if-let [module (:module opts)]
                    (find-applied-by-module repository module)
                    (find-all-applied repository))
        filtered (if-let [status (:status opts)]
                  (filter #(= status (:status %)) migrations)
                  migrations)
        sorted (sort-by :applied-at filtered)]
    (if-let [limit (:limit opts)]
      (vec (take limit sorted))
      (vec sorted))))

(defn create-migration-record
  "Create a migration record for insertion.
   
   Args:
     migration - Migration map
     execution-result - Execution result map
     db-type - Database type keyword
     
   Returns:
     Migration record ready for recording"
  [migration execution-result db-type]
  {:version (:version migration)
   :name (:name migration)
   :module (:module migration)
   :checksum (:checksum migration)
   :execution-time-ms (:execution-time-ms execution-result)
   :status (if (:success? execution-result) :success :failed)
   :db-type db-type
   :error-message (:error execution-result)})

(defn validate-migration-file
  "Validate a migration file structure.
   
   Args:
     migration-file - Migration file map
     
   Returns:
     Map with :valid? and optional :errors"
  [migration-file]
  (let [errors []
        errors (if-not (:version migration-file)
                 (conj errors "Missing :version")
                 errors)
        errors (if-not (:name migration-file)
                 (conj errors "Missing :name")
                 errors)
        errors (if-not (:module migration-file)
                 (conj errors "Missing :module")
                 errors)
        errors (if-not (:content migration-file)
                 (conj errors "Missing :content")
                 errors)
        errors (if-not (:checksum migration-file)
                 (conj errors "Missing :checksum")
                 errors)
        errors (if-not (:direction migration-file)
                 (conj errors "Missing :direction")
                 errors)]
    {:valid? (empty? errors)
     :errors errors}))
