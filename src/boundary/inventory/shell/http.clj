(ns boundary.inventory.shell.http
  "HTTP routes for inventory module."
  (:require [boundary.inventory.ports :as ports]))

(defn api-routes [service]
  [["/api/items" {:get {:handler (fn [req] {:status 200 :body []})}
                          :post {:handler (fn [req] {:status 201 :body {}})}}]
   ["/api/items/:id" {:get {:handler (fn [req] {:status 200 :body {}})}
                                :put {:handler (fn [req] {:status 200 :body {}})}
                                :delete {:handler (fn [req] {:status 204})}}]])

(defn web-routes [service config]
  [["/web/items" {:get {:handler (fn [req] {:status 200 :body "<html><body>Web UI</body></html>"})}}]])

(defn routes [service config]
  (vec (concat (api-routes service) (web-routes service config))))
