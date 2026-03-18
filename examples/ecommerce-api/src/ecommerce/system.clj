(ns ecommerce.system
  "Integrant system configuration for e-commerce API."
  (:require [integrant.core :as ig]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [reitit.ring :as ring]
            ;; Modules
            [ecommerce.product.shell.persistence :as product-persistence]
            [ecommerce.product.shell.service :as product-service]
            [ecommerce.product.shell.http :as product-http]
            [ecommerce.cart.shell.persistence :as cart-persistence]
            [ecommerce.cart.shell.service :as cart-service]
            [ecommerce.cart.shell.http :as cart-http]
            [ecommerce.order.shell.persistence :as order-persistence]
            [ecommerce.order.shell.service :as order-service]
            [ecommerce.order.shell.http :as order-http]
            [ecommerce.payment.shell.provider :as payment-provider]
            [ecommerce.payment.shell.service :as payment-service]
            [ecommerce.payment.shell.http :as payment-http]
            ;; Middleware
            [ecommerce.shared.http.middleware :as middleware]
            ;; Boundary user module — requires load ig/init-key multimethods
            [boundary.user.shell.module-wiring]
            ;; Boundary admin module — requires load ig/init-key multimethods
            [boundary.admin.shell.module-wiring]
            ;; Boundary admin HTTP (for wrap-method-override)
            [boundary.admin.shell.http :as admin-http]
            ;; Boundary platform db factory
            [boundary.platform.shell.adapters.database.factory :as db-factory]
            ;; Observability no-op adapters
            [boundary.observability.logging.shell.adapters.no-op :as no-op-logging]
            [boundary.observability.errors.shell.adapters.no-op :as no-op-errors])
  (:import [com.zaxxer.hikari HikariDataSource]))

;; =============================================================================
;; Configuration
;; =============================================================================

(defn load-config
  "Load configuration from EDN file."
  ([]
   (load-config :dev))
  ([profile]
   (let [config-file (io/resource (str "config/" (name profile) ".edn"))]
     (if config-file
       (aero/read-config config-file)
       (throw (ex-info "Config file not found" {:profile profile}))))))

;; =============================================================================
;; Database
;; =============================================================================

