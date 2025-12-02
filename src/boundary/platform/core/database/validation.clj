(ns boundary.platform.core.database.validation
  "Pure validation functions for database contexts and configurations.
   
   All functions are pure predicates or validators that check data structure
   conformity without performing I/O or side effects."
  (:require [boundary.platform.shell.adapters.database.protocols :as protocols]))

;; =============================================================================
;; Context Validation
;; =============================================================================

(defn db-context?
  "Check if value is a valid database context structure.
   
   Pure predicate: checks data structure shape and protocol satisfaction.
   
   Args:
     ctx: Value to check
     
   Returns:
     Boolean - true if ctx has correct structure"
  [ctx]
  (and (map? ctx)
       (:datasource ctx)
       (:adapter ctx)
       (satisfies? protocols/DBAdapter (:adapter ctx))))

(defn validate-db-context
  "Validate database context structure.
   
   Pure function: returns context if valid, throws if invalid.
   
   Args:
     ctx: Database context to validate
     
   Returns:
     ctx if valid
     
   Throws:
     IllegalArgumentException if ctx is invalid"
  [ctx]
  (if (db-context? ctx)
    ctx
    (throw (IllegalArgumentException.
            (str "Invalid database context. Expected map with :datasource and :adapter keys. Got: "
                 (type ctx))))))

(defn adapter-context?
  "Check if value has a valid adapter (subset of db-context).
   
   Pure predicate: checks adapter-specific structure.
   
   Args:
     ctx: Value to check
     
   Returns:
     Boolean - true if ctx has valid adapter"
  [ctx]
  (and (map? ctx)
       (:adapter ctx)
       (satisfies? protocols/DBAdapter (:adapter ctx))))

(defn validate-adapter-context
  "Validate that context has a valid adapter.
   
   Pure function: returns context if valid, throws if invalid.
   
   Args:
     ctx: Context to validate
     
   Returns:
     ctx if valid
     
   Throws:
     IllegalArgumentException if adapter is invalid"
  [ctx]
  (if (adapter-context? ctx)
    ctx
    (throw (IllegalArgumentException.
            (str "Invalid adapter context. Expected map with :adapter key implementing DBAdapter protocol. Got: "
                 (type ctx))))))

;; =============================================================================
;; Configuration Validation
;; =============================================================================

(defn valid-config-structure?
  "Check if configuration has required structure.
   
   Pure predicate: validates config map shape.
   
   Args:
     config: Configuration map to check
     
   Returns:
     Boolean - true if config has :active and :inactive keys"
  [config]
  (and (map? config)
       (contains? config :active)
       (contains? config :inactive)))

(defn validate-sqlite-config
  "Validate SQLite adapter configuration.
   
   Pure validation: checks required keys for SQLite.
   
   Args:
     config: SQLite configuration map
     
   Returns:
     nil if valid, error message string if invalid"
  [config]
  (when-not (:db config)
    "SQLite configuration missing :db key"))

(defn validate-postgresql-config
  "Validate PostgreSQL adapter configuration.
   
   Pure validation: checks required keys for PostgreSQL.
   
   Args:
     config: PostgreSQL configuration map
     
   Returns:
     nil if valid, error message string if invalid"
  [config]
  (let [required-keys [:host :port :dbname :user :password]]
    (when-let [missing (seq (remove config required-keys))]
      (str "PostgreSQL configuration missing keys: " missing))))

(defn validate-mysql-config
  "Validate MySQL adapter configuration.
   
   Pure validation: checks required keys for MySQL.
   
   Args:
     config: MySQL configuration map
     
   Returns:
     nil if valid, error message string if invalid"
  [config]
  (let [required-keys [:host :port :dbname :user :password]]
    (when-let [missing (seq (remove config required-keys))]
      (str "MySQL configuration missing keys: " missing))))

(defn validate-h2-config
  "Validate H2 adapter configuration.
   
   Pure validation: checks required keys for H2.
   
   Args:
     config: H2 configuration map
     
   Returns:
     nil if valid, error message string if invalid"
  [config]
  (when-not (or (:db config) (:memory config))
    "H2 configuration missing :db or :memory key"))

(defn validate-adapter-config
  "Validate adapter configuration based on adapter type.
   
   Pure function: dispatches to type-specific validators.
   
   Args:
     adapter-type: Keyword adapter type (:sqlite, :postgresql, etc.)
     config: Configuration map
     
   Returns:
     nil if valid, error message string if invalid"
  [adapter-type config]
  (case adapter-type
    :sqlite (validate-sqlite-config config)
    :postgresql (validate-postgresql-config config)
    :mysql (validate-mysql-config config)
    :h2 (validate-h2-config config)
    nil))  ; Unknown type, skip validation
