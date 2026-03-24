(ns boundary.tenant.shell.module-wiring
  (:require [boundary.tenant.shell.membership-persistence :as membership-persistence]
            [boundary.tenant.shell.membership-service :as membership-service]
            [boundary.tenant.shell.persistence :as tenant-persistence]
            [boundary.tenant.shell.service :as tenant-service]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Tenant Database Schema
;; =============================================================================

(defmethod ig/init-key :boundary/tenant-db-schema
  [_ {:keys [ctx]}]
  (log/info "Initializing tenant module database schema")
  (tenant-persistence/initialize-tenant-schema! ctx)
  (log/info "Tenant module database schema initialized")
  {:status :initialized})

(defmethod ig/halt-key! :boundary/tenant-db-schema
  [_ _state]
  (log/info "Tenant module database schema component halted"))

;; =============================================================================
;; Tenant Repository
;; =============================================================================

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
  [_ {:keys [tenant-service db-context config]}]
  (log/info "Initializing tenant module routes (normalized format)")
  (require 'boundary.tenant.shell.http)
  (let [tenant-routes-fn (ns-resolve 'boundary.tenant.shell.http 'tenant-routes-normalized)
        routes (tenant-routes-fn tenant-service db-context (or config {}))]
    (log/info "Tenant module routes initialized successfully"
              {:route-keys (keys routes)
               :api-count (count (:api routes))})
    routes))

(defmethod ig/halt-key! :boundary/tenant-routes
  [_ _routes]
  (log/info "Tenant module routes halted (no cleanup needed)"))

;; =============================================================================
;; Membership Repository
;; =============================================================================

(defmethod ig/init-key :boundary/membership-repository
  [_ {:keys [ctx logger error-reporter]}]
  (log/info "Initializing membership repository")
  (let [repo (membership-persistence/create-membership-repository ctx logger error-reporter)]
    (log/info "Membership repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/membership-repository
  [_ _repo]
  (log/info "Membership repository halted (no cleanup needed)"))

;; =============================================================================
;; Membership Service
;; =============================================================================

(defmethod ig/init-key :boundary/membership-service
  [_ {:keys [repository logger metrics-emitter error-reporter]}]
  (log/info "Initializing membership service")
  (let [service (membership-service/create-membership-service
                 repository
                 logger
                 metrics-emitter
                 error-reporter)]
    (log/info "Membership service initialized")
    service))

(defmethod ig/halt-key! :boundary/membership-service
  [_ _service]
  (log/info "Membership service halted (no cleanup needed)"))

;; =============================================================================
;; Membership Routes
;; =============================================================================

(defmethod ig/init-key :boundary/membership-routes
  [_ {:keys [service]}]
  (log/info "Initializing membership module routes (normalized format)")
  (require 'boundary.tenant.shell.membership-http)
  (let [routes-fn (ns-resolve 'boundary.tenant.shell.membership-http 'membership-routes-normalized)
        routes    (routes-fn service)]
    (log/info "Membership module routes initialized successfully"
              {:route-keys (keys routes)
               :api-count  (count (:api routes))})
    routes))

(defmethod ig/halt-key! :boundary/membership-routes
  [_ _routes]
  (log/info "Membership module routes halted (no cleanup needed)"))
