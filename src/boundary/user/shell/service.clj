(ns boundary.user.shell.service
  "FC/IS Shell Layer - User module service coordination.
   
   This is the SHELL layer in Functional Core / Imperative Shell architecture.
   The shell coordinates between:
   - Pure functional CORE (boundary.user.core.*)
   - I/O SHELL persistence (boundary.user.shell.persistence)
   
   Shell responsibilities:
   1. Coordinate calls between core and persistence layers
   2. Manage external dependencies (time, UUIDs, logging)
   3. Handle all side effects and I/O operations
   4. Orchestrate transaction boundaries
   5. Transform between external and internal representations
   
   The shell does NOT contain business logic - that lives in core.*
   The shell does NOT handle database operations - that lives in persistence.*"
  (:require [boundary.user.core.user :as user-core]
            [boundary.user.core.session :as session-core] 
            [boundary.user.shell.persistence :as persistence]
            [boundary.user.ports :as ports]
            [clojure.tools.logging :as log])
  (:import (java.util UUID)
           (java.time Instant)
           (java.security SecureRandom)))

;; =============================================================================
;; Helper Functions for External Dependencies
;; =============================================================================

(defn generate-secure-token
  "Generate cryptographically secure random token for sessions.
   This is a shell layer responsibility as it involves external randomness."
  []
  (let [secure-random (SecureRandom.)
        token-bytes (byte-array 32)]
    (.nextBytes secure-random token-bytes)
    (-> token-bytes
        (.encodeToString (java.util.Base64/getEncoder))
        (.replace "+" "-")
        (.replace "/" "_")
        (.replace "=" ""))))

(defn generate-user-id
  "Generate UUID for new users. Shell layer responsibility."
  []
  (UUID/randomUUID))

(defn current-timestamp
  "Get current timestamp. Shell layer responsibility for time dependency."
  []
  (Instant/now))

;; =============================================================================
;; Database-Agnostic User Service (I/O Shell Layer)
;; =============================================================================