(defn run-migrations!
  "Run SQL migrations from migrations/ directory."
  [datasource]
  (println "Running migrations...")
  (let [migrations-dir (io/file "migrations")
        migration-files (when (.exists migrations-dir)
                          (->> (.listFiles migrations-dir)
                               (filter #(.endsWith (.getName %) ".sql"))
                               (sort-by #(.getName %))))]
    (doseq [file migration-files]
      (println "  Running:" (.getName file))
      (let [sql (slurp file)]
        ;; Split by semicolon and execute each statement
        (doseq [statement (clojure.string/split sql #";\s*\n")]
          (when-not (clojure.string/blank? statement)
            (try
              (jdbc/execute! datasource [statement])
              (catch Exception e
                ;; Ignore "already exists" errors for idempotency
                (when-not (re-find #"already exists|duplicate|UNIQUE constraint failed"
                                   (.getMessage e))
                  (throw e))))))))))

(defmethod ig/init-key :ecommerce/datasource [_ config]
  (println "Initializing datasource:" (:dbname config))
  (let [ds (connection/->pool HikariDataSource config)]
    ;; Run migrations
    (run-migrations! ds)
    ds))

(defmethod ig/halt-key! :ecommerce/datasource [_ datasource]
  (println "Closing datasource")
  (.close datasource))

;; =============================================================================
;; Boundary db-context (used by user + admin modules)
;; =============================================================================

(defmethod ig/init-key :ecommerce/db-context [_ db-config]
  (println "Initializing boundary db-context for admin/user modules")
  (db-factory/db-context db-config))

(defmethod ig/halt-key! :ecommerce/db-context [_ ctx]
  (println "Closing boundary db-context")
  (db-factory/close-db-context! ctx))

;; =============================================================================
;; Observability no-ops (required by admin service)
;; =============================================================================

(defmethod ig/init-key :ecommerce/logger [_ _]
  (no-op-logging/create-logging-component {}))

(defmethod ig/halt-key! :ecommerce/logger [_ _] nil)

(defmethod ig/init-key :ecommerce/error-reporter [_ _]
  (no-op-errors/create-error-reporting-component {}))

(defmethod ig/halt-key! :ecommerce/error-reporter [_ _] nil)

;; =============================================================================
;; Repositories
;; =============================================================================

(defmethod ig/init-key :ecommerce/product-repository [_ {:keys [datasource]}]
  (product-persistence/->SQLiteProductRepository datasource))

(defmethod ig/init-key :ecommerce/cart-repository [_ {:keys [datasource]}]
  (cart-persistence/->SQLiteCartRepository datasource))

(defmethod ig/init-key :ecommerce/order-repository [_ {:keys [datasource]}]
  (order-persistence/->SQLiteOrderRepository datasource))

;; =============================================================================
;; Payment Provider
;; =============================================================================

(defmethod ig/init-key :ecommerce/payment-provider [_ config]
  (println "Initializing payment provider:" (:provider config))
  (payment-provider/->MockPaymentProvider config))

;; =============================================================================
;; Services
;; =============================================================================

(defmethod ig/init-key :ecommerce/product-service [_ {:keys [repository]}]
  (product-service/->ProductService repository))

(defmethod ig/init-key :ecommerce/cart-service [_ {:keys [cart-repository product-repository]}]
  (cart-service/->CartService cart-repository product-repository))

(defmethod ig/init-key :ecommerce/order-service [_ {:keys [order-repository cart-repository product-repository]}]
  (order-service/->OrderService order-repository cart-repository product-repository))

(defmethod ig/init-key :ecommerce/payment-service [_ {:keys [provider order-service]}]
  (payment-service/->PaymentService provider order-service))

;; =============================================================================
;; HTTP Server
;; =============================================================================

(defn- normalized->reitit
  "Convert normalized boundary route specs to Reitit route vectors,
   prefixing all paths with base-path.

   Promotes :meta :middleware to route-level :middleware.
   Handles per-method :middleware for routes like logout.

   Input:  {:path \"/...\" :meta {:middleware [...]} :methods {:get {:handler fn} ...}}
   Output: [\"/...\" {:middleware [...] :get {:handler fn} ...}]"
  [base-path routes]
  (when (seq routes)
    (mapv (fn [{:keys [path meta methods]}]
            (let [full-path   (str base-path path)
                  route-mw    (:middleware meta)
                  methods-data (reduce-kv
                                (fn [acc method {:keys [handler middleware]}]
                                  (assoc acc method
                                         (cond-> {:handler handler}
                                           (seq middleware) (assoc :middleware middleware))))
                                {}
                                methods)
                  route-data  (cond-> methods-data
                                (seq route-mw) (assoc :middleware route-mw))]
              [full-path route-data]))
          routes)))

(defmethod ig/init-key :ecommerce/http-server
  [_ {:keys [config services admin-routes user-routes]}]
  (let [{:keys [product-service cart-service order-service payment-service]} services
        payment-config (:payment config)

        ;; Existing REST API routes (unchanged)
        api-routes (vec (concat
                         (product-http/routes product-service)
                         (cart-http/routes cart-service)
                         (order-http/routes order-service)
                         (payment-http/routes payment-service payment-config)))

        ;; Admin + user web routes — convert normalized format to Reitit
        user-web-routes  (or (:web user-routes) [])
        admin-web-routes (or (:web admin-routes) [])

        web-routes (vec (concat
                         (normalized->reitit "/web" user-web-routes)
                         ;; Convenience redirect: /web/admin → /web/admin/
                         [["/web/admin" {:get {:handler (fn [_]
                                                          {:status  302
                                                           :headers {"Location" "/web/admin/"}
                                                           :body    ""})}}]]
                         (normalized->reitit "/web/admin" admin-web-routes)))

        all-routes (vec (concat api-routes web-routes))

        router  (ring/router all-routes {:conflicts nil})
        handler (-> (ring/ring-handler router (ring/create-default-handler))
                    admin-http/wrap-method-override  ; reads :form-params for PUT/DELETE override
                    wrap-params                      ; parse form + query params
                    wrap-cookies                     ; parse cookies (for session auth)
                    middleware/wrap-json-body
                    middleware/wrap-session-id
                    middleware/wrap-cors
                    (wrap-resource "public")         ; serve CSS/JS/assets from resources/public/
                    middleware/wrap-exception-handler
                    middleware/wrap-request-logging)
        server-config (:server config)]
    (println "Starting HTTP server on port" (:port server-config))
    (println "Admin UI: http://localhost:" (:port server-config) "/web/admin/")
    (jetty/run-jetty handler server-config)))

(defmethod ig/halt-key! :ecommerce/http-server [_ server]
  (println "Stopping HTTP server")
  (.stop server))

;; =============================================================================
;; System Configuration
;; =============================================================================

(defn system-config
  "Build Integrant system configuration."
  [config]
  (merge
   ;; Ecommerce datasource + repositories + services (existing)
   {:ecommerce/datasource (:database config)

    :ecommerce/product-repository {:datasource (ig/ref :ecommerce/datasource)}
    :ecommerce/cart-repository {:datasource (ig/ref :ecommerce/datasource)}
    :ecommerce/order-repository {:datasource (ig/ref :ecommerce/datasource)}

    :ecommerce/payment-provider (:payment config)

    :ecommerce/product-service {:repository (ig/ref :ecommerce/product-repository)}
    :ecommerce/cart-service {:cart-repository (ig/ref :ecommerce/cart-repository)
                             :product-repository (ig/ref :ecommerce/product-repository)}
    :ecommerce/order-service {:order-repository (ig/ref :ecommerce/order-repository)
                              :cart-repository (ig/ref :ecommerce/cart-repository)
                              :product-repository (ig/ref :ecommerce/product-repository)}
    :ecommerce/payment-service {:provider (ig/ref :ecommerce/payment-provider)
                                :order-service (ig/ref :ecommerce/order-service)}}

   ;; Boundary db-context (separate pool for user + admin modules)
   {:ecommerce/db-context (:db-context config)
    :ecommerce/logger     {}
    :ecommerce/error-reporter {}}

   ;; User module (ig/init-key registered by boundary.user.shell.module-wiring)
   {:boundary/user-db-schema     {:ctx (ig/ref :ecommerce/db-context)}
    :boundary/user-repository    {:ctx (ig/ref :ecommerce/db-context)}
    :boundary/session-repository {:ctx (ig/ref :ecommerce/db-context)}
    :boundary/audit-repository   {:ctx (ig/ref :ecommerce/db-context)
                                  :pagination-config {:default-limit 20}}
    :boundary/mfa-service        {:user-repository (ig/ref :boundary/user-repository)
                                  :mfa-config {}}
    :boundary/auth-service       {:user-repository    (ig/ref :boundary/user-repository)
                                  :session-repository (ig/ref :boundary/session-repository)
                                  :mfa-service        (ig/ref :boundary/mfa-service)
                                  :auth-config        {}}
    :boundary/user-service       {:user-repository    (ig/ref :boundary/user-repository)
                                  :session-repository (ig/ref :boundary/session-repository)
                                  :audit-repository   (ig/ref :boundary/audit-repository)
                                  :validation-config  (get-in config [:user :validation])
                                  :auth-service       (ig/ref :boundary/auth-service)}
    :boundary/user-routes        {:user-service (ig/ref :boundary/user-service)
                                  :mfa-service  (ig/ref :boundary/mfa-service)
                                  :config       (get config :user {})}}

   ;; Admin module (ig/init-key registered by boundary.admin.shell.module-wiring)
   {:boundary/admin              (:boundary/admin config)
    :boundary/admin-schema-provider {:db-ctx (ig/ref :ecommerce/db-context)
                                     :config (ig/ref :boundary/admin)}
    :boundary/admin-service         {:db-ctx          (ig/ref :ecommerce/db-context)
                                     :schema-provider (ig/ref :boundary/admin-schema-provider)
                                     :logger          (ig/ref :ecommerce/logger)
                                     :error-reporter  (ig/ref :ecommerce/error-reporter)
                                     :config          (ig/ref :boundary/admin)}
    :boundary/admin-routes          {:admin-service   (ig/ref :boundary/admin-service)
                                     :schema-provider (ig/ref :boundary/admin-schema-provider)
                                     :user-service    (ig/ref :boundary/user-service)
                                     :config          (ig/ref :boundary/admin)}}

   ;; HTTP server — now includes admin + user web routes
   {:ecommerce/http-server {:config       config
                            :admin-routes (ig/ref :boundary/admin-routes)
                            :user-routes  (ig/ref :boundary/user-routes)
                            :services     {:product-service (ig/ref :ecommerce/product-service)
                                           :cart-service    (ig/ref :ecommerce/cart-service)
                                           :order-service   (ig/ref :ecommerce/order-service)
                                           :payment-service (ig/ref :ecommerce/payment-service)}}}))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defonce ^:private system (atom nil))

(defn start!
  "Start the system."
  ([]
   (start! (load-config)))
  ([config]
   (let [sys (ig/init (system-config config))]
     (reset! system sys)
     sys)))

(defn stop!
  "Stop the system."
  ([]
   (when @system
     (stop! @system)))
  ([sys]
   (ig/halt! sys)
   (reset! system nil)))

(defn restart!
  "Restart the system."
  []
  (stop!)
  (start!))
