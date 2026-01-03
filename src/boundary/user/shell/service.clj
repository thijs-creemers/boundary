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
            [boundary.user.core.authentication :as auth-core]
            [boundary.user.core.audit :as audit-core]
[boundary.user.shell.auth :as auth-shell]
            [boundary.user.ports :as ports]
            [boundary.platform.shell.service-interceptors :as service-interceptors]
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

(defrecord UserService [user-repository session-repository audit-repository validation-config auth-service]

  ports/IUserService

  ;; User Management - Shell layer orchestrates I/O and calls pure core functions
  (register-user [this user-data]
    (println "DEBUG register-user called with:" (select-keys user-data [:email :name :role :password]))
    (let [result (service-interceptors/execute-service-operation
                  :register-user
                  {:user-data user-data
                   :email (:email user-data)}
                  (fn [{:keys [params]}]
                    (let [user-data (:user-data params)]
                      ;; 1. Validate request using pure core function with validation config
                      (println "DEBUG register-user step: before validate-user-creation-request")
                      (let [validation-result (user-core/validate-user-creation-request user-data validation-config)]
                        (println "DEBUG register-user step: after validate-user-creation-request" {:valid? (:valid? validation-result)})
                        (when-not (:valid? validation-result)
                          (throw (ex-info "Invalid user data"
                                          {:type :validation-error
                                           :errors (:errors validation-result)
                                           :original-data user-data
                                           :interface-type :cli}))))

                      ;; 2. Validate password policy using pure authentication core
                      (println "DEBUG register-user step: before meets-password-policy?" {:has-password (boolean (:password user-data))})
                      (when (:password user-data)
                        (let [password-validation (auth-core/meets-password-policy? (:password user-data)
                                                                                    (:password-policy validation-config)
                                                                                    {:email (:email user-data)})]
                          (println "DEBUG register-user step: after meets-password-policy?" {:valid? (:valid? password-validation)
                                                                                             :violations (:violations password-validation)})
                          (when-not (:valid? password-validation)
                            ;; Surface detailed violations from password policy so callers (HTTP/CLI)
                            ;; can show user-friendly hints about what is wrong with the password.
                            (throw (ex-info "Password does not meet requirements"
                                            {:type :password-policy-violation
                                             :violations (:violations password-validation)})))))

                      ;; 3. Check business rules using pure core function
                      (println "DEBUG register-user step: before check-duplicate-user-decision")
                      (let [existing-user (.find-user-by-email user-repository (:email user-data))
                            uniqueness-result (user-core/check-duplicate-user-decision user-data existing-user)]
                        (println "DEBUG register-user step: after check-duplicate-user-decision" {:decision (:decision uniqueness-result)})
                        (when (= :reject (:decision uniqueness-result))
                          (throw (ex-info "User already exists"
                                          {:type :user-exists
                                           :message (:message uniqueness-result)}))))

                      ;; 4. Hash password using auth service (shell layer I/O)
                      (println "DEBUG register-user step: before hash-password")
                      (let [password-hash (when (:password user-data)
                                            (auth-shell/hash-password (:password user-data)))
                            user-data-with-hash (if password-hash
                                                  (-> user-data
                                                      (assoc :password_hash password-hash)
                                                      (dissoc :password))
                                                  user-data)]

                        ;; 5. Persist using impure shell persistence layer
                        (let [prepared-user (user-core/prepare-user-for-creation user-data-with-hash (current-timestamp) (generate-user-id))
                              created-user (.create-user user-repository prepared-user)]
                          (println "DEBUG created-user in service:" (select-keys created-user [:id :email :name :role :created-at]))

                          ;; 6. Create audit log entry for user creation
                          (try
                            (let [audit-entry (audit-core/create-user-audit-entry
                                               nil  ; actor-id (nil for self-registration, or actor from context)
                                               "system"  ; actor-email (system or actual actor)
                                               created-user
                                               nil  ; ip-address (extract from request context if available)
                                               nil)] ; user-agent (extract from request context if available)
                              (.create-audit-log audit-repository audit-entry))
                            (catch Exception e
                              ;; Log audit failure but don't fail the operation
                              (println "WARN: Failed to create audit log:" (.getMessage e))))

                          ;; Remove sensitive data before returning
                          (dissoc created-user :password_hash)))))
                  {:system {:user-repository user-repository
                            :session-repository session-repository
                            :auth-service auth-service}})]
      (println "DEBUG register-user result from execute-service-operation:" result)
      result))

  (authenticate-user [this user-credentials]
    (service-interceptors/execute-service-operation
     :authenticate-user
     {:user-credentials user-credentials
      :email (:email user-credentials)}
     (fn [{:keys [params]}]
       (let [user-credentials (:user-credentials params)
             {:keys [email password ip-address user-agent remember]} user-credentials]

          ;; 1. Find user by email
         (if-let [user (.find-user-by-email user-repository email)]
           (let [credential-validation (auth-core/validate-login-credentials user-credentials)]
             (if-not (:valid? credential-validation)
                 ;; Validation failed - log failed attempt
               (do
                 (let [audit-entry (audit-core/login-audit-entry
                                    (:id user)
                                    (:email user)
                                    ip-address
                                    user-agent
                                    false
                                    "Invalid credentials format")]
                   (.create-audit-log audit-repository audit-entry))
                 {:authenticated false
                  :reason :invalid-credentials
                  :errors (:errors credential-validation)})

                 ;; 3. Verify password using auth service (shell layer I/O)
                (if (and (:password_hash user)
                         (auth-shell/verify-password password (:password_hash user)))
                   ;; 4. Check account security using pure authentication core
                    (let [login-decision (auth-core/should-allow-login-attempt? user {} (current-timestamp))]
                      (if (:allowed? login-decision)
                       ;; 5. Generate session data using pure functions
                        (let [session-token (generate-secure-token)
                              session-id (generate-user-id)
                              current-time (current-timestamp)
                              session-data (session-core/prepare-session-for-creation
                                            {:user-id (:id user)
                                             :device-info {:ip-address ip-address
                                                           :user-agent user-agent}
                                             :remember-me (boolean remember)}
                                            current-time
                                            session-id
                                            session-token)]

                         ;; 6. Persist session using impure shell persistence layer
                          (let [created-session (.create-session session-repository session-data)]

                           ;; 7. Log successful login
                            (let [audit-entry (audit-core/login-audit-entry
                                               (:id user)
                                               (:email user)
                                               ip-address
                                               user-agent
                                               true
                                               nil)]
                              (.create-audit-log audit-repository audit-entry))

                           ;; Return authentication result with session and JWT
                            {:authenticated true
                             :user (dissoc user :password_hash)
                             :session created-session
                             :jwt-token (auth-shell/create-jwt-token user 24)}))

                        ;; Login not allowed - log failed attempt
                        (do
                          (let [audit-entry (audit-core/login-audit-entry
                                             (:id user)
                                             (:email user)
                                             ip-address
                                             user-agent
                                             false
                                             (str "Login not allowed: " (:reason login-decision)))]
                            (.create-audit-log audit-repository audit-entry))
                          {:authenticated false
                           :reason (:reason login-decision)
                           :retry-after (:retry-after login-decision)})))

                 ;; Password verification failed - log failed attempt
                 (do
                   (let [audit-entry (audit-core/login-audit-entry
                                      (:id user)
                                      (:email user)
                                      ip-address
                                      user-agent
                                      false
                                      "Invalid password")]
                     (.create-audit-log audit-repository audit-entry))
                   {:authenticated false
                    :reason :invalid-password}))))

             ;; User not found - we don't log this for security (avoid enumeration)
           {:authenticated false
            :reason :user-not-found})))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

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
               :session-repository session-repository
               :auth-service auth-service}}))

  (logout-user [this session-token]
    (service-interceptors/execute-service-operation
     :logout-user
     {:session-token session-token}
     (fn [{:keys [params]}]
       (let [session-token (:session-token params)]
         ;; 1. Find and invalidate session using impure shell persistence layer
         (if-let [session (.find-session-by-token session-repository session-token)]
           (let [invalidated-session (.invalidate-session session-repository session-token)
                 ;; Get user info for audit log
                 user (.find-user-by-id user-repository (:user_id session))]

             ;; 2. Create audit log entry
             (when user
               (let [audit-entry (audit-core/logout-audit-entry
                                  (:id user)
                                  (:email user)
                                  (:ip_address session)  ; Use IP from session
                                  (:user_agent session))] ; Use user-agent from session
                 (.create-audit-log audit-repository audit-entry)))

             ;; Return result
             {:invalidated true :session-id (:id session)})

           ;; Session not found
           {:invalidated false})))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  ;; Additional IUserService methods
  (get-user-by-id [this user-id]
    (service-interceptors/execute-service-operation
     :get-user-by-id
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             user (.find-user-by-id user-repository user-id)]
         ;; Remove sensitive data before returning
         (when user
           (dissoc user :password_hash))))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  (get-user-by-email [this email]
    (service-interceptors/execute-service-operation
     :get-user-by-email
     {:email email}
     (fn [{:keys [params]}]
       (let [{:keys [email]} params
             user (.find-user-by-email user-repository email)]
         ;; Remove sensitive data before returning
         (when user
           (dissoc user :password_hash))))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  (list-users [this options]
    (service-interceptors/execute-service-operation
     :list-users
     {:options options}
     (fn [{:keys [params]}]
       (let [{:keys [options]} params
             result (.find-users user-repository options)
             users (:users result)
             total-count (:total-count result)
             cleaned-users (map #(dissoc % :password_hash) users)]
         ;; Remove sensitive data from all users and return with pagination info
         {:users cleaned-users
          :total-count total-count}))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  (update-user-profile [this user-entity]
    (service-interceptors/execute-service-operation
     :update-user-profile
     {:user-entity user-entity
      :user-id (:id user-entity)}
     (fn [{:keys [params]}]
       (let [user-entity (:user-entity params)
             ;; Get old user data for audit trail (before update)
             old-user (.find-user-by-id user-repository (:id user-entity))]
         ;; 1. Validate update using pure core function
         (let [validation-result (user-core/validate-user-update-request user-entity)]
           (when-not (:valid? validation-result)
             (throw (ex-info "Invalid user data"
                             {:type :validation-error
                              :errors (:errors validation-result)
                              :original-data user-entity
                              :interface-type :cli}))))

         ;; 2. Prepare user with updated timestamp and handle active/deleted_at
         (let [current-time (current-timestamp)
               prepared-user (user-core/prepare-user-for-update user-entity current-time)]

           ;; 3. Persist using impure shell persistence layer
           (let [updated-user (.update-user user-repository prepared-user)]

             ;; 4. Create audit log entry
             ;; TODO: Extract actor info from context (currently using target user as actor)
             (when (and updated-user old-user)
               (let [audit-entry (audit-core/update-user-audit-entry
                                  (:id updated-user)      ; actor-id (TODO: should be from context)
                                  (:email updated-user)   ; actor-email (TODO: should be from context)
                                  (:id updated-user)      ; target-user-id
                                  (:email updated-user)   ; target-user-email
                                  old-user                ; old user data
                                  updated-user            ; new user data
                                  nil                     ; ip-address (TODO: extract from context)
                                  nil)]                   ; user-agent (TODO: extract from context)
                 (.create-audit-log audit-repository audit-entry)))

             ;; Remove sensitive data before returning
             (when updated-user
               (dissoc updated-user :password_hash))))))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  (deactivate-user [this user-id]
    (service-interceptors/execute-service-operation
     :deactivate-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             ;; Get user details before deactivation for audit trail
             user (.find-user-by-id user-repository user-id)
             result (.soft-delete-user user-repository user-id)]

         ;; Create audit log entry
         (when (and result user)
           (try
             (let [audit-entry (audit-core/deactivate-user-audit-entry
                                nil  ; actor-id (extract from request context)
                                "system"  ; actor-email
                                user-id
                                (:email user)
                                nil  ; ip-address
                                nil)] ; user-agent
               (.create-audit-log audit-repository audit-entry))
             (catch Exception e
               (println "WARN: Failed to create audit log:" (.getMessage e)))))

         (boolean result)))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :audit-repository audit-repository
               :auth-service auth-service}}))

  (permanently-delete-user [this user-id]
    (service-interceptors/execute-service-operation
     :permanently-delete-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             ;; Get user details before deletion for audit trail
             user (.find-user-by-id user-repository user-id)
             result (.hard-delete-user user-repository user-id)]

         ;; Create audit log entry
         (when (and result user)
           (try
             (let [audit-entry (audit-core/delete-user-audit-entry
                                nil  ; actor-id (extract from request context)
                                "system"  ; actor-email
                                user-id
                                (:email user)
                                nil  ; ip-address
                                nil)] ; user-agent
               (.create-audit-log audit-repository audit-entry))
             (catch Exception e
               (println "WARN: Failed to create audit log:" (.getMessage e)))))

         (boolean result)))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :audit-repository audit-repository
               :auth-service auth-service}}))

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
               :session-repository session-repository
               :auth-service auth-service}}))

  (get-user-sessions [this user-id]
    (service-interceptors/execute-service-operation
     :get-user-sessions
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             sessions (.find-sessions-by-user session-repository user-id)]
         ;; Return sessions (no sensitive data to filter)
         sessions))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  ;; ---------------------------------------------------------------------------
  ;; Audit Log Query Operations
  ;; ---------------------------------------------------------------------------

  (list-audit-logs [this options]
    (service-interceptors/execute-service-operation
     :list-audit-logs
     {:options options}
     (fn [{:keys [params]}]
       (let [options (:options params)
             ;; Query audit repository with provided options
             result (.find-audit-logs audit-repository options)]
         ;; Return result map with :audit-logs and :total-count
         result))
     {:system {:audit-repository audit-repository}}))

  (get-audit-logs-for-user [this user-id options]
    (service-interceptors/execute-service-operation
     :get-audit-logs-for-user
     {:user-id user-id :options options}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             options (:options params)
             ;; Query audit logs for specific user
             logs (.find-audit-logs-by-user audit-repository user-id options)]
         ;; Return vector of audit log entities
         logs))
     {:system {:audit-repository audit-repository}})))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-user-service
  "Create a user service instance with injected repositories, validation config, and auth service.

   Args:
     user-repository: Implementation of IUserRepository
     session-repository: Implementation of IUserSessionRepository
     audit-repository: Implementation of IUserAuditRepository
     validation-config: Map containing validation policies and settings
     auth-service: Implementation of IAuthenticationService

   Returns:
     UserService instance

   Example:
     (def service (create-user-service user-repo session-repo audit-repo validation-cfg auth-svc))"
  [user-repository session-repository audit-repository validation-config auth-service]
  (->UserService user-repository session-repository audit-repository validation-config auth-service))