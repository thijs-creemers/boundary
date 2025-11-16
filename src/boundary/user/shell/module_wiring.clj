(ns boundary.user.shell.module-wiring
  "Integrant wiring for the user module.

  This namespace owns all Integrant init/halt methods for user-specific
  components so that shared system wiring does not depend directly on
  user shell namespaces."
  (:require [boundary.user.shell.persistence :as user-persistence]
            [boundary.user.shell.service :as user-service]
            [boundary.user.shell.auth :as user-auth]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; User Repository
;; =============================================================================

(defmethod ig/init-key :boundary/user-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing user repository")
  (let [repo (user-persistence/create-user-repository ctx)]
    (log/info "User repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/user-repository
  [_ _repo]
  (log/info "User repository halted (no cleanup needed)"))

;; =============================================================================
;; Session Repository
;; =============================================================================

(defmethod ig/init-key :boundary/session-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing session repository")
  (let [repo (user-persistence/create-session-repository ctx)]
    (log/info "Session repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/session-repository
  [_ _repo]
  (log/info "Session repository halted (no cleanup needed)"))

;; =============================================================================
;; Authentication Service
;; =============================================================================

(defmethod ig/init-key :boundary/auth-service
  [_ {:keys [user-repository session-repository auth-config]}]
  (log/info "Initializing authentication service")
  (let [service (user-auth/create-authentication-service
                 user-repository session-repository auth-config)]
    (log/info "Authentication service initialized")
    service))

(defmethod ig/halt-key! :boundary/auth-service
  [_ _service]
  (log/info "Authentication service halted (no cleanup needed)"))

;; =============================================================================
;; User Service
;; =============================================================================

(defmethod ig/init-key :boundary/user-service
  [_ {:keys [user-repository session-repository validation-config auth-service]}]
  (log/info "Initializing user service")
  (let [service (user-service/create-user-service
                 user-repository session-repository validation-config auth-service)]
    (log/info "User service initialized")
    service))

(defmethod ig/halt-key! :boundary/user-service
  [_ _service]
  (log/info "User service halted (no cleanup needed)"))

;; =============================================================================
;; User HTTP Handler
;; =============================================================================

(defmethod ig/init-key :boundary/user-http-handler
  [_ {:keys [user-service config]}]
  (log/info "Initializing user HTTP handler")
  (require 'boundary.user.shell.http)
  (let [create-handler (ns-resolve 'boundary.user.shell.http 'create-handler)
        handler (create-handler user-service (or config {}))]
    (log/info "User HTTP handler initialized successfully")
    handler))

(defmethod ig/halt-key! :boundary/user-http-handler
  [_ _handler]
  (log/info "User HTTP handler halted"))

;; =============================================================================
;; User Database Schema
;; =============================================================================

(defmethod ig/init-key :boundary/user-db-schema
  [_ {:keys [ctx]}]
  (log/info "Initializing user module database schema")
  (user-persistence/initialize-user-schema! ctx)
  (log/info "User module database schema initialized")
  {:status :initialized})

(defmethod ig/halt-key! :boundary/user-db-schema
  [_ _state]
  (log/info "User module database schema component halted"))
