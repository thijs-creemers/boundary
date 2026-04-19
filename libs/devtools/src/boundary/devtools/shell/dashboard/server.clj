(ns boundary.devtools.shell.dashboard.server
  "Integrant component for the dev dashboard HTTP server on port 9999."
  (:require [boundary.devtools.shell.dashboard.pages.overview :as overview]
            [boundary.devtools.shell.dashboard.pages.routes :as routes-page]
            [boundary.devtools.shell.dashboard.pages.requests :as requests-page]
            [boundary.devtools.shell.dashboard.pages.schemas :as schemas-page]
            [boundary.devtools.shell.dashboard.pages.database :as database-page]
            [boundary.devtools.shell.dashboard.pages.errors :as errors-page]
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

(defn- build-context [config]
  (let [sys (try @(resolve 'integrant.repl.state/system) (catch Exception _ nil))]
    {:system-status   (if sys :running :stopped)
     :component-count (if sys (count sys) 0)
     :error-count     0
     :http-port       (or (:http-port config) 3000)}))

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
        ["/dashboard/database"
         {:get (fn [_req]
                 (html-response (database-page/render (build-context config))))}]
        ["/dashboard/errors"
         {:get (fn [_req]
                 (html-response (errors-page/render (build-context config))))}]
        ["/dashboard/fragments/request-list"
         {:get (fn [_req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (requests-page/render-fragment)})}]
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
                   :body    (database-page/render-query-result req)})}]
        ["/dashboard/fragments/try-route"
         {:post (fn [req]
                  {:status  200
                   :headers {"Content-Type" "text/html; charset=utf-8"}
                   :body    (routes-page/render-try-result req)})}]])
      ring/ring-handler
      (wrap-resource "dashboard")
      wrap-content-type
      wrap-params))

(defmethod ig/init-key :boundary/dashboard [_ {:keys [port] :as config}]
  (let [port   (or port 9999)
        config (assoc config :port port)
        server (jetty/run-jetty (make-handler config) {:port port :join? false})]
    (log/infof "Dev dashboard started on http://localhost:%d/dashboard" port)
    {:server server :port port}))

(defmethod ig/halt-key! :boundary/dashboard [_ {:keys [server]}]
  (when server
    (.stop server)
    (log/info "Dev dashboard stopped")))
