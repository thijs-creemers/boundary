(ns boundary.user.shell.auth
  "Shell Layer - Authentication service with side effects.
   
   This is the SHELL layer for authentication operations:
   - Password hashing and verification (bcrypt)
   - JWT token creation and validation
   - Session token generation
   - Authentication coordination
   
   All I/O and side effects happen here. Pure business logic
   is delegated to boundary.user.core.authentication."
  (:require [boundary.user.core.authentication :as auth-core]
            [boundary.user.core.session :as session-core]
            [boundary.user.schema :as schema]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]
            [clojure.tools.logging :as log]
            [malli.core :as m])
  (:import (java.time Instant Duration)
           (java.security SecureRandom)
           (java.util Base64)))

;; =============================================================================
;; Password Hashing Operations (Side Effects)
;; =============================================================================

(defn hash-password
  "Shell function: Hash password using bcrypt.
   
   Args:
     password: Plain text password
     
   Returns:
     Bcrypt hash string (60 characters)
     
   Side effects: Crypto operations with secure random"
  [password]
  (hashers/derive password {:alg :bcrypt+sha512}))

(defn verify-password
  "Shell function: Verify password against bcrypt hash.
   
   Args:
     password: Plain text password  
     hash: Bcrypt hash string
     
   Returns:
     Boolean - true if password matches
     
   Side effects: Crypto operations"
  [password hash]
  (hashers/check password hash))

;; =============================================================================
;; JWT Token Operations (Side Effects)
;; =============================================================================

(def ^:private jwt-secret
  "JWT signing secret. In production, load from secure configuration."
  ;; TODO: Load from secure configuration in production
  "your-secret-key-change-in-production")

(defn create-jwt-token
  "Shell function: Create JWT token for authenticated user.
   
   Args:
     user: User entity
     session-duration-hours: How long token should be valid
     
   Returns:
     JWT token string
     
   Side effects: Crypto operations, time-dependent"
  [user session-duration-hours]
  (let [now (Instant/now)
        exp (.plus now (Duration/ofHours session-duration-hours))
        claims {:sub (str (:id user))
                :email (:email user)
                :role (name (:role user))
                :tenant-id (str (:tenant-id user))
                :iat (.getEpochSecond now)
                :exp (.getEpochSecond exp)}]
    (jwt/sign claims jwt-secret)))

(defn validate-jwt-token
  "Shell function: Validate and decode JWT token.
   
   Args:
     token: JWT token string
     
   Returns:
     {:valid? true :claims {...}} or {:valid? false :error string}
     
   Side effects: Crypto operations"
  [token]
  (try
    (let [claims (jwt/unsign token jwt-secret)]
      {:valid? true :claims claims})
    (catch Exception e
      {:valid? false :error (.getMessage e)})))

;; =============================================================================
;; Session Token Generation (Side Effects)
;; =============================================================================

(defn generate-session-token
  "Shell function: Generate cryptographically secure session token.
   
   Returns:
     Base64-encoded random token string
     
   Side effects: Secure random generation"
  []
  (let [random (SecureRandom.)
        bytes (byte-array 32)]
    (.nextBytes random bytes)
    (.encodeToString (Base64/getEncoder) bytes)))

;; =============================================================================
;; Authentication Service Coordination
;; =============================================================================

(defrecord AuthenticationService [user-repository session-repository auth-config])

(defn create-authentication-service
  "Factory function: Create authentication service instance.
   
   Args:
     user-repository: IUserRepository implementation
     session-repository: IUserSessionRepository implementation  
     auth-config: Authentication configuration map
     
   Returns:
     AuthenticationService instance"
  [user-repository session-repository auth-config]
  (->AuthenticationService user-repository session-repository auth-config))

