(ns boundary.shell.adapters.database.sqlite.connection
  "SQLite connection management utilities."
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]))

;; =============================================================================
;; Configuration Constants
;; =============================================================================

(def ^:private mmap-size-bytes
  "Memory-mapped I/O size in bytes (256MB)."
  268435456)

(def ^:private cache-size-pages
  "Page cache size in pages (~10MB with 1KB pages)."
  10000)

(def ^:private busy-timeout-ms
  "Busy timeout in milliseconds (5 seconds)."
  5000)

(def ^:private default-sqlite-pragmas
  "Default SQLite PRAGMA settings for optimal performance and reliability."
  ["PRAGMA journal_mode=WAL"                                ; Write-Ahead Logging for better concurrency
   "PRAGMA synchronous=NORMAL"                              ; Balance between safety and performance
   "PRAGMA foreign_keys=ON"                                 ; Enable foreign key constraints
   "PRAGMA temp_store=MEMORY"                               ; Store temporary tables in memory
   (str "PRAGMA mmap_size=" mmap-size-bytes)                ; Memory-mapped I/O
   (str "PRAGMA cache_size=" cache-size-pages)              ; Page cache
   (str "PRAGMA busy_timeout=" busy-timeout-ms)])           ; Busy timeout

;; =============================================================================
;; Connection Validation
;; =============================================================================

(defn- validate-datasource
  "Validate datasource parameter.

   Args:
     datasource: Datasource to validate

   Returns:
     datasource if valid

   Throws:
     IllegalArgumentException if invalid"
  [datasource]
  (when (nil? datasource)
    (throw (IllegalArgumentException. "Datasource cannot be nil")))
  datasource)

;; =============================================================================
;; Connection Initialization
;; =============================================================================

(defn initialize!
  "Apply SQLite PRAGMA settings to a connection.

   Args:
     datasource: Database connection or connection pool
     db-config: Database configuration map (unused for SQLite)
     custom-pragmas: Optional vector of additional PRAGMA statements

   Returns:
     nil - side effects only"
  ([datasource db-config]
   (initialize! datasource db-config []))
  ([datasource db-config custom-pragmas]
   (validate-datasource datasource)
   (let [all-pragmas   (concat default-sqlite-pragmas custom-pragmas)
         success-count (atom 0)
         failure-count (atom 0)]
     (log/debug "Initializing SQLite PRAGMA settings" {:pragmas-count (count all-pragmas)})
     (doseq [pragma all-pragmas]
       (try
         (jdbc/execute! datasource [pragma])
         (log/debug "Applied PRAGMA successfully" {:pragma pragma})
         (swap! success-count inc)
         (catch Exception e
           (log/warn "Failed to apply PRAGMA, continuing"
                     {:pragma pragma
                      :error  (.getMessage e)})
           (swap! failure-count inc))))
     (log/info "SQLite PRAGMA initialization completed"
               {:successful-pragmas @success-count
                :failed-pragmas     @failure-count
                :total-pragmas      (count all-pragmas)}))))

(defn build-jdbc-url
  "Build SQLite JDBC URL from configuration.

   Args:
     db-config: Database configuration map with :database-path

   Returns:
     String - JDBC URL for SQLite"
  [db-config]
  (str "jdbc:sqlite:" (:database-path db-config)))

(defn pool-defaults
  "Get SQLite-optimized connection pool defaults.

   Returns:
     Map - HikariCP pool configuration optimized for embedded usage"
  []
  {:minimum-idle          1
   :maximum-pool-size     5
   :connection-timeout-ms 30000
   :idle-timeout-ms       600000
   :max-lifetime-ms       1800000})
