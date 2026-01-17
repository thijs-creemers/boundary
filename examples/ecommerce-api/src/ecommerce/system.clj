(ns ecommerce.system
  "Integrant system configuration for e-commerce API."
  (:require [integrant.core :as ig]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [ring.adapter.jetty :as jetty]
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
            [ecommerce.shared.http.middleware :as middleware])
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

(defmethod ig/init-key :ecommerce/datasource [_ config]
  (println "Initializing datasource:" (:dbname config))
  (let [ds (connection/->pool HikariDataSource config)]
    ;; Run migrations
    (run-migrations! ds)
    ds))

(defmethod ig/halt-key! :ecommerce/datasource [_ datasource]
  (println "Closing datasource")
  (.close datasource))

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

(defmethod ig/init-key :ecommerce/http-server [_ {:keys [config services]}]
  (let [{:keys [product-service cart-service order-service payment-service]} services
        payment-config (:payment config)
        routes (vec (concat
                     (product-http/routes product-service)
                     (cart-http/routes cart-service)
                     (order-http/routes order-service)
                     (payment-http/routes payment-service payment-config)))
        router (ring/router routes {:conflicts nil})
        handler (-> (ring/ring-handler
                     router
                     (ring/create-default-handler))
                    middleware/wrap-json-body
                    middleware/wrap-session-id
                    middleware/wrap-cors
                    middleware/wrap-exception-handler
                    middleware/wrap-request-logging)
        server-config (:server config)]
    (println "Starting HTTP server on port" (:port server-config))
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
                               :order-service (ig/ref :ecommerce/order-service)}
   
   :ecommerce/http-server {:config config
                           :services {:product-service (ig/ref :ecommerce/product-service)
                                      :cart-service (ig/ref :ecommerce/cart-service)
                                      :order-service (ig/ref :ecommerce/order-service)
                                      :payment-service (ig/ref :ecommerce/payment-service)}}})

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
