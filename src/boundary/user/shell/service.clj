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
  (:require [boundary.user.core.session :as session-core]
            [boundary.user.core.user :as user-core]
            [boundary.user.ports :as ports]
            [boundary.shared.core.service-interceptors :as service-interceptors]
            [clojure.string :as str])
  (:import (java.security SecureRandom)
           (java.time Instant)
           (java.util UUID)))

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
    (-> (java.util.Base64/getEncoder)
        (.encodeToString token-bytes)
        (str/replace "+" "-")
        (str/replace "/" "_")
        (str/replace "=" ""))))

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

(defrecord UserService [user-repository session-repository validation-config]

  ports/IUserService

  ;; User Management - Shell layer orchestrates I/O and calls pure core functions
  (register-user [this user-data]
    (service-interceptors/execute-service-operation
     :register-user
     {:user-data user-data
      :tenant-id (:tenant-id user-data)
      :email (:email user-data)}
     (fn [{:keys [params]}]
       (let [user-data (:user-data params)]
         ;; 1. Validate request using pure core function with validation config
         (let [validation-result (user-core/validate-user-creation-request user-data validation-config)]
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
                              :message (:message uniqueness-result)}))))

         ;; 3. Persist using impure shell persistence layer
         (let [prepared-user (user-core/prepare-user-for-creation user-data (current-timestamp) (generate-user-id))
               created-user (.create-user user-repository prepared-user)]
           created-user)))
     {:system {:user-repository user-repository
               :session-repository session-repository}}))

  (authenticate-user [this user-credentials]
    (service-interceptors/execute-service-operation
     :authenticate-user
     {:user-credentials user-credentials
      :user-id (:user-id user-credentials)
      :tenant-id (:tenant-id user-credentials)}
     (fn [{:keys [params]}]
       (let [user-credentials (:user-credentials params)]
         ;; 1. Generate session data using pure functions
         (let [session-token (generate-secure-token)
               session-id (generate-user-id)
               current-time (current-timestamp)
               session-data (session-core/prepare-session-for-creation
                             {:user-id (:user-id user-credentials)
                              :tenant-id (:tenant-id user-credentials)
                              :ip-address (:ip-address user-credentials)
                              :user-agent (:user-agent user-credentials)}
                             current-time
                             session-id
                             session-token)]

           ;; 2. Persist session using impure shell persistence layer
           (let [created-session (.create-session session-repository session-data)]
             ;; Return session
             created-session))))
     {:system {:user-repository user-repository
               :session-repository session-repository}}))

  (validate-session [this session-token]
    (service-interceptors/execute-service-operation
     :validate-session
     {:session-token session-token}
     (fn [{:keys [params]}]
       (let [session-token (:session-token params)]
         ;; 1. Find session using impure shell persistence layer
         (if-let [session (.find-session-by-token session-repository session-token)]
           (let [current-time (current-timestamp)
                 validation-result (session-core/is-session-valid? session current-time)]

             (if (:valid? validation-result)
               ;; 2. Update session access time using impure shell persistence layer
               (let [updated-session-data (session-core/update-session-access session current-time)
                     updated-session (.update-session session-repository updated-session-data)]
                 ;; Return validated session
                 updated-session)

               ;; Session invalid (expired, etc.) - throw exception
               (throw (ex-info "Session invalid"
                               {:type :session-invalid
                                :reason (:reason validation-result)}))))

           ;; Session not found - return nil
           nil)))
     {:system {:user-repository user-repository
               :session-repository session-repository}}))

  (logout-user [this session-token]
    (service-interceptors/execute-service-operation
     :logout-user
     {:session-token session-token}
     (fn [{:keys [params]}]
       (let [session-token (:session-token params)]
         ;; 1. Find and invalidate session using impure shell persistence layer
         (if-let [session (.find-session-by-token session-repository session-token)]
           (let [invalidated-session (.invalidate-session session-repository session-token)]
             ;; Return result
             {:invalidated true :session-id (:id session)})

           ;; Session not found
           {:invalidated false})))
     {:system {:user-repository user-repository
               :session-repository session-repository}}))

  ;; Additional IUserService methods
  (get-user-by-id [this user-id]
    (service-interceptors/execute-service-operation
     :get-user-by-id
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)]
         (.find-user-by-id user-repository user-id)))
     {:system {:user-repository user-repository
               :session-repository session-repository}}))

  (get-user-by-email [this email tenant-id]
    (service-interceptors/execute-service-operation
     :get-user-by-email
     {:email email :tenant-id tenant-id}
     (fn [{:keys [params]}]
       (let [{:keys [email tenant-id]} params]
         (.find-user-by-email user-repository email tenant-id)))
     {:system {:user-repository user-repository
               :session-repository session-repository}}))

  (list-users-by-tenant [this tenant-id options]
    (service-interceptors/execute-service-operation
     :list-users-by-tenant
     {:tenant-id tenant-id :options options}
     (fn [{:keys [params]}]
       (let [{:keys [tenant-id options]} params]
         (.find-users-by-tenant user-repository tenant-id options)))
     {:system {:user-repository user-repository
               :session-repository session-repository}}))

  (update-user-profile [this user-entity]
    (service-interceptors/execute-service-operation
     :update-user-profile
     {:user-entity user-entity
      :user-id (:id user-entity)
      :tenant-id (:tenant-id user-entity)}
     (fn [{:keys [params]}]
       (let [user-entity (:user-entity params)]
         ;; 1. Validate update using pure core function
         (let [validation-result (user-core/validate-user-update-request user-entity)]
           (when-not (:valid? validation-result)
             (throw (ex-info "Invalid user data"
                             {:type :validation-error
                              :errors (:errors validation-result)}))))

         ;; 2. Persist using impure shell persistence layer
         (.update-user user-repository user-entity)))
     {:system {:user-repository user-repository
               :session-repository session-repository}}))

  (deactivate-user [this user-id]
    (service-interceptors/execute-service-operation
     :deactivate-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             result (.soft-delete-user user-repository user-id)]
         (boolean result)))
     {:system {:user-repository user-repository
               :session-repository session-repository}}))

  (permanently-delete-user [this user-id]
    (service-interceptors/execute-service-operation
     :permanently-delete-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             result (.hard-delete-user user-repository user-id)]
         (boolean result)))
     {:system {:user-repository user-repository
               :session-repository session-repository}}))

  (logout-user-everywhere [this user-id]
    (service-interceptors/execute-service-operation
     :logout-user-everywhere
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             invalidated-count (.invalidate-all-user-sessions session-repository user-id)]
         ;; Return count of invalidated sessions
         invalidated-count))
     {:system {:user-repository user-repository
               :session-repository session-repository}})))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-user-service
  "Create a user service instance with injected repositories and validation config.

   Args:
     user-repository: Implementation of IUserRepository
     session-repository: Implementation of IUserSessionRepository
     validation-config: Map containing validation policies and settings

   Returns:
     UserService instance

   Example:
     (def service (create-user-service user-repo session-repo validation-cfg))"
  [user-repository session-repository validation-config]
  (->UserService user-repository session-repository validation-config))