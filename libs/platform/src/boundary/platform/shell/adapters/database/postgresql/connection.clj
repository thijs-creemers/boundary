(ns boundary.platform.shell.adapters.database.postgresql.connection
  "PostgreSQL connection management utilities."
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]))

;; =============================================================================
;; Connection Configuration
;; =============================================================================

(def ^:private default-application-name
  "Default application name for PostgreSQL connections."
  "boundary-app")

(def ^:private default-statement-timeout-ms
  "Default statement timeout in milliseconds (30 seconds)."
  30000)

;; =============================================================================
;; Connection Initialization
;; =============================================================================

(defn initialize!
  "Initialize PostgreSQL connection with optimized settings.
   
   Args:
     datasource: PostgreSQL datasource
     db-config: Database configuration map
     
   Returns:
     nil - side effects only"
  [datasource db-config]
  (log/debug "Initializing PostgreSQL connection settings")
  (try
    ;; Set application name for connection identification
    (let [app-name (or (:application-name db-config) default-application-name)]
      (jdbc/execute! datasource ["SET application_name = ?" app-name]))

    ;; Set timezone to UTC for consistency
    (jdbc/execute! datasource ["SET TIME ZONE 'UTC'"])

    ;; Set statement timeout (configurable, default 30 seconds)
    (let [timeout (get db-config :statement-timeout-ms default-statement-timeout-ms)]
      (when (pos? timeout)
        (jdbc/execute! datasource ["SET statement_timeout = ?" timeout])))

    (log/debug "PostgreSQL connection initialized successfully")
    (catch Exception e
      ;; Log warning but don't fail - these are optional optimization settings
      (log/warn "Failed to initialize some PostgreSQL settings (connection will still work)"
                {:error (.getMessage e)
                 :error-type (type e)}))))

(defn build-jdbc-url
  "Build PostgreSQL JDBC URL from configuration.
   
   Args:
     db-config: Database configuration map with :host, :port, :name
     
   Returns:
     String - JDBC URL with PostgreSQL-specific parameters"
  [{:keys [host port name]}]
  (str "jdbc:postgresql://" host ":" port "/" name "?stringtype=unspecified"))

(defn pool-defaults
  "Get PostgreSQL-optimized connection pool defaults.
   
   Returns:
     Map - HikariCP pool configuration"
  []
  {:minimum-idle 5
   :maximum-pool-size 20
   :connection-timeout-ms 30000
   :idle-timeout-ms 600000
   :max-lifetime-ms 1800000})