(ns boundary.devtools.shell.dashboard.server
  "Integrant component for the dev dashboard HTTP server on port 9999."
  (:require [boundary.devtools.shell.dashboard.pages.overview :as overview]
            [boundary.devtools.shell.dashboard.pages.routes :as routes-page]
            [boundary.devtools.shell.dashboard.pages.requests :as requests-page]
            [boundary.devtools.shell.dashboard.pages.schemas :as schemas-page]
            [boundary.devtools.shell.dashboard.pages.database :as database-page]
            [boundary.devtools.shell.dashboard.pages.errors :as errors-page]
            [boundary.devtools.shell.dashboard.pages.docs :as docs-page]
            [integrant.core :as ig]
            [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.tools.logging :as log]))

(defn- html-response [body]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    body})

(defn- jetty-port
  "Extract the actual listening port from a Jetty Server instance."
  [server]
  (try
    (when server
      (let [connector (first (.getConnectors server))]
        (when connector
          (.getLocalPort connector))))
    (catch Exception _ nil)))

(defn- build-context
  "Build a context map from the injected Integrant components.
   Falls back to integrant.repl.state/system for component count (full system view),
   but uses the injected refs for actual data access."
  [config]
  (let [sys          (try @(resolve 'integrant.repl.state/system) (catch Exception _ nil))
        http-handler (:http-handler config)
        http-server  (:http-server config)
        db-context   (:db-context config)
        app-port     (or (jetty-port http-server) (:http-port config) 3000)]
    {:system-status   (if (or sys http-handler) :running :stopped)
     :component-count (when sys (count sys))
     :error-count     (:total (errors-page/error-stats))
     :http-port       app-port
     :http-handler    http-handler
     :db-context      db-context}))

(defn- make-handler [config]
  (-> (ring/router
       [["/dashboard"
         {:get (fn [_req]
                 (html-response (overview/render (build-context config))))}]
        ["/dashboard/routes"
         {:get (fn [_req]
                 (html-response (routes-page/render (build-context config))))}]
        ["/dashboard/requests"
         {:get (fn [_req]
                 (html-response (requests-page/render (build-context config))))}]
        ["/dashboard/schemas"
         {:get (fn [req]
                 (html-response (schemas-page/render (build-context config) req)))}]
        ["/dashboard/db"
         {:get (fn [_req]
                 (html-response (database-page/render (build-context config))))}]
        ["/dashboard/errors"
         {:get (fn [_req]
                 (html-response (errors-page/render (build-context config))))}]
        ["/dashboard/docs"
         {:get (fn [_req]
                 (html-response (docs-page/render-index (build-context config))))}]
        ["/dashboard/docs/:module/:file"
         {:get (fn [req]
                 (let [module (get-in req [:path-params :module])
                       file   (get-in req [:path-params :file])]
                   (html-response (docs-page/render (build-context config) module file))))}]
        ["/dashboard/fragments/request-list"
         {:get (fn [req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (requests-page/render-fragment req)})}]
        ["/dashboard/fragments/request-detail"
         {:get (fn [req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (requests-page/render-detail-fragment req)})}]
        ["/dashboard/fragments/error-list"
         {:get (fn [_req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (errors-page/render-fragment)})}]
        ["/dashboard/fragments/pool-status"
         {:get (fn [_req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (database-page/render-pool-fragment (build-context config))})}]
        ["/dashboard/fragments/query-result"
         {:post (fn [req]
                  {:status  200
                   :headers {"Content-Type" "text/html; charset=utf-8"}
                   :body    (database-page/render-query-result (merge req (select-keys (build-context config) [:db-context])))})}]
        ["/dashboard/fragments/try-route"
         {:post (fn [req]
                  {:status  200
                   :headers {"Content-Type" "text/html; charset=utf-8"}
                   :body    (routes-page/render-try-result (merge req (select-keys (build-context config) [:http-handler])))})}]
        ["/dashboard/fragments/routes-table"
         {:get (fn [req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (routes-page/render-table-fragment (merge req (select-keys (build-context config) [:http-handler])))})}]
        ["/dashboard/fragments/route-inspect"
         {:get (fn [req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (routes-page/render-inspect-fragment req)})}]])
      ring/ring-handler
      (wrap-resource "dashboard")
      (wrap-resource "public")
      wrap-content-type
      wrap-params))

(defn- try-start-jetty
  "Attempt to start Jetty on the given port. On BindException, try up to
   max-port before giving up. Returns {:server s :port p} or nil."
  [handler host port max-port]
  (loop [p port]
    (when (<= p max-port)
      (let [result (try
                     {:server (jetty/run-jetty handler {:port p :host host :join? false})
                      :port   p}
                     (catch java.net.BindException _
                       (log/debugf "Dashboard port %d in use, trying %d" p (inc p))
                       nil))]
        (or result (recur (inc p)))))))

(defmethod ig/init-key :boundary/dashboard [_ {:keys [port host] :as config}]
  (let [port   (or port 9999)
        host   (or host "127.0.0.1")
        result (try-start-jetty (make-handler config) host port (+ port 10))]
    (if result
      (do
        (log/infof "Dev dashboard started on http://%s:%d/dashboard" host (:port result))
        (when (not= port (:port result))
          (log/warnf "Dashboard port %d was busy, using %d instead" port (:port result)))
        result)
      (do
        (log/warnf "Could not start dev dashboard — ports %d–%d all in use" port (+ port 10))
        nil))))

(defmethod ig/halt-key! :boundary/dashboard [_ {:keys [server]}]
  (when server
    (.stop server)
    (schemas-page/reset-schemas!)
    (log/info "Dev dashboard stopped")))
