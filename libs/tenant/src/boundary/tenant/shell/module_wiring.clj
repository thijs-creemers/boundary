(ns boundary.tenant.shell.module-wiring
  (:require [boundary.tenant.shell.persistence :as tenant-persistence]
            [boundary.tenant.shell.service :as tenant-service]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :boundary/tenant-repository
  [_ {:keys [ctx logger error-reporter]}]
  (log/info "Initializing tenant repository")
  (let [repo (tenant-persistence/create-tenant-repository ctx logger error-reporter)]
    (log/info "Tenant repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/tenant-repository
  [_ _repo]
  (log/info "Tenant repository halted (no cleanup needed)"))

(defmethod ig/init-key :boundary/tenant-service
  [_ {:keys [tenant-repository validation-config logger metrics-emitter error-reporter]}]
  (log/info "Initializing tenant service")
  (let [service (tenant-service/create-tenant-service 
                  tenant-repository 
                  validation-config 
                  logger 
                  metrics-emitter 
                  error-reporter)]
    (log/info "Tenant service initialized")
    service))

(defmethod ig/halt-key! :boundary/tenant-service
  [_ _service]
  (log/info "Tenant service halted (no cleanup needed)"))

;; =============================================================================
;; Tenant Routes (Structured Format for Top-Level Composition)
;; =============================================================================

(defmethod ig/init-key :boundary/tenant-routes
  [_ {:keys [tenant-service config]}]
  (log/info "Initializing tenant module routes (normalized format)")
  (require 'boundary.tenant.shell.http)
  (let [tenant-routes-fn (ns-resolve 'boundary.tenant.shell.http 'tenant-routes-normalized)
        routes (tenant-routes-fn tenant-service (or config {}))]
    (log/info "Tenant module routes initialized successfully"
              {:route-keys (keys routes)
               :api-count (count (:api routes))})
    routes))

(defmethod ig/halt-key! :boundary/tenant-routes
  [_ _routes]
  (log/info "Tenant module routes halted (no cleanup needed)"))
