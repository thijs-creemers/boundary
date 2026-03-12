(ns boundary.workflow.shell.module-wiring
  "Integrant lifecycle management for the workflow module.

   Config keys:

   :boundary/workflow
     Minimal config (no side-effects):
       {:db-ctx (ig/ref :boundary/db-context)}

     Full config (with jobs side-effects):
       {:db-ctx        (ig/ref :boundary/db-context)
        :job-queue     (ig/ref :boundary/job-queue)
        :guard-registry {}}

   :boundary/workflow-routes
     {:workflow-service (ig/ref :boundary/workflow)
      :user-service     (ig/ref :boundary/user-service)}

     Returns {:api [...] :web [...] :static []} for composition
     by the HTTP handler."
  (:require [integrant.core :as ig]
            [boundary.workflow.core.machine :as machine]
            [boundary.workflow.shell.persistence :as persistence]
            [boundary.workflow.shell.service :as service]
            [boundary.workflow.shell.http :as workflow-http]
            [clojure.tools.logging :as log]))

(defmethod ig/init-key :boundary/workflow
  [_ {:keys [db-ctx job-queue guard-registry]}]
  (log/info "Initializing workflow component")
  (let [datasource (:datasource db-ctx)
        store      (persistence/create-workflow-store datasource)
        registry   (machine/create-workflow-registry)
        engine     (service/create-workflow-service store registry job-queue guard-registry)]
    (log/info "Workflow component initialized")
    {:store    store
     :registry registry
     :engine   engine}))

(defmethod ig/halt-key! :boundary/workflow
  [_ _component]
  (log/info "Halting workflow component")
  nil)

;; =============================================================================
;; Workflow Routes Component
;; =============================================================================

(defmethod ig/init-key :boundary/workflow-routes
  [_ {:keys [workflow-service user-service]}]
  (log/info "Initializing workflow routes")
  {:api    (workflow-http/workflow-routes
            (:engine workflow-service))
   :web    (workflow-http/workflow-web-routes
            (:store workflow-service)
            (:registry workflow-service)
            user-service)
   :static []})

(defmethod ig/halt-key! :boundary/workflow-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup needed
  nil)
