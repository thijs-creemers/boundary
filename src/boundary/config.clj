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
     Map with :port, :host, and :join? keys"
  [config]
  (let [http-cfg (get-in config [:active :boundary/http])]
    {:port (or (:port http-cfg) 3000)
     :host (or (:host http-cfg) "0.0.0.0")
     :join? (or (:join? http-cfg) false)}))

(defn app-config
  "Extract application-level configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with application settings"
  [config]
  (get-in config [:active :boundary/settings] {}))

;; =============================================================================
;; Integrant Configuration Generation
;; =============================================================================

(defn ig-config
  "Generate Integrant configuration map from loaded config.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Integrant config map ready for integrant.core/init
   
   Example:
     (def config (load-config))
     (def ig-cfg (ig-config config))
     (integrant.core/init ig-cfg)"
  [config]
  (let [db-cfg (db-spec config)
        http-cfg (http-config config)]
    {:boundary/db-context
     db-cfg

     :boundary/user-repository
     {:ctx (ig/ref :boundary/db-context)}

     :boundary/session-repository
     {:ctx (ig/ref :boundary/db-context)}

     :boundary/user-service
     {:user-repository (ig/ref :boundary/user-repository)
      :session-repository (ig/ref :boundary/session-repository)}

     :boundary/http-handler
     {:user-service (ig/ref :boundary/user-service)
      :config config}

     :boundary/http-server
     (merge http-cfg
            {:handler (ig/ref :boundary/http-handler)})}))

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

  ;; Generate Integrant config
  (def ig-cfg (ig-config config))

  ;; Initialize system
  (def system (ig/init ig-cfg))

  ;; Halt systemu
  (ig/halt! system)
  ...)


