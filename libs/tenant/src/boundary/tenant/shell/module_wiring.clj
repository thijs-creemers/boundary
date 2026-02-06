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
