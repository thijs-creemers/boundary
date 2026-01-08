(ns boundary.user.shell.auth
  "Shell Layer - Authentication service with side effects.
   
   This is the SHELL layer for authentication operations:
   - Password hashing and verification (bcrypt)
   - JWT token creation and validation
   - Session token generation
   - Authentication coordination
   - MFA verification
   
   All I/O and side effects happen here. Pure business logic
   is delegated to boundary.user.core.authentication."
  (:require [boundary.user.core.authentication :as auth-core]
            [boundary.user.core.mfa :as mfa-core]
            [boundary.user.shell.mfa :as mfa-shell]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]
            [clojure.tools.logging :as log])
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
  "JWT signing secret loaded from environment variable.

   SECURITY: Must be set via JWT_SECRET environment variable.
   Throws exception if not configured to prevent accidental use of default secret."
  (or (System/getenv "JWT_SECRET")
      (throw (ex-info "JWT_SECRET environment variable not configured. Set JWT_SECRET before starting the application."
                      {:type :configuration-error
                       :required-env-var "JWT_SECRET"}))))

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

(defrecord AuthenticationService [user-repository session-repository mfa-service auth-config])

(defn create-authentication-service
  "Factory function: Create authentication service instance.
   
   Args:
     user-repository: IUserRepository implementation
     session-repository: IUserSessionRepository implementation
     mfa-service: MFAService implementation  
     auth-config: Authentication configuration map
     
   Returns:
     AuthenticationService instance"
  [user-repository session-repository mfa-service auth-config]
  (->AuthenticationService user-repository session-repository mfa-service auth-config))

(defn authenticate-user
  "Coordinate user authentication with all security checks including MFA.
   
   Args:
     auth-service: AuthenticationService instance
     email: User email
     password: Plain text password
     login-context: Map with :ip-address, :user-agent, :mfa-code (optional)
     
   Returns:
     Authentication result map with :success?, :user, :session, :requires-mfa?, etc."
  [auth-service email password login-context]
  (let [{:keys [user-repository session-repository mfa-service auth-config]} auth-service]
    (log/info "Authentication attempt" {:email email :ip (:ip-address login-context)})

    (let [current-time (Instant/now)

          ;; Step 1: Validate login request format
          credential-validation (auth-core/validate-login-credentials
                                 {:email email :password password})

          _ (when-not (:valid? credential-validation)
              (log/warn "Invalid login credentials format" {:email email}))

          ;; Step 2: Look up user (I/O operation)
          user (when (:valid? credential-validation)
                 (.find-user-by-email user-repository email))

          ;; Step 3: Check if login attempt should be allowed
          login-allowed? (when user
                           (auth-core/should-allow-login-attempt?
                            user auth-config current-time))

          _ (when (and user (not (:allowed? login-allowed?)))
              (log/warn "Login attempt blocked" {:email email :reason (:reason login-allowed?)}))

          ;; Step 4: Verify password (I/O operation)
          password-valid? (when (and user (:allowed? login-allowed?))
                            (verify-password password (:password-hash user)))

          ;; Step 5: Analyze login risk
          login-risk (when (and user password-valid?)
                       (auth-core/analyze-login-risk
                        user login-context
                        (.find-sessions-by-user session-repository (:id user))))

          ;; Step 6: Check MFA requirement and verification
          mfa-code (:mfa-code login-context)
          mfa-requirement (when (and user password-valid?)
                            (mfa-core/determine-mfa-requirement
                             user password-valid? mfa-code login-risk))

          ;; Step 7: Verify MFA code if provided
          mfa-verification (when (and user
                                      password-valid?
                                      (:mfa-enabled user)
                                      mfa-code)
                             (mfa-shell/verify-mfa-code mfa-service user mfa-code))

          ;; Step 8: Handle authentication result
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
                                           user auth-config current-time)
                          updated-user (merge user failure-updates)]
                      ;; Update user with failed login consequences (I/O)
                      (.update-user user-repository updated-user)
                      (log/warn "Authentication failed - invalid password" {:email email})
                      {:success? false
                       :error :authentication-failed
                       :message "Invalid credentials"})

                   ;; MFA required but not provided
                   (and (:requires-mfa? mfa-requirement)
                        (nil? mfa-code))
                   {:success? false
                    :requires-mfa? true
                    :message "MFA code required"
                    :user (dissoc user :password-hash :mfa-secret :mfa-backup-codes)}

                   ;; MFA code provided but invalid
                   (and (:requires-mfa? mfa-requirement)
                        mfa-code
                        (not (:valid? mfa-verification)))
                   (do
                     (log/warn "MFA verification failed" {:email email})
                     {:success? false
                      :error :mfa-verification-failed
                      :message "Invalid MFA code"})

                   ;; Successful authentication (with or without MFA)
                   :else
                   (let [;; If backup code was used, update user
                          user-after-mfa (if (:used-backup-code? mfa-verification)
                                           (do
                                             (log/info "Backup code used for MFA" {:email email})
                                             (let [updated (merge user (:updates mfa-verification))]
                                               (.update-user user-repository updated)
                                               updated))
                                           user)

                          ;; Update user for successful login (I/O)  
                          login-updates (auth-core/prepare-successful-login-updates user-after-mfa current-time)
                          updated-user (merge user-after-mfa login-updates)
                          _ (.update-user user-repository updated-user)

                         ;; Determine session creation policy
                         session-policy (auth-core/should-create-session? user login-risk auth-config)

                         ;; Create session if policy allows
                         session (when (:create-session? session-policy)
                                   (let [session-token (generate-session-token)
                                         jwt-token (create-jwt-token user (:session-duration-hours session-policy))
                                         session-data {:user-id (:id user)
                                                       :session-token session-token
                                                       :jwt-token jwt-token
                                                       :expires-at (.plus current-time
                                                                          (Duration/ofHours (:session-duration-hours session-policy)))
                                                       :user-agent (:user-agent login-context)
                                                       :ip-address (:ip-address login-context)}]
                                     (.create-session session-repository session-data)))]

                     (log/info "Authentication successful"
                               {:email email
                                :risk-score (:risk-score login-risk)
                                :mfa-used (:mfa-enabled user)
                                :session-duration (:session-duration-hours session-policy)})

                     {:success? true
                      :user (dissoc user :password-hash :mfa-secret :mfa-backup-codes) ; Don't return sensitive data
                      :session session
                      :risk-analysis login-risk
                      :mfa-verified? (or (not (:mfa-enabled user)) (:valid? mfa-verification))
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