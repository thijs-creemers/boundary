(ns boundary.inventory.shell.module-wiring
  "Integrant wiring for the inventory module."
  (:require [boundary.inventory.shell.persistence :as inventory-persistence]
            [boundary.inventory.shell.service :as inventory-service]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Inventory Repository
;; =============================================================================

(defmethod ig/init-key :boundary/inventory-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing inventory repository")
  (let [repo (inventory-persistence/create-repository ctx)]
    (log/info "Inventory repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/inventory-repository
  [_ _repo]
  (log/info "Inventory repository halted (no cleanup needed)"))

;; =============================================================================
;; Inventory Service
;; =============================================================================

(defmethod ig/init-key :boundary/inventory-service
  [_ {:keys [repository]}]
  (log/info "Initializing inventory service")
  (let [service (inventory-service/create-service repository)]
    (log/info "Inventory service initialized")
    service))

(defmethod ig/halt-key! :boundary/inventory-service
  [_ _service]
  (log/info "Inventory service halted (no cleanup needed)"))

;; =============================================================================
;; Inventory Routes
;; =============================================================================

(defmethod ig/init-key :boundary/inventory-routes
  [_ {:keys [service config]}]
  (log/info "Initializing inventory module routes (normalized format)")
  (require 'boundary.inventory.shell.http)
  (let [routes-fn (ns-resolve 'boundary.inventory.shell.http 'inventory-routes-normalized)
        routes (routes-fn service config)]
    (log/info "Inventory module routes initialized successfully"
              {:route-keys (keys routes)
               :api-count (count (:api routes))
               :web-count (count (:web routes))})
    routes))

(defmethod ig/halt-key! :boundary/inventory-routes
  [_ _routes]
  (log/info "Inventory module routes halted (no cleanup needed)"))
