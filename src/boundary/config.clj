(ns boundary.config
  "Configuration management using Aero for environment-based config.
   
   This namespace provides configuration loading and Integrant system
   configuration generation for the Boundary application.
   
   Usage:
     (def config (load-config))
     (def ig-config (ig-config config))
     (integrant.core/init ig-config)"
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Configuration Loading
;; =============================================================================

(defn load-config
  "Load configuration from resources/conf/dev/config.edn using Aero.
   
   Args:
     opts: Optional map with :profile key (defaults to :dev)
   
   Returns:
     Configuration map with resolved environment variables and profile selection
   
   Example:
     (load-config)
     (load-config {:profile :test})"
  ([] (load-config {}))
  ([{:keys [profile] :or {profile :dev}}]
   (let [config-path (str "conf/" (name profile) "/config.edn")
         config-resource (io/resource config-path)]
     (if config-resource
       (do
         (log/info "Loading configuration" {:profile profile :path config-path})
         (aero/read-config config-resource {:profile profile}))
       (throw (ex-info "Configuration file not found"
                       {:profile profile
                        :path config-path
                        :available-profiles [:dev :test :prod]}))))))

;; =============================================================================
;; Configuration Helpers
;; =============================================================================

(defn- active-database-adapter
  "Determine which database adapter is active from config.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Keyword adapter (:sqlite, :h2, :postgresql, :mysql) or nil"
  [config]
  (let [active-config (:active config)]
    (cond
      (:boundary/sqlite active-config) :sqlite
      (:boundary/h2 active-config) :h2
      (:boundary/postgresql active-config) :postgresql
      (:boundary/mysql active-config) :mysql
      :else nil)))

(defn db-adapter
  "Extract database adapter keyword from config.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Keyword adapter (:sqlite, :h2, :postgresql, :mysql)"
  [config]
  (or (active-database-adapter config)
      (throw (ex-info "No active database adapter found in configuration"
                      {:active-keys (keys (:active config))}))))

(defn db-spec
  "Extract database specification from config for the active adapter.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Database spec map appropriate for the adapter
   
   Example:
     {:adapter :sqlite :database-path \"dev-database.db\"}"
  [config]
  (let [adapter (db-adapter config)
        adapter-key (keyword "boundary" (name adapter))
        adapter-config (get-in config [:active adapter-key])]

    (when-not adapter-config
      (throw (ex-info "No configuration found for active adapter"
                      {:adapter adapter
                       :adapter-key adapter-key})))

    (case adapter
      :sqlite
      {:adapter :sqlite
       :database-path (:db adapter-config)
       :pool (:pool adapter-config)}

      :h2
      {:adapter :h2
       :database-path (if (:memory adapter-config)
                        "mem:boundary;DB_CLOSE_DELAY=-1"
                        (:db adapter-config))
       :pool (:pool adapter-config)}

      :postgresql
      {:adapter :postgresql
       :host (:host adapter-config)
       :port (:port adapter-config)
       :name (:dbname adapter-config)
       :username (:user adapter-config)
       :password (:password adapter-config)
       :pool (:pool adapter-config)}

      :mysql
      {:adapter :mysql
       :host (:host adapter-config)
       :port (:port adapter-config)
       :name (:dbname adapter-config)
       :username (:user adapter-config)
       :password (:password adapter-config)
       :pool (:pool adapter-config)}

      (throw (ex-info "Unsupported database adapter"
                      {:adapter adapter
                       :supported [:sqlite :h2 :postgresql :mysql]})))))

(defn http-config
  "Extract HTTP server configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with :port, :host, :join?, and :port-range keys"
  [config]
  (let [http-cfg (get-in config [:active :boundary/http])]
    {:port (or (:port http-cfg) 3000)
     :host (or (:host http-cfg) "0.0.0.0")
     :join? (or (:join? http-cfg) false)
     :port-range (:port-range http-cfg)}))

(defn app-config
  "Extract application-level configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with application settings"
  [config]
  (get-in config [:active :boundary/settings] {}))

(defn default-tenant-id
  "Extract default tenant ID for development/testing.
   
   This provides a consistent tenant context for:
   - REPL development and testing
   - CLI operations without explicit tenant specification  
   - Default test fixtures
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     UUID string of default tenant ID
   
   Note:
     Production systems should NOT rely on defaults and must
     always specify tenant-id explicitly in requests."
  [config]
  (get-in config [:active :boundary/settings :default-tenant-id]))

(defn user-validation-config
  "Extract user validation configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with user validation settings including:
     - :email-domain-allowlist - Set of allowed email domains
     - :password-policy - Password complexity requirements
     - :name-restrictions - Name validation rules
     - :role-restrictions - Role assignment rules
     - :tenant-limits - Per-tenant user limits
     - :cross-field-validation - Cross-field validation rules"
  [config]
  (get-in config [:active :boundary/settings :user-validation] {}))

(defn logging-config
  "Extract logging configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with logging provider and settings"
  [config]
  (get-in config [:active :boundary/logging] {:provider :no-op}))

(defn metrics-config
  "Extract metrics configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with metrics provider and settings"
  [config]
  (get-in config [:active :boundary/metrics] {:provider :no-op}))

(defn error-reporting-config
  "Extract error reporting configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with error reporting provider and settings"
  [config]
  (get-in config [:active :boundary/error-reporting] {:provider :no-op}))

;; =============================================================================
;; Integrant Configuration Generation
;; =============================================================================

(defn- core-system-config
  "Return core system components (database, observability) independent of modules."
  [config]
  (let [db-cfg (db-spec config)
        logging-cfg (logging-config config)
        metrics-cfg (metrics-config config)
        error-reporting-cfg (error-reporting-config config)]
    {:boundary/db-context db-cfg
     :boundary/logging logging-cfg
     :boundary/metrics metrics-cfg
     :boundary/error-reporting error-reporting-cfg}))

(defn- user-module-config
  "Return Integrant configuration for the user module.
   
   This wiring is specific to the user module and includes:
   - Database schema initialization
   - User and session repositories
   - User service
   - User module routes (structured format: {:api :web :static})
   - Top-level HTTP handler (composes routes from all modules)
   - HTTP server
   
   Future modules should follow this pattern: define a *-module-config function
   that returns a partial Integrant map and merge it into ig-config."
  [config]
  (let [http-cfg (http-config config)
        validation-cfg (user-validation-config config)]
    {:boundary/user-db-schema
     {:ctx (ig/ref :boundary/db-context)}

     :boundary/user-repository
     {:ctx (ig/ref :boundary/db-context)}

     :boundary/session-repository
     {:ctx (ig/ref :boundary/db-context)}

     :boundary/audit-repository
     {:ctx (ig/ref :boundary/db-context)}

     :boundary/auth-service
     {:user-repository (ig/ref :boundary/user-repository)
      :session-repository (ig/ref :boundary/session-repository)
      :auth-config {}} ; Add actual auth config if needed

     :boundary/user-service
     {:user-repository (ig/ref :boundary/user-repository)
      :session-repository (ig/ref :boundary/session-repository)
      :audit-repository (ig/ref :boundary/audit-repository)
      :validation-config validation-cfg
      :auth-service (ig/ref :boundary/auth-service)
      :logger (ig/ref :boundary/logging)
      :metrics (ig/ref :boundary/metrics)
      :error-reporter (ig/ref :boundary/error-reporting)}

     :boundary/user-routes
     {:user-service (ig/ref :boundary/user-service)
      :config config}

     :boundary/http-handler
     {:config config
      :user-routes (ig/ref :boundary/user-routes)
      :inventory-routes (ig/ref :boundary/inventory-routes)}

     :boundary/http-server
     (merge http-cfg
            {:handler (ig/ref :boundary/http-handler)
             :config http-cfg})}))

(defn- inventory-module-config
  "Return Integrant configuration for the inventory module."
  [config]
  {:boundary/inventory-repository
   {:ctx (ig/ref :boundary/db-context)}

   :boundary/inventory-service
   {:repository (ig/ref :boundary/inventory-repository)}

   :boundary/inventory-routes
   {:service (ig/ref :boundary/inventory-service)
    :config config}})

(defn ig-config
  "Generate Integrant configuration map from loaded config.
   
   The configuration is composed from:
   - Core system components (database, observability)
   - Module-specific components (:user, :inventory)
   
   Future modules can be added by:
   1. Creating a *-module-config function (like user-module-config)
   2. Merging it into the final config map
   3. Ensuring the module's wiring namespace is required
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Integrant config map ready for integrant.core/init
   
   Example:
     (def config (load-config))
     (def ig-cfg (ig-config config))
     (integrant.core/init ig-cfg)"
  [config]
  (merge (core-system-config config)
         (user-module-config config)
         (inventory-module-config config)))

;; =============================================================================
;; REPL Utilities
;; =============================================================================

(comment
  ;; Load configuration
  (def config (load-config))

  ;; Check active adapter
  (db-adapter config)

  ;; Extract database spec
  (db-spec config)

  ;; HTTP config
  (http-config config)

  ;; Get default tenant ID for development
  (default-tenant-id config)
  ;; => "00000000-0000-0000-0000-000000000001" (or value from DEFAULT_TENANT_ID env var)

  ;; Use default tenant ID in REPL development
  (require '[boundary.shared.core.utils.type-conversion :as tc])
  (def tenant-id (tc/string->uuid (default-tenant-id config)))
  ;; => #uuid "00000000-0000-0000-0000-000000000001"

  ;; Generate Integrant config
  (def ig-cfg (ig-config config))

  ;; Initialize system
  (def system (ig/init ig-cfg))

  ;; Halt system
  (ig/halt! system)
  ...)


