(ns boundary.user.shell.service
  "User module service layer providing business logic and orchestration.
   
   This service layer acts as the primary interface for user-related operations,
   orchestrating between the domain layer and repository implementations. It handles:
   - Business rule validation and enforcement
   - Cross-cutting concerns (logging, metrics, security)
   - Transaction coordination
   - Domain event coordination
   - API request/response transformation
   
   The service is completely database-agnostic and depends on repository
   interfaces that are injected at construction time. Database-specific
   implementations are provided by boundary.user.infrastructure.database."
  (:require [boundary.user.schema :as schema]
            [boundary.user.ports :as ports]
            [malli.core :as m]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Database-Agnostic User Service
;; =============================================================================

(defrecord UserService [user-repository session-repository]
  ;; User Management
  (create-user [_ user-data]
    (log/info "Creating user through service" {:email (:email user-data)})
    ;; Validate using domain schema
    (when-not (m/validate schema/CreateUserRequest user-data)
      (throw (ex-info "Invalid user data"
                      {:type :validation-error
                       :errors (m/explain schema/CreateUserRequest user-data)})))
    
    ;; Business logic: check for existing user
    (when (.find-user-by-email user-repository (:email user-data) (:tenant-id user-data))
      (throw (ex-info "User already exists"
                      {:type :user-exists
                       :email (:email user-data)})))
    
    ;; Delegate to repository
    (.create-user user-repository user-data))
  
  (find-user-by-id [_ user-id]
    (log/debug "Finding user by ID through service" {:user-id user-id})
    (.find-user-by-id user-repository user-id))
  
  (find-user-by-email [_ email tenant-id]
    (log/debug "Finding user by email through service" {:email email :tenant-id tenant-id})
    (.find-user-by-email user-repository email tenant-id))
  
  (find-users-by-tenant [_ tenant-id options]
    (log/debug "Finding users by tenant through service" {:tenant-id tenant-id})
    (.find-users-by-tenant user-repository tenant-id options))
  
  (update-user [_ user-entity]
    (log/info "Updating user through service" {:user-id (:id user-entity)})
    ;; Validate using domain schema
    (when-not (m/validate schema/User user-entity)
      (throw (ex-info "Invalid user data for update"
                      {:type :validation-error
                       :errors (m/explain schema/User user-entity)})))
    
    ;; Business logic: ensure user exists
    (when-not (.find-user-by-id user-repository (:id user-entity))
      (throw (ex-info "User not found for update"
                      {:type :user-not-found
                       :user-id (:id user-entity)})))
    
    ;; Delegate to repository
    (.update-user user-repository user-entity))
  
  (soft-delete-user [_ user-id]
    (log/info "Soft deleting user through service" {:user-id user-id})
    (.soft-delete-user user-repository user-id))
  
  (hard-delete-user [_ user-id]
    (log/warn "Hard deleting user through service - IRREVERSIBLE" {:user-id user-id})
    (.hard-delete-user user-repository user-id))
  
  ;; Session Management
  (create-session [_ session-data]
    (log/info "Creating session through service" {:user-id (:user-id session-data)})
    (.create-session session-repository session-data))
  
  (find-session-by-token [_ session-token]
    (log/debug "Finding session by token through service")
    (.find-session-by-token session-repository session-token))
  
  (invalidate-session [_ session-token]
    (log/info "Invalidating session through service")
    (.invalidate-session session-repository session-token))
  
  (invalidate-all-user-sessions [_ user-id]
    (log/warn "Invalidating all user sessions through service" {:user-id user-id})
    (.invalidate-all-user-sessions session-repository user-id)))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-user-service
  "Create a user service instance with injected repositories.
   
   Args:
     user-repository: Implementation of IUserRepository
     session-repository: Implementation of IUserSessionRepository
     
   Returns:
     UserService instance
     
   Example:
     (def service (create-user-service user-repo session-repo))"
  [user-repository session-repository]
  (->UserService user-repository session-repository))
