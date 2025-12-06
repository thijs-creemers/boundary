(ns boundary.inventory.shell.http
  "HTTP routes for inventory module."
  (:require [boundary.inventory.ports :as ports]))

;; =============================================================================
;; Legacy Reitit Routes
;; =============================================================================

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

;; =============================================================================
;; Normalized Routes
;; =============================================================================

(defn normalized-api-routes
  "Define API routes in normalized format.
   
   Args:
     service: Inventory service instance
     
   Returns:
     Vector of normalized route maps"
  [service]
  [{:path "/items"
    :methods {:get {:handler (fn [req] {:status 200 :body []})}
              :post {:handler (fn [req] {:status 201 :body {}})}}}
   {:path "/items/:id"
    :methods {:get {:handler (fn [req] {:status 200 :body {}})}
              :put {:handler (fn [req] {:status 200 :body {}})}
              :delete {:handler (fn [req] {:status 204})}}}])

(defn normalized-web-routes
  "Define web UI routes in normalized format (WITHOUT /web prefix).
   
   NOTE: These routes will be mounted under /web by the top-level router.
   Do NOT include /web prefix in paths here.
   
   Args:
     service: Inventory service instance
     config: Application configuration map
     
   Returns:
     Vector of normalized route maps"
  [service config]
  [{:path "/items"
    :methods {:get {:handler (fn [req] {:status 200 :body "<html><body>Web UI</body></html>"})}}}])

(defn inventory-routes-normalized
  "Define inventory module routes in normalized format for top-level composition.
   
   Returns a map with route categories:
   - :api - REST API routes (will be mounted under /api)
   - :web - Web UI routes (will be mounted under /web)
   - :static - Static asset routes (empty)
   
   Args:
     service: Inventory service instance
     config: Application configuration map

   Returns:
     Map with keys :api, :web, :static containing normalized route vectors"
  [service config]
  {:api (normalized-api-routes service)
   :web (normalized-web-routes service config)
   :static []})

