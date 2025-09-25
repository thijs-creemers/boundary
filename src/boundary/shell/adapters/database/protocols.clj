(ns boundary.shell.adapters.database.protocols
  "Protocol defining the common interface for database adapters.
   
   This protocol abstracts database-specific behavior while allowing 
   common database operations to be implemented in a shared core namespace.
   Each database type (SQLite, PostgreSQL, MySQL, H2) implements this 
   protocol to provide database-specific functionality.
   
   Design Philosophy:
   - Keep protocol narrow - prefer common behavior in core
   - Only include methods that differ between databases
   - Database-agnostic operations belong in core namespace
   - Type conversions use shared utilities where possible"
  (:require [clojure.spec.alpha :as s]))

;; =============================================================================
;; Core Database Adapter Protocol
;; =============================================================================

(defprotocol DBAdapter
  "Protocol defining database-specific behavior for multi-database support.
   
   Each database adapter (SQLite, PostgreSQL, MySQL, H2) implements this
   protocol to provide database-specific functionality while common operations
   are handled by the shared database core namespace."

  (dialect [this]
    "Return the HoneySQL dialect keyword for this database.
     
     Returns:
       Keyword - :sqlite, :postgresql, :mysql, or :h2
       
     Example:
       (dialect sqlite-adapter) ;; => :sqlite")

  (jdbc-driver [this]
    "Return the JDBC driver class string for this database.
     
     Returns:
       String - fully qualified JDBC driver class name
       
     Example:
       (jdbc-driver sqlite-adapter) ;; => \"org.sqlite.JDBC\"")

  (jdbc-url [this db-config]
    "Generate JDBC URL string from database configuration.
     
     Args:
       db-config: Database configuration map
       
     Returns:
       String - JDBC URL formatted for this database type
       
     Example:
       (jdbc-url postgres-adapter {:host \"localhost\" :port 5432 :name \"mydb\"})
       ;; => \"jdbc:postgresql://localhost:5432/mydb\"")

  (pool-defaults [this]
    "Return default connection pool settings optimized for this database.
     
     Returns:
       Map - HikariCP pool configuration defaults
       
     Example:
       (pool-defaults sqlite-adapter)
       ;; => {:minimum-idle 1 :maximum-pool-size 5 :connection-timeout-ms 30000}")

  (init-connection! [this datasource db-config]
    "Initialize database connection with database-specific settings.
     
     Performs one-time connection initialization such as:
     - SQLite: Apply PRAGMA settings
     - PostgreSQL: Set application_name, timezone
     - MySQL: Set sql_mode, timezone
     - H2: Set MODE, timezone
     
     Args:
       datasource: Database connection pool or connection
       db-config: Database configuration map
       
     Returns:
       nil - side effects only
       
     Example:
       (init-connection! sqlite-adapter datasource config)")

  (build-where [this filters]
    "Build database-specific WHERE clause conditions from filter map.
     
     Handles database-specific differences such as:
     - PostgreSQL: Use ILIKE for case-insensitive string matching
     - Others: Use LIKE for string matching
     - Boolean handling varies by database
     
     Args:
       filters: Map of field -> value filters
       
     Returns:
       HoneySQL WHERE clause fragment or nil
       
     Example:
       (build-where postgres-adapter {:name \"john\" :active true})
       ;; => [:and [:ilike :name \"%john%\"] [:= :active true]]")

  (boolean->db [this boolean-value]
    "Convert boolean value to database-specific representation.
     
     Args:
       boolean-value: Boolean value (true/false/nil)
       
     Returns:
       Database-specific boolean representation
       - SQLite/MySQL: 1/0
       - PostgreSQL/H2: true/false
       
     Example:
       (boolean->db sqlite-adapter true) ;; => 1
       (boolean->db postgres-adapter true) ;; => true")

  (db->boolean [this db-value]
    "Convert database boolean representation to Clojure boolean.
     
     Args:
       db-value: Database boolean value
       
     Returns:
       Boolean - true/false
       
     Example:
       (db->boolean sqlite-adapter 1) ;; => true
       (db->boolean postgres-adapter true) ;; => true")

  (table-exists? [this datasource table-name]
    "Check if a table exists in the database.
     
     Uses database-specific table introspection:
     - SQLite: Query sqlite_master
     - PostgreSQL: Query information_schema.tables
     - MySQL: Query information_schema.tables
     - H2: Query INFORMATION_SCHEMA.TABLES
     
     Args:
       datasource: Database connection pool or connection
       table-name: String or keyword table name
       
     Returns:
       Boolean - true if table exists
       
     Example:
       (table-exists? sqlite-adapter ds :users) ;; => true")

  (get-table-info [this datasource table-name]
    "Get column information for a table.
     
     Uses database-specific column introspection to return standardized
     column information including name, type, constraints, and primary key status.
     
     Args:
       datasource: Database connection pool or connection
       table-name: String or keyword table name
       
     Returns:
       Vector of column info maps with keys:
       - :name - column name string
       - :type - database type string
       - :not-null - boolean
       - :default - default value or nil
       - :primary-key - boolean
       
     Example:
       (get-table-info sqlite-adapter ds :users)
       ;; => [{:name \"id\" :type \"TEXT\" :not-null true :primary-key true} ...]"))

;; =============================================================================
;; Configuration Specifications
;; =============================================================================

(s/def ::adapter #{:sqlite :postgresql :mysql :h2})
(s/def ::database-path string?)
(s/def ::host string?)
(s/def ::port pos-int?)
(s/def ::name string?)
(s/def ::username string?)
(s/def ::password string?)

(s/def ::sqlite-config
  (s/keys :req-un [::adapter ::database-path]
          :opt-un [::pool]))

(s/def ::server-db-config
  (s/keys :req-un [::adapter ::host ::port ::name ::username ::password]
          :opt-un [::pool]))

(s/def ::minimum-idle pos-int?)
(s/def ::maximum-pool-size pos-int?)
(s/def ::connection-timeout-ms pos-int?)
(s/def ::idle-timeout-ms pos-int?)
(s/def ::max-lifetime-ms pos-int?)

(s/def ::pool
  (s/keys :opt-un [::minimum-idle ::maximum-pool-size ::connection-timeout-ms
                   ::idle-timeout-ms ::max-lifetime-ms]))

(s/def ::db-config
  (s/or :sqlite ::sqlite-config
        :server ::server-db-config))

(defn validate-db-config
      "Validate database configuration against spec.

       Args:
         db-config: Database configuration map

       Returns:
         db-config if valid

       Throws:
         ExceptionInfo if configuration is invalid"
  [db-config]
  (if (s/valid? ::db-config db-config)
    db-config
    (throw (ex-info "Invalid database configuration"
                    {:config db-config
                     :errors (s/explain-data ::db-config db-config)}))))