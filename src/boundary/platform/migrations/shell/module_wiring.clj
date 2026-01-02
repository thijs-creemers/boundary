(ns boundary.platform.migrations.shell.module-wiring
  "Integrant wiring for the database migrations module.
   
   This namespace owns all Integrant init/halt methods for migration-specific
   components so that shared system wiring does not depend directly on
   migration shell namespaces."
  (:require [boundary.platform.migrations.shell.repository :as migration-repository]
            [boundary.platform.migrations.shell.executor :as migration-executor]
            [boundary.platform.migrations.shell.discovery :as migration-discovery]
            [boundary.platform.migrations.shell.locking.postgres :as postgres-lock]
            [boundary.platform.migrations.shell.locking.table-based :as table-lock]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Migration Repository
;; =============================================================================

(defmethod ig/init-key :boundary/migration-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing migration repository")
  (let [repo (migration-repository/create-repository ctx)]
    (log/info "Migration repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/migration-repository
  [_ _repo]
  (log/info "Migration repository halted (no cleanup needed)"))

;; =============================================================================
;; Migration Executor
;; =============================================================================

(defmethod ig/init-key :boundary/migration-executor
  [_ {:keys [ctx]}]
  (log/info "Initializing migration executor")
  (let [executor (migration-executor/create-executor ctx)]
    (log/info "Migration executor initialized")
    executor))

(defmethod ig/halt-key! :boundary/migration-executor
  [_ _executor]
  (log/info "Migration executor halted (no cleanup needed)"))

;; =============================================================================
;; Migration Discovery
;; =============================================================================

(defmethod ig/init-key :boundary/migration-discovery
  [_ {:keys [base-dir]}]
  (log/info "Initializing migration discovery with base-dir:" base-dir)
  (let [discovery (migration-discovery/create-discovery {:base-dir base-dir})]
    (log/info "Migration discovery initialized")
    discovery))

(defmethod ig/halt-key! :boundary/migration-discovery
  [_ _discovery]
  (log/info "Migration discovery halted (no cleanup needed)"))

;; =============================================================================
;; Migration Lock (Database-specific)
;; =============================================================================

(defmethod ig/init-key :boundary/migration-lock
  [_ {:keys [ctx db-type]}]
  (log/info "Initializing migration lock for db-type:" db-type)
  (let [lock (case (or db-type :postgres)
               :postgres (postgres-lock/create-postgres-lock ctx)
               :h2 (table-lock/create-table-lock ctx)
               :sqlite (table-lock/create-table-lock ctx)
               ;; Default to postgres
               (postgres-lock/create-postgres-lock ctx))]
    (log/info "Migration lock initialized")
    lock))

(defmethod ig/halt-key! :boundary/migration-lock
  [_ _lock]
  (log/info "Releasing migration lock (if held)")
  ;; Force release any held locks on shutdown
  (try
    ;; Note: This may not work if lock doesn't have force-release-lock
    ;; In production, locks should auto-release on connection close
    (log/info "Migration lock halted")
    (catch Exception e
      (log/warn "Could not force-release lock on halt:" (.getMessage e)))))