(defn authenticate-user
  "Coordinate user authentication with all security checks.
   
   Args:
     auth-service: AuthenticationService instance
     email: User email
     password: Plain text password
     login-context: Map with :tenant-id, :ip-address, :user-agent
     
   Returns:
     Authentication result map with :success?, :user, :session, etc."
  [auth-service email password login-context]
  (let [{:keys [user-repository session-repository auth-config]} auth-service]
    (log/info "Authentication attempt" {:email email :ip (:ip-address login-context)})

    (let [current-time (Instant/now)

          ;; Step 1: Validate login request format
          credential-validation (auth-core/validate-login-credentials
                                 {:email email :password password})

          _ (when-not (:valid? credential-validation)
              (log/warn "Invalid login credentials format" {:email email}))

          ;; Step 2: Look up user (I/O operation)
          user (when (:valid? credential-validation)
                 (.find-user-by-email user-repository email (:tenant-id login-context)))

          ;; Step 3: Check if login attempt should be allowed
          login-allowed? (when user
                           (auth-core/should-allow-login-attempt?
                            user auth-config current-time))

          _ (when (and user (not (:allowed? login-allowed?)))
              (log/warn "Login attempt blocked" {:email email :reason (:reason login-allowed?)}))

          ;; Step 4: Verify password (I/O operation)
          password-valid? (when (and user (:allowed? login-allowed?))
                            (verify-password password (:password-hash user)))

          ;; Step 5: Handle authentication result
          result (cond
                   ;; Validation errors
                   (not (:valid? credential-validation))
                   {:success? false
                    :error :validation-error
                    :message "Invalid request format"}

                   ;; User not found or login blocked
                   (not (:allowed? login-allowed?))
                   {:success? false
                    :error :authentication-failed
                    :message (:reason login-allowed?)
                    :retry-after (:retry-after login-allowed?)}

                   ;; Password verification failed
                   (not password-valid?)
                   (let [failure-updates (auth-core/calculate-failed-login-consequences
                                          user auth-config current-time)]
                     ;; Update user with failed login consequences (I/O)
                     (.update-user user-repository (:id user) failure-updates)
                     (log/warn "Authentication failed - invalid password" {:email email})
                     {:success? false
                      :error :authentication-failed
                      :message "Invalid credentials"})

                   ;; Successful authentication
                   :else
                   (let [;; Analyze login risk
                         login-risk (auth-core/analyze-login-risk
                                     user login-context
                                     (.find-sessions-by-user-id session-repository (:id user) {:limit 30}))

                         ;; Update user for successful login (I/O)  
                         login-updates (auth-core/prepare-successful-login-updates user current-time)
                         _ (.update-user user-repository (:id user) login-updates)

                         ;; Determine session creation policy
                         session-policy (auth-core/should-create-session? user login-risk auth-config)

                         ;; Create session if policy allows
                         session (when (:create-session? session-policy)
                                   (let [session-token (generate-session-token)
                                         jwt-token (create-jwt-token user (:session-duration-hours session-policy))
                                         session-data {:user-id (:id user)
                                                       :tenant-id (:tenant-id user)
                                                       :session-token session-token
                                                       :jwt-token jwt-token
                                                       :expires-at (.plus current-time
                                                                          (Duration/ofHours (:session-duration-hours session-policy)))
                                                       :user-agent (:user-agent login-context)
                                                       :ip-address (:ip-address login-context)}]
                                     (.create-session session-repository session-data)))]

                     (log/info "Authentication successful"
                               {:email email :risk-score (:risk-score login-risk)
                                :session-duration (:session-duration-hours session-policy)})

                     {:success? true
                      :user (dissoc user :password-hash) ; Don't return password hash
                      :session session
                      :risk-analysis login-risk
                      :requires-additional-verification? (:requires-additional-verification? session-policy)}))]

      result)))

;; =============================================================================
;; Password Management Functions
;; =============================================================================

(defn create-user-with-password
  "Shell function: Create user with hashed password.
   
   Args:
     user-data: User creation data with plain text password
     user-repository: IUserRepository implementation
     
   Returns:
     User entity with password-hash field populated
     
   Side effects: Password hashing, I/O operations"
  [user-data user-repository]
  (let [password (:password user-data)
        password-hash (hash-password password)
        user-without-password (dissoc user-data :password)
        user-with-hash (assoc user-without-password
                              :password-hash password-hash
                              :password-created-at (Instant/now))]
    user-with-hash))

(defn change-user-password
  "Shell function: Change user password with validation.
   
   Args:
     user-id: User ID
     old-password: Current password (for verification)
     new-password: New password to set
     user-repository: IUserRepository implementation
     auth-config: Authentication configuration
     
   Returns:
     {:success? boolean :error optional-string}
     
   Side effects: Password hashing, I/O operations"
  [user-id old-password new-password user-repository auth-config]
  (let [user (.find-user-by-id user-repository user-id)
        current-time (Instant/now)]

    (cond
      ;; User not found
      (nil? user)
      {:success? false :error "User not found"}

      ;; Old password verification failed
      (not (verify-password old-password (:password-hash user)))
      {:success? false :error "Current password is incorrect"}

      ;; New password doesn't meet policy
      (let [policy-check (auth-core/meets-password-policy?
                          new-password
                          (:password-policy auth-config)
                          {:email (:email user)})]
        (not (:valid? policy-check)))
      {:success? false
       :error "New password doesn't meet security requirements"
       :violations (:violations (auth-core/meets-password-policy?
                                 new-password
                                 (:password-policy auth-config)
                                 {:email (:email user)}))}

      ;; Change password
      :else
      (let [new-hash (hash-password new-password)
            updates {:password-hash new-hash
                     :password-created-at current-time
                     :force-password-reset false}]
        (.update-user user-repository user-id updates)
        (log/info "Password changed" {:user-id user-id})
        {:success? true}))))