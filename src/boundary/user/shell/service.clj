(ns boundary.user.shell.service
  "User module I/O shell service layer - orchestrates between pure core and external systems.
   
   This shell layer acts as the I/O orchestration interface for user-related operations,
   bridging between the pure functional core and external systems. It handles:
   - I/O coordination and side effects (database, time, logging)
   - External dependency management (repositories, services)
   - Transaction boundaries and error handling
   - Cross-cutting concerns (logging, metrics, security)
   - API request/response transformation
   
   The shell is responsible for:
   1. Calling pure core functions with all needed parameters
   2. Managing external dependencies (time, UUIDs, tokens)
   3. Orchestrating repository calls based on core function results
   4. Handling all side effects (logging, events, notifications)
   
   Business logic lives in boundary.user.core.* namespaces."
  (:require [boundary.user.schema :as schema]
            [boundary.user.ports :as ports]
            [boundary.user.core.user :as user-core]
            [boundary.user.core.session :as session-core]
            [malli.core :as m]
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
      
      ;; 3. Prepare user for update with timestamp
      (let [current-time (current-timestamp)
            prepared-user (user-core/prepare-user-for-update user-entity current-time)]
        
        ;; 4. Persist and return
        (.update-user user-repository prepared-user))))
  
  (soft-delete-user [_ user-id]
    (log/info "Soft deleting user through service" {:user-id user-id})
    (.soft-delete-user user-repository user-id))
  
  (hard-delete-user [_ user-id]
    (log/warn "Hard deleting user through service - IRREVERSIBLE" {:user-id user-id})
    (.hard-delete-user user-repository user-id))
  
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
