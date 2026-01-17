(ns blog.system
  "Integrant system configuration for the blog application.
   
   Defines all system components and their dependencies:
   - Database connection
   - Post repository and service
   - HTTP router and server"
  (:require [integrant.core :as ig]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [blog.post.shell.persistence :as post-persistence]
            [blog.post.shell.service :as post-service]
            [blog.post.shell.http :as post-http])
  (:import [com.zaxxer.hikari HikariDataSource]))

;; =============================================================================
;; Configuration
;; =============================================================================

(defn load-config
  "Load configuration from resources/config/{profile}.edn"
  ([]
   (load-config :dev))
  ([profile]
   (aero/read-config (io/resource (str "config/" (name profile) ".edn")))))

(defn system-config
  "Build Integrant system configuration.
   
   Args:
     profile: Configuration profile (:dev, :test, :prod)
     
   Returns:
     Integrant configuration map."
  ([]
   (system-config :dev))
  ([profile]
   (let [config (load-config profile)]
     {;; Database connection pool
      :blog/datasource {:db-spec (:database config)}
      
      ;; Post module
      :blog/post-repository {:datasource (ig/ref :blog/datasource)}
      :blog/post-service {:repository (ig/ref :blog/post-repository)}
      
      ;; HTTP router
      :blog/router {:post-service (ig/ref :blog/post-service)
                    :blog-config (:blog config)}
      
      ;; HTTP server
      :blog/server {:router (ig/ref :blog/router)
                    :server-config (:server config)}})))

;; =============================================================================
;; Database
;; =============================================================================

(defmethod ig/init-key :blog/datasource
  [_ {:keys [db-spec]}]
  (println "Starting database connection...")
  (let [ds (jdbc/get-datasource db-spec)]
    ;; Run migrations on startup
    (println "Running migrations...")
    (doseq [migration-file (sort (filter #(.endsWith (.getName %) ".sql")
                                         (.listFiles (io/file "migrations"))))]
      (println "  Applying:" (.getName migration-file))
      (jdbc/execute! ds [(slurp migration-file)]))
    ds))

(defmethod ig/halt-key! :blog/datasource
  [_ datasource]
  (println "Stopping database connection...")
  (when (instance? HikariDataSource datasource)
    (.close ^HikariDataSource datasource)))

;; =============================================================================
;; Post Module
;; =============================================================================

(defmethod ig/init-key :blog/post-repository
  [_ {:keys [datasource]}]
  (println "Initializing post repository...")
  (post-persistence/create-post-repository datasource))

(defmethod ig/init-key :blog/post-service
  [_ {:keys [repository]}]
  (println "Initializing post service...")
  (post-service/create-post-service repository))

;; =============================================================================
;; HTTP
;; =============================================================================

(defn wrap-exceptions
  "Middleware to catch and handle exceptions."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (println "Error handling request:" (.getMessage e))
        {:status 500
         :headers {"Content-Type" "text/html"}
         :body "<h1>500 Internal Server Error</h1>"}))))

(defmethod ig/init-key :blog/router
  [_ {:keys [post-service blog-config]}]
  (println "Building HTTP router...")
  (ring/ring-handler
   (ring/router
    (post-http/routes post-service blog-config)
    ;; Allow conflicting routes - /new is matched before /:id
    {:conflicts nil})
   (ring/routes
    ;; Static files
    (ring/create-resource-handler {:path "/" :root "public"})
    ;; Default 404
    (ring/create-default-handler
     {:not-found (constantly {:status 404
                              :headers {"Content-Type" "text/html"}
                              :body "<h1>404 Not Found</h1>"})}))
   {:middleware [[wrap-exceptions]
                 [params/wrap-params]
                 [keyword-params/wrap-keyword-params]]}))

(defmethod ig/init-key :blog/server
  [_ {:keys [router server-config]}]
  (let [port (:port server-config 3001)]
    (println (str "Starting HTTP server on port " port "..."))
    (jetty/run-jetty router {:port port
                             :join? (:join? server-config false)})))

(defmethod ig/halt-key! :blog/server
  [_ server]
  (println "Stopping HTTP server...")
  (.stop server))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn start!
  "Start the system with the given profile."
  ([]
   (start! :dev))
  ([profile]
   (ig/init (system-config profile))))

(defn stop!
  "Stop the running system."
  [system]
  (ig/halt! system))