(defrecord UserService [user-repository session-repository]
  ports/IUserService
  
  ;; User Management - Shell layer orchestrates I/O and calls pure core functions
  (create-user [this user-data]
    (log/info "Creating user through service" {:email (:email user-data)})
    
    ;; 1. Validate request using pure core function
    (let [validation-result (user-core/validate-user-creation-request user-data)]
      (when-not (:valid? validation-result)
        (throw (ex-info "Invalid user data"
                        {:type :validation-error
                         :errors (:errors validation-result)}))))
    
    ;; 2. Check business rules using pure core function
    (let [existing-user (.find-user-by-email user-repository (:email user-data) (:tenant-id user-data))
          uniqueness-result (user-core/check-duplicate-user-decision user-data existing-user)]
      (when (= :reject (:decision uniqueness-result))
        (throw (ex-info "User already exists"
                        {:type :user-exists
                         :email (:email user-data)}))))
    
    ;; 3. Generate external dependencies and prepare user
    (let [current-time (current-timestamp)
          user-id (generate-user-id)
          prepared-user (user-core/prepare-user-for-creation user-data current-time user-id)]
      
      ;; 4. Persist and return
      (.create-user user-repository prepared-user)))
  
  (find-user-by-id [_ user-id]
    (log/debug "Finding user by ID through service" {:user-id user-id})
    (.find-user-by-id user-repository user-id))
  
  (find-user-by-email [_ email tenant-id]
    (log/debug "Finding user by email through service" {:email email :tenant-id tenant-id})
    (.find-user-by-email user-repository email tenant-id))
  
  (find-users-by-tenant [_ tenant-id options]
    (log/debug "Finding users by tenant through service" {:tenant-id tenant-id})
    (.find-users-by-tenant user-repository tenant-id options))
  
  (update-user [this user-entity]
    (log/info "Updating user through service" {:user-id (:id user-entity)})
    
    ;; 1. Get current user
    (let [current-user (.find-user-by-id user-repository (:id user-entity))]
      (when-not current-user
        (throw (ex-info "User not found for update"
                        {:type :user-not-found
                         :user-id (:id user-entity)})))
      
      ;; 2. Validate update using pure core function
      (let [validation-result (user-core/validate-user-update-request user-entity)]
        (when-not (:valid? validation-result)
          (throw (ex-info "Invalid user data for update"
                          {:type :validation-error
                           :errors (:errors validation-result)}))))
      
      ;; 3. Check business rules for updates
      (let [changes (user-core/calculate-user-changes current-user user-entity)
            business-result (user-core/validate-user-business-rules user-entity changes)]
        (when-not (:valid? business-result)
          (throw (ex-info "Business rule violation"
                          {:type :business-rule-violation
                           :errors (:errors business-result)}))))
      
      ;; 4. Prepare user for update with timestamp
      (let [current-time (current-timestamp)
            prepared-user (user-core/prepare-user-for-update user-entity current-time)]
        
        ;; 5. Persist and return
        (.update-user user-repository prepared-user))))
  
  (soft-delete-user [this user-id]
    (log/info "Soft deleting user through service" {:user-id user-id})
    
    ;; 1. Get current user
    (let [current-user (.find-user-by-id user-repository user-id)]
      (when-not current-user
        (throw (ex-info "User not found for deletion"
                        {:type :user-not-found
                         :user-id user-id})))
      
      ;; 2. Check if deletion is allowed using pure core function
      (let [deletion-result (user-core/can-delete-user? current-user)]
        (when-not (:allowed? deletion-result)
          (throw (ex-info "User deletion not allowed"
                          {:type :deletion-not-allowed
                           :reason (:reason deletion-result)}))))
      
      ;; 3. Prepare user for soft deletion
      (let [current-time (current-timestamp)
            prepared-user (user-core/prepare-user-for-soft-deletion current-user current-time)]
        
        ;; 4. Persist and return
        (.update-user user-repository prepared-user)
        
        ;; 5. Invalidate all user sessions as side effect
        (.invalidate-all-user-sessions this user-id)
        
        true)))
  
  (hard-delete-user [this user-id]
    (log/warn "Hard deleting user through service - IRREVERSIBLE" {:user-id user-id})
    
    ;; 1. Get current user for validation
    (let [current-user (.find-user-by-id user-repository user-id)]
      (when-not current-user
        (throw (ex-info "User not found for deletion"
                        {:type :user-not-found
                         :user-id user-id})))
      
      ;; 2. Check if hard deletion is allowed using pure core function
      (let [deletion-result (user-core/can-hard-delete-user? current-user)]
        (when-not (:allowed? deletion-result)
          (throw (ex-info "User hard deletion not allowed"
                          {:type :hard-deletion-not-allowed
                           :reason (:reason deletion-result)}))))
      
      ;; 3. Invalidate all sessions first
      (.invalidate-all-user-sessions this user-id)
      
      ;; 4. Perform hard deletion
      (.hard-delete-user user-repository user-id)))
  
  ;; Session Management - Shell layer orchestrates I/O and calls pure core functions
  (create-session [this session-data]
    (log/info "Creating session through service" {:user-id (:user-id session-data)})
    
    ;; 1. Validate request using pure core function
    (let [validation-result (session-core/validate-session-creation-request session-data)]
      (when-not (:valid? validation-result)
        (throw (ex-info "Invalid session data"
                        {:type :validation-error
                         :errors (:errors validation-result)}))))
    
    ;; 2. Generate external dependencies
    (let [current-time (current-timestamp)
          session-id (generate-user-id)
          session-token (generate-secure-token)
          session-policy {:duration-hours 24}  ; TODO: Make configurable
          
          ;; 3. Use pure core function to prepare session
          prepared-session (session-core/prepare-session-for-creation 
                           session-data current-time session-id session-token session-policy)]
      
      ;; 4. Persist and return
      (.create-session session-repository prepared-session)))
  
  (find-session-by-token [this session-token]
    (log/debug "Finding session by token through service")
    
    ;; 1. Get session from repository
    (when-let [session (.find-session-by-token session-repository session-token)]
      (let [current-time (current-timestamp)
            update-policy {:access-update-threshold-minutes 5}  ; TODO: Make configurable
            
            ;; 2. Validate session using pure core function
            validation-result (session-core/is-session-valid? session current-time)]
        
        (if (:valid? validation-result)
          (do
            ;; 3. Update access time if policy allows
            (when (session-core/should-update-access-time? session current-time update-policy)
              (let [updated-session (session-core/prepare-session-for-access-update session current-time)]
                (.update-session session-repository updated-session)))
            session)
          (do
            (log/debug "Session validation failed" validation-result)
            nil)))))
  
  (invalidate-session [this session-token]
    (log/info "Invalidating session through service")
    
    ;; 1. Find session
    (if-let [session (.find-session-by-token session-repository session-token)]
      (let [current-time (current-timestamp)
            
            ;; 2. Use pure core function to prepare invalidation
            invalidated-session (session-core/prepare-session-for-invalidation session current-time)]
        
        ;; 3. Update in repository
        (.update-session session-repository invalidated-session)
        true)
      false))
  
  (invalidate-all-user-sessions [this user-id]
    (log/warn "Invalidating all user sessions through service" {:user-id user-id})
    
    ;; 1. Get all user sessions
    (let [sessions (.find-sessions-by-user session-repository user-id)
          current-time (current-timestamp)]
      
      ;; 2. Use pure core function to prepare each for invalidation
      (doseq [session sessions]
        (let [invalidated-session (session-core/prepare-session-for-invalidation session current-time)]
          (.update-session session-repository invalidated-session)))
      
      ;; 3. Return count of invalidated sessions
      (count sessions)))

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
