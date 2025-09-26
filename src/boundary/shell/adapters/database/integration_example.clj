(ns boundary.shell.adapters.database.integration-example
  "Example integration showing how to use the multi-database adapter system.
   
   This namespace demonstrates how to:
   - Load environment-specific configurations
   - Initialize database adapters based on active configurations
   - Use the initialized adapters for database operations
   - Handle multiple active databases simultaneously"
  (:require [boundary.shell.adapters.database.config :as db-config]
            [boundary.shell.adapters.database.config-factory :as config-factory]
            [boundary.shell.adapters.database.core :as db-core]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Application State Management
;; =============================================================================

(def ^:private app-state
  "Global application state holding active database connections."
  (atom {:databases {}
         :config nil}))

;; =============================================================================
;; Initialization Functions
;; =============================================================================

(defn initialize-databases!
  "Initialize all active databases based on the current environment configuration.
   
   This function:
   1. Loads the environment-specific config.edn
   2. Identifies active database adapters
   3. Creates connection pools for each active adapter
   4. Stores the initialized adapters in application state
   
   Returns: map of {:adapter-key {:adapter adapter :pool pool}}"
  ([]
   (initialize-databases! (or (System/getProperty "env") "dev")))
  
  ([environment]
   (try
     (log/info "Initializing databases for environment:" environment)
     
     ;; Load configuration for the specified environment
     (let [config (db-config/load-config environment)
           active-adapters (config-factory/create-active-adapters config)]
       
       (log/info "Found" (count active-adapters) "active database adapters:"
                 (keys active-adapters))
       
       ;; Initialize each active adapter
       (let [initialized-dbs 
             (into {}
                   (map (fn [[adapter-key adapter-config]]
                          (log/info "Initializing adapter:" adapter-key)
                          (let [pool (db-core/create-connection-pool adapter-config)]
                            [adapter-key {:adapter adapter-config
                                          :pool pool}]))
                        active-adapters))]
         
         ;; Update application state
         (swap! app-state assoc
                :databases initialized-dbs
                :config config)
         
         (log/info "Successfully initialized" (count initialized-dbs) "database connections")
         initialized-dbs))
     
     (catch Exception e
       (log/error e "Failed to initialize databases")
       (throw (ex-info "Database initialization failed"
                       {:environment environment
                        :error (.getMessage e)}
                       e))))))

(defn shutdown-databases!
  "Safely shutdown all active database connections."
  []
  (let [{:keys [databases]} @app-state]
    (log/info "Shutting down" (count databases) "database connections")
    
    (doseq [[adapter-key {:keys [pool]}] databases]
      (try
        (log/debug "Closing connection pool for:" adapter-key)
        (.close pool)
        (log/debug "Successfully closed:" adapter-key)
        (catch Exception e
          (log/warn e "Error closing connection pool for:" adapter-key))))
    
    (swap! app-state assoc :databases {})
    (log/info "All database connections shut down")))

;; =============================================================================
;; Database Access Functions
;; =============================================================================

(defn get-database
  "Get a specific database adapter by key.
   
   Args:
     adapter-key - Keyword identifying the adapter (e.g., :boundary/sqlite)
   
   Returns: {:adapter adapter-config :pool connection-pool} or nil"
  [adapter-key]
  (get-in @app-state [:databases adapter-key]))

(defn get-primary-database
  "Get the primary database (first active adapter).
   
   Useful when you have a single active database or want a default."
  []
  (let [databases (:databases @app-state)]
    (when (seq databases)
      (second (first databases)))))

(defn list-active-databases
  "List all currently active database adapters.
   
   Returns: sequence of [adapter-key {:adapter ... :pool ...}]"
  []
  (seq (:databases @app-state)))

(defn execute-query
  "Execute a query on a specific database adapter.
   
   Args:
     adapter-key - Keyword identifying the adapter
     query-map - HoneySQL query map
   
   Returns: Query results or throws exception"
  [adapter-key query-map]
  (if-let [db (get-database adapter-key)]
    (db-core/execute-query! (:adapter db) query-map)
    (throw (ex-info "Database adapter not found or not initialized"
                    {:adapter-key adapter-key
                     :available-adapters (keys (:databases @app-state))}))))

;; =============================================================================
;; Example Usage Functions
;; =============================================================================

(defn example-basic-usage
  "Demonstrate basic usage of the multi-database system."
  []
  (log/info "=== Basic Usage Example ===")
  
  ;; Initialize databases for current environment
  (initialize-databases!)
  
  ;; List what's available
  (let [active-dbs (list-active-databases)]
    (log/info "Active databases:" (map first active-dbs))
    
    ;; Try to execute a simple query on the first available database
    (when-let [[adapter-key _] (first active-dbs)]
      (try
        (let [result (execute-query adapter-key {:select [:1 :as :test]})]
          (log/info "Test query result on" adapter-key ":" result))
        (catch Exception e
          (log/warn "Test query failed on" adapter-key ":" (.getMessage e))))))
  
  ;; Clean shutdown
  (shutdown-databases!)
  (log/info "=== Example Complete ==="))

(defn example-multi-database-usage
  "Demonstrate using multiple active databases simultaneously."
  []
  (log/info "=== Multi-Database Usage Example ===")
  
  ;; For this example, we'll need a config with multiple active databases
  ;; You would modify your config.edn to have both SQLite and H2 active
  
  (initialize-databases!)
  
  (let [active-dbs (list-active-databases)]
    (if (> (count active-dbs) 1)
      (do
        (log/info "Testing with multiple databases:" (map first active-dbs))
        
        ;; Execute the same query on all active databases
        (doseq [[adapter-key _] active-dbs]
          (try
            (let [result (execute-query adapter-key {:select [:current_timestamp :as :now]})]
              (log/info "Timestamp from" adapter-key ":" result))
            (catch Exception e
              (log/warn "Query failed on" adapter-key ":" (.getMessage e))))))
      
      (log/info "Only one database active, cannot demonstrate multi-database usage")))
  
  (shutdown-databases!)
  (log/info "=== Multi-Database Example Complete ==="))

(defn example-environment-switching
  "Demonstrate switching between different environment configurations."
  []
  (log/info "=== Environment Switching Example ===")
  
  ;; Test different environments
  (doseq [env ["dev" "test" "prod"]]
    (log/info "--- Testing environment:" env "---")
    (try
      (let [config (db-config/load-config env)
            active-adapters (config-factory/create-active-adapters config)]
        (log/info "Environment" env "has" (count active-adapters) "active adapters:"
                  (keys active-adapters)))
      (catch Exception e
        (log/warn "Failed to load environment" env ":" (.getMessage e)))))
  
  (log/info "=== Environment Switching Example Complete ==="))

;; =============================================================================
;; Development and Testing Helpers
;; =============================================================================

(defn reset-application-state!
  "Reset application state - useful for development and testing."
  []
  (shutdown-databases!)
  (reset! app-state {:databases {} :config nil})
  (log/info "Application state reset"))

(defn current-state
  "Get current application state - useful for debugging."
  []
  (let [state @app-state]
    {:active-databases (keys (:databases state))
     :config-loaded? (some? (:config state))}))

(comment
  ;; Interactive development examples
  ;; Execute these in your REPL to test the system
  ;; 
  ;; IMPORTANT: Start your REPL with appropriate database drivers:
  ;; clj -M:repl-clj:db/sqlite           ; SQLite only
  ;; clj -M:repl-clj:db/h2               ; H2 only  
  ;; clj -M:repl-clj:db/all-drivers      ; All drivers available
  
  ;; Basic initialization
  (initialize-databases!)
  (current-state)
  
  ;; Test a query (requires SQLite driver: :db/sqlite)
  (execute-query :boundary/sqlite {:select [:1 :as :test]})
  
  ;; Test H2 query (requires H2 driver: :db/h2) 
  (execute-query :boundary/h2 {:select [:1 :as :test]})
  
  ;; List active databases
  (list-active-databases)
  
  ;; Run examples
  (example-basic-usage)
  (example-environment-switching)
  
  ;; Clean up
  (reset-application-state!)
  )
