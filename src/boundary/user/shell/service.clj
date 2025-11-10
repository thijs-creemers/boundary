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
            [boundary.logging.core :as logging]
            [boundary.metrics.core :as metrics]
            [boundary.error-reporting.core :as error-reporting]
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

(defn extract-validation-error-codes
  "Extract specific error codes from validation errors for metrics.
   
   Args:
     validation-errors: Validation errors from core validation functions
   
   Returns:
     A set of error code strings for metrics tagging"
  [validation-errors]
  (cond
    ;; If it's a Malli explain result (from schema validation)
    (and (map? validation-errors)
         (contains? validation-errors :errors))
    (let [malli-errors (:errors validation-errors)]
      (->> malli-errors
           (map (fn [error]
                  (let [path (-> error :path first)
                        type (-> error :type)]
                    (case type
                      :malli.core/missing-key (str "missing-" (name path))
                      :malli.core/extra-key (str "extra-" (name path))
                      :malli.core/invalid-type (str "invalid-" (name path))
                      :malli.core/predicate-failed
                      (case path
                        :email "invalid-email-format"
                        :name "invalid-name-length"
                        :role "invalid-role"
                        (str "invalid-" (name path)))
                      "validation-error"))))
           (into #{})))

    ;; If it's a business rules error (from validate-user-business-rules)
    (and (map? validation-errors)
         (contains? validation-errors :errors)
         (map? (:errors validation-errors)))
    (->> (:errors validation-errors)
         keys
         (map (fn [field] (str "business-rule-" (name field))))
         (into #{}))

    ;; If it's a simple error map with field keys
    (map? validation-errors)
    (->> validation-errors
         keys
         (map (fn [field] (str "field-error-" (name field))))
         (into #{}))

    ;; Default fallback
    :else
    #{"unknown-validation-error"}))

(defn count-active-sessions
  "Count currently active sessions across all tenants.
   
   Args:
     session-repository: IUserSessionRepository instance
     current-time: Current timestamp for expiry checking
   
   Returns:
     Map with total count and count by tenant-id"
  [session-repository current-time]
  (let [all-sessions (.find-all-sessions session-repository)
        active-sessions (filter (fn [session]
                                  (and (not (:revoked-at session))
                                       (or (nil? (:expires-at session))
                                           (.isAfter (:expires-at session) current-time))))
                                all-sessions)]
    {:total (count active-sessions)
     :by-tenant (frequencies (map :tenant-id active-sessions))}))

(defn update-active-sessions-gauge
  "Update gauge metrics for active session counts.
   
   Args:
     metrics-emitter: Metrics collection service instance
     session-repository: IUserSessionRepository instance
   
   Side Effects:
     Updates gauge metrics with current active session counts"
  [metrics-emitter session-repository]
  (try
    (let [current-time (current-timestamp)
          session-counts (count-active-sessions session-repository current-time)]
      ;; Update total active sessions gauge
      (metrics/set-gauge-value metrics-emitter "active-sessions-total" (:total session-counts) {})

      ;; Update per-tenant active sessions gauge
      (doseq [[tenant-id count] (:by-tenant session-counts)]
        (metrics/set-gauge-value metrics-emitter "active-sessions-by-tenant" count {:tenant-id tenant-id})))
    (catch Exception e
      ;; Log error but don't fail the operation
      (.warn nil "Failed to update active sessions gauge" {:error (.getMessage e)}))))

;; =============================================================================
;; Database-Agnostic User Service (I/O Shell Layer)
;; =============================================================================

(defrecord UserService [user-repository session-repository logger metrics-emitter error-reporter]

  ports/IUserService

  ;; User Management - Shell layer orchestrates I/O and calls pure core functions
  (register-user [this user-data]
    (let [context {:operation "register-user"
                   :tenant-id (:tenant-id user-data)
                   :email (:email user-data)}]
;; Add error reporting breadcrumb
      (error-reporting/add-breadcrumb error-reporter
                                      "Starting user registration"
                                      "service.user"
                                      :info
                                      {:operation "register-user"
                                       :tenant-id (:tenant-id user-data)
                                       :email (:email user-data)})

      ;; Add histogram timing for user operation duration
      (metrics/time-with-histogram
       metrics-emitter
       "user-operation-duration"
       {:operation-type "register"
        :tenant-id (:tenant-id user-data)}
       (fn []
         (logging/with-function-logging
           logger
           "register-user"
           context
           (fn []
             (.info logger "Registering user through service" {:email (:email user-data)})

              ;; Metrics: Track user registration attempt
             (metrics/increment-counter metrics-emitter "user-registrations-attempted"
                                        {:tenant-id (:tenant-id user-data)})

             (try
                ;; 1. Validate request using pure core function
               (let [validation-result (user-core/validate-user-creation-request user-data)]
                 (when-not (:valid? validation-result)
                   ;; Add error reporting breadcrumb for validation failure
                   (error-reporting/add-breadcrumb error-reporter
                                                   "User validation failed"
                                                   "service.user.validation"
                                                   :warning
                                                   {:validation-errors (:errors validation-result)
                                                    :tenant-id (:tenant-id user-data)})

                    ;; Metrics: Track validation failure with detailed error codes
                   (let [error-codes (extract-validation-error-codes (:errors validation-result))]
                     (doseq [error-code error-codes]
                       (metrics/increment-counter metrics-emitter "validation-errors"
                                                  {:operation "register-user"
                                                   :tenant-id (:tenant-id user-data)
                                                   :error-code error-code})))
                   (throw (ex-info "Invalid user data"
                                   {:type :validation-error
                                    :errors (:errors validation-result)}))))

                ;; 2. Check business rules using pure core function
               (let [existing-user (.find-user-by-email user-repository (:email user-data) (:tenant-id user-data))
                     uniqueness-result (user-core/check-duplicate-user-decision user-data existing-user)]
                 (when (= :reject (:decision uniqueness-result))
                   ;; Add error reporting breadcrumb for duplicate user
                   (error-reporting/add-breadcrumb error-reporter
                                                   "Duplicate user detected"
                                                   "service.user.business-rules"
                                                   :warning
                                                   {:email (:email user-data)
                                                    :tenant-id (:tenant-id user-data)
                                                    :message (:message uniqueness-result)})

                    ;; Metrics: Track duplicate user attempt
                   (metrics/increment-counter metrics-emitter "user-registrations-failed"
                                              {:tenant-id (:tenant-id user-data)
                                               :reason "user-exists"})
                   (throw (ex-info "User already exists"
                                   {:type :user-exists
                                    :message (:message uniqueness-result)}))))

                ;; 3. Persist using impure shell persistence layer
               (let [prepared-user (user-core/prepare-user-for-creation user-data (current-timestamp) (generate-user-id))
                     created-user (.create-user user-repository prepared-user)
                     success-context (assoc context :user-id (:id created-user))]

                 ;; Add success breadcrumb
                 (error-reporting/add-breadcrumb error-reporter
                                                 "User registration successful"
                                                 "service.user"
                                                 :info
                                                 {:user-id (:id created-user)
                                                  :tenant-id (:tenant-id created-user)})

                  ;; Log business event using logging core
                 (logging/log-business-event logger "user-registered" "user" success-context
                                             {:user-id (:id created-user)
                                              :email (:email created-user)
                                              :name (:name created-user)
                                              :tenant-id (:tenant-id created-user)})

                  ;; Audit log using logging core
                 (logging/audit-user-action logger (:id created-user) "user" "register" "success" success-context)

                  ;; Metrics: Track successful registration
                 (metrics/increment-counter metrics-emitter "user-registrations-successful"
                                            {:tenant-id (:tenant-id user-data)})

                  ;; Return the created user
                 created-user)

               (catch Exception e
                 ;; Capture exception with error reporting
                 (error-reporting/report-application-error error-reporter e
                                                           "User registration failed"
                                                           {:extra {:operation "register-user"
                                                                    :tenant-id (:tenant-id user-data)
                                                                    :email (:email user-data)
                                                                    :user-data user-data}
                                                            :tags {:component "service.user"
                                                                   :operation "register-user"}})

                 ;; Metrics: Track registration failure
                 (metrics/increment-counter metrics-emitter "user-registrations-failed"
                                            {:tenant-id (:tenant-id user-data)
                                             :reason "system-error"})
                 ;; Re-throw the exception
                 (throw e)))))))))

  (authenticate-user [this user-credentials]
    (let [context {:operation "authenticate-user"
                   :user-id (:user-id user-credentials)}]
      ;; Add error reporting breadcrumb
      (error-reporting/add-breadcrumb error-reporter
                                      "Starting user authentication"
                                      "service.user"
                                      :info
                                      {:operation "authenticate-user"
                                       :user-id (:user-id user-credentials)
                                       :tenant-id (:tenant-id user-credentials)})

      ;; Add histogram timing for user operation duration
      (metrics/time-with-histogram
       metrics-emitter
       "user-operation-duration"
       {:operation-type "authenticate"
        :tenant-id (:tenant-id user-credentials)}
       (fn []
         (logging/with-function-logging
           logger
           "authenticate-user"
           context
           (fn []
             (.info logger "Authenticating user and creating session" {:user-id (:user-id user-credentials)})

              ;; Metrics: Track authentication attempt
             (metrics/increment-counter
              metrics-emitter
              "user-authentications-attempted"
              {:tenant-id (:tenant-id user-credentials)})

             (try
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
                 (let [created-session (.create-session session-repository session-data)
                       success-context (assoc context :session-id (:id created-session))]

                   ;; Add success breadcrumb
                   (error-reporting/add-breadcrumb error-reporter
                                                   "User authentication successful"
                                                   "service.user"
                                                   :info
                                                   {:user-id (:user-id created-session)
                                                    :session-id (:id created-session)
                                                    :tenant-id (:tenant-id created-session)})

                    ;; Business event log using logging core
                   (logging/log-business-event
                    logger
                    "user-session-started"
                    "session" success-context
                    {:user-id (:user-id created-session)
                     :session-id (:id created-session)
                     :login-method "password"
                     :tenant-id (:tenant-id created-session)})

                    ;; Audit log using logging core
                   (logging/audit-user-action
                    logger (:user-id created-session)
                    "session"
                    "authenticate"
                    "success"
                    success-context)

                    ;; Metrics: Track successful authentication and session creation
                   (metrics/increment-counter
                    metrics-emitter
                    "user-authentications-successful"
                    {:tenant-id (:tenant-id user-credentials)})

                   (metrics/increment-counter
                    metrics-emitter
                    "session-creations"
                    {:tenant-id (:tenant-id user-credentials)})

                    ;; Update active sessions gauge  
                   (update-active-sessions-gauge metrics-emitter session-repository)

                    ;; Return session
                   created-session))

               (catch Exception e
                 ;; Capture exception with error reporting
                 (error-reporting/report-application-error error-reporter e
                                                           "User authentication failed"
                                                           {:extra {:operation "authenticate-user"
                                                                    :user-id (:user-id user-credentials)
                                                                    :tenant-id (:tenant-id user-credentials)
                                                                    :user-credentials user-credentials}
                                                            :tags {:component "service.user"
                                                                   :operation "authenticate-user"}})

                 ;; Metrics: Track authentication failure
                 (metrics/increment-counter metrics-emitter "user-authentications-failed"
                                            {:tenant-id (:tenant-id user-credentials)
                                             :reason "system-error"})
                 ;; Re-throw the exception
                 (throw e)))))))))

  (validate-session [this session-token]
    (let [context {:operation "validate-session"
                   :session-token session-token}]
      ;; Add error reporting breadcrumb
      (error-reporting/add-breadcrumb error-reporter
                                      "Starting session validation"
                                      "service.user"
                                      :info
                                      {:operation "validate-session"
                                       :session-token (str/replace session-token #".{20}$" "***")})

      ;; Add histogram timing for user operation duration  
      (metrics/time-with-histogram
       metrics-emitter
       "user-operation-duration"
       {:operation-type "validate-session"}
       (fn []
         (logging/with-function-logging
           logger
           "validate-session"
           context
           (fn []
             (.info logger "Validating session token" {:session-token (str/replace session-token #".{20}$" "***")})

              ;; Metrics: Track session validation attempt
             (metrics/increment-counter metrics-emitter "session-validations-attempted" {})

             (try
                ;; 1. Find session using impure shell persistence layer
               (if-let [session (.find-session-by-token session-repository session-token)]
                 (let [current-time (current-timestamp)
                       validation-result (session-core/is-session-valid? session current-time)
                       success-context (assoc context
                                              :session-id (:id session)
                                              :user-id (:user-id session))]

                   (if (:valid? validation-result)

                       ;; 2. Update session access time using impure shell persistence layer
                     (let [updated-session (let [updated-session-data (session-core/update-session-access session current-time)]
                                             (.update-session session-repository updated-session-data))]

                       ;; Add success breadcrumb
                       (error-reporting/add-breadcrumb error-reporter
                                                       "Session validation successful"
                                                       "service.user"
                                                       :info
                                                       {:session-id (:id session)
                                                        :user-id (:user-id session)
                                                        :tenant-id (:tenant-id session)})

                          ;; Audit log using logging core
                       (logging/audit-user-action
                        logger (:user-id session)
                        "session"
                        "validate"
                        "success"
                        success-context)

                          ;; Metrics: Track successful session validation
                       (metrics/increment-counter
                        metrics-emitter
                        "session-validations-successful"
                        {:tenant-id (:tenant-id session)})

                          ;; Return validated session
                       updated-session)

                     (do
                       ;; Add failure breadcrumb for expired session
                       (error-reporting/add-breadcrumb error-reporter
                                                       "Session validation failed - expired"
                                                       "service.user.validation"
                                                       :warning
                                                       {:session-id (:id session)
                                                        :user-id (:user-id session)
                                                        :tenant-id (:tenant-id session)
                                                        :reason (:reason validation-result)})

                          ;; Session invalid (expired, etc.)
                       (logging/audit-user-action
                        logger (:user-id session)
                        "session"
                        "validate"
                        "expired"
                        success-context)

                          ;; Metrics: Track session validation failure
                       (metrics/increment-counter
                        metrics-emitter
                        "session-validations-failed"
                        {:tenant-id (:tenant-id session)
                         :reason "expired"})

                       (throw (ex-info "Session invalid"
                                       {:type :session-invalid
                                        :reason (:reason validation-result)})))))

                 (do
                   ;; Add failure breadcrumb for not found session
                   (error-reporting/add-breadcrumb error-reporter
                                                   "Session validation failed - not found"
                                                   "service.user.validation"
                                                   :warning
                                                   {:session-token (str/replace session-token #".{20}$" "***")})

                    ;; Session not found
                   (.warn logger "Session token not found" context)

                    ;; Metrics: Track session validation failure
                   (metrics/increment-counter
                    metrics-emitter
                    "session-validations-failed"
                    {:reason "not-found"})

                   nil))

               (catch Exception e
                 ;; Capture exception with error reporting
                 (error-reporting/report-application-error error-reporter e
                                                           "Session validation failed"
                                                           {:extra {:operation "validate-session"
                                                                    :session-token (str/replace session-token #".{20}$" "***")}
                                                            :tags {:component "service.user"
                                                                   :operation "validate-session"}})

                    ;; Metrics: Track validation system error
                 (metrics/increment-counter metrics-emitter "session-validations-failed"
                                            {:reason "system-error"})
                    ;; Re-throw the exception
                 (throw e)))))))))

  (logout-user [this session-token]
    (let [context {:operation "logout-user" :session-token session-token}]
      ;; Add error reporting breadcrumb
      (error-reporting/add-breadcrumb error-reporter
                                      "Starting user logout"
                                      "service.user"
                                      :info
                                      {:operation "logout-user"
                                       :session-token (str/replace session-token #".{20}$" "***")})

     ;; Add histogram timing for user operation duration
      (metrics/time-with-histogram
       metrics-emitter
       "user-operation-duration"
       {:operation-type "logout-user"}
       (fn []
         (logging/with-function-logging
           logger
           "logout-user"
           context
           (fn []
             (.info logger
                    "Logging out user by invalidating session"
                    {:session-token (str/replace session-token #".{20}$" "***")})

              ;; Metrics: Track session invalidation attempt
             (metrics/increment-counter metrics-emitter "session-invalidations-attempted" {})

             (try
                ;; 1. Find and invalidate session using impure shell persistence layer
               (if-let [session (.find-session-by-token session-repository session-token)]
                 (let [invalidated-session (.invalidate-session session-repository session-token)
                       success-context (assoc context :session-id (:id session) :user-id (:user-id session))]

                   ;; Add success breadcrumb
                   (error-reporting/add-breadcrumb error-reporter
                                                   "User logout successful"
                                                   "service.user"
                                                   :info
                                                   {:session-id (:id session)
                                                    :user-id (:user-id session)
                                                    :tenant-id (:tenant-id session)})

                    ;; Business event log using logging core
                   (logging/log-business-event
                    logger "user-session-ended"
                    "session"
                    success-context
                    {:user-id (:user-id session)
                     :session-id (:id session)
                     :reason "logout"
                     :tenant-id (:tenant-id session)})

                    ;; Audit log using logging core
                   (logging/audit-user-action
                    logger (:user-id session)
                    "session"
                    "invalidate"
                    "success"
                    success-context)

                    ;; Metrics: Track successful session invalidation
                   (metrics/increment-counter
                    metrics-emitter
                    "session-invalidations-successful"
                    {:tenant-id (:tenant-id session)})

                    ;; Update active sessions gauge
                   (update-active-sessions-gauge metrics-emitter session-repository)

                      ;; Return result
                   {:invalidated true :session-id (:id session)})

                 (do
                   ;; Add failure breadcrumb for not found session
                   (error-reporting/add-breadcrumb error-reporter
                                                   "User logout failed - session not found"
                                                   "service.user.validation"
                                                   :warning
                                                   {:session-token (str/replace session-token #".{20}$" "***")})

                    ;; Session not found
                   (.warn logger "Session token not found for invalidation" context)

                    ;; Metrics: Track session invalidation failure
                   (metrics/increment-counter
                    metrics-emitter
                    "session-invalidations-failed"
                    {:reason "not-found"})
                   {:invalidated false}))

               (catch Exception e
                 ;; Capture exception with error reporting
                 (error-reporting/report-application-error error-reporter e
                                                           "User logout failed"
                                                           {:extra {:operation "logout-user"
                                                                    :session-token (str/replace session-token #".{20}$" "***")}
                                                            :tags {:component "service.user"
                                                                   :operation "logout-user"}})

                 ;; Metrics: Track invalidation system error
                 (metrics/increment-counter metrics-emitter "session-invalidations-failed"
                                            {:reason "system-error"})
                 ;; Re-throw the exception
                 (throw e)))))))))

  ;; Additional IUserService methods
  (get-user-by-id [this user-id]
    (let [context {:operation "get-user-by-id" :user-id user-id}]
      ;; Add error reporting breadcrumb
      (error-reporting/add-breadcrumb error-reporter
                                      "Starting get user by ID"
                                      "service.user"
                                      :info
                                      {:operation "get-user-by-id"
                                       :user-id user-id})

      (logging/with-function-logging
        logger
        "get-user-by-id"
        context
        (fn []
          (.info logger "Getting user by ID" {:user-id user-id})

          (try
            (let [user (.find-user-by-id user-repository user-id)]
              ;; Add success breadcrumb
              (error-reporting/add-breadcrumb error-reporter
                                              (if user "User found by ID" "User not found by ID")
                                              "service.user"
                                              :info
                                              {:user-id user-id
                                               :found (boolean user)})
              user)
            (catch Exception e
              ;; Capture exception with error reporting
              (error-reporting/report-application-error error-reporter e
                                                        "Get user by ID failed"
                                                        {:extra {:operation "get-user-by-id"
                                                                 :user-id user-id}
                                                         :tags {:component "service.user"
                                                                :operation "get-user-by-id"}})
              ;; Re-throw the exception
              (throw e)))))))

  (get-user-by-email [this email tenant-id]
    (let [context {:operation "get-user-by-email" :email email :tenant-id tenant-id}]
      ;; Add error reporting breadcrumb
      (error-reporting/add-breadcrumb error-reporter
                                      "Starting get user by email"
                                      "service.user"
                                      :info
                                      {:operation "get-user-by-email"
                                       :email email
                                       :tenant-id tenant-id})

      (logging/with-function-logging
        logger
        "get-user-by-email"
        context
        (fn []
          (.info logger "Getting user by email" {:email email :tenant-id tenant-id})

          (try
            (let [user (.find-user-by-email user-repository email tenant-id)]
              ;; Add success breadcrumb
              (error-reporting/add-breadcrumb error-reporter
                                              (if user "User found by email" "User not found by email")
                                              "service.user"
                                              :info
                                              {:email email
                                               :tenant-id tenant-id
                                               :found (boolean user)})
              user)
            (catch Exception e
              ;; Capture exception with error reporting
              (error-reporting/report-application-error error-reporter e
                                                        "Get user by email failed"
                                                        {:extra {:operation "get-user-by-email"
                                                                 :email email
                                                                 :tenant-id tenant-id}
                                                         :tags {:component "service.user"
                                                                :operation "get-user-by-email"}})
              ;; Re-throw the exception
              (throw e)))))))

  (list-users-by-tenant [this tenant-id options]
    (let [context {:operation "list-users-by-tenant" :tenant-id tenant-id :options options}]
      ;; Add error reporting breadcrumb
      (error-reporting/add-breadcrumb error-reporter
                                      "Starting list users by tenant"
                                      "service.user"
                                      :info
                                      {:operation "list-users-by-tenant"
                                       :tenant-id tenant-id
                                       :options options})

      (logging/with-function-logging
        logger
        "list-users-by-tenant"
        context
        (fn []
          (.info logger "Listing users by tenant" {:tenant-id tenant-id :options options})

          (try
            (let [users (.find-users-by-tenant user-repository tenant-id options)]
              ;; Add success breadcrumb
              (error-reporting/add-breadcrumb error-reporter
                                              "Users listed by tenant"
                                              "service.user"
                                              :info
                                              {:tenant-id tenant-id
                                               :user-count (count (:users users))
                                               :total-count (:total-count users)})
              users)
            (catch Exception e
              ;; Capture exception with error reporting
              (error-reporting/report-application-error error-reporter e
                                                        "List users by tenant failed"
                                                        {:extra {:operation "list-users-by-tenant"
                                                                 :tenant-id tenant-id
                                                                 :options options}
                                                         :tags {:component "service.user"
                                                                :operation "list-users-by-tenant"}})
              ;; Re-throw the exception
              (throw e)))))))

  (update-user-profile [this user-entity]
    (let [context {:operation "update-user-profile" :user-id (:id user-entity)}]
      ;; Add error reporting breadcrumb
      (error-reporting/add-breadcrumb error-reporter
                                      "Starting user profile update"
                                      "service.user"
                                      :info
                                      {:operation "update-user-profile"
                                       :user-id (:id user-entity)
                                       :tenant-id (:tenant-id user-entity)})

      (logging/with-function-logging
        logger
        "update-user-profile"
        context
        (fn []
          (.info logger "Updating user profile" {:user-id (:id user-entity)})

          (try
            ;; 1. Validate update using pure core function
            (let [validation-result (user-core/validate-user-update-request user-entity)]
              (when-not (:valid? validation-result)
                ;; Add error reporting breadcrumb for validation failure
                (error-reporting/add-breadcrumb error-reporter
                                                "User profile validation failed"
                                                "service.user.validation"
                                                :warning
                                                {:validation-errors (:errors validation-result)
                                                 :user-id (:id user-entity)})
                (throw (ex-info "Invalid user data"
                                {:type :validation-error
                                 :errors (:errors validation-result)}))))

            ;; 2. Persist using impure shell persistence layer
            (let [updated-user (.update-user user-repository user-entity)]
              ;; Add success breadcrumb
              (error-reporting/add-breadcrumb error-reporter
                                              "User profile update successful"
                                              "service.user"
                                              :info
                                              {:user-id (:id updated-user)
                                               :tenant-id (:tenant-id updated-user)})
              updated-user)

            (catch Exception e
              ;; Capture exception with error reporting
              (error-reporting/report-application-error error-reporter e
                                                        "User profile update failed"
                                                        {:extra {:operation "update-user-profile"
                                                                 :user-id (:id user-entity)
                                                                 :user-entity user-entity}
                                                         :tags {:component "service.user"
                                                                :operation "update-user-profile"}})
              ;; Re-throw the exception
              (throw e)))))))

  (deactivate-user [this user-id]
    (let [context {:operation "deactivate-user" :user-id user-id}]
      (logging/with-function-logging
        logger
        "deactivate-user"
        context
        (fn []
          (.info logger "Deactivating user" {:user-id user-id})
          (try
            (let [result (.soft-delete-user user-repository user-id)]
              (boolean result))
            (catch Exception e
              (throw e)))))))

  (permanently-delete-user [this user-id]
    (let [context {:operation "permanently-delete-user" :user-id user-id}]
      (logging/with-function-logging
        logger
        "permanently-delete-user"
        context
        (fn []
          (.info logger "Permanently deleting user" {:user-id user-id})
          (try
            (let [result (.hard-delete-user user-repository user-id)]
              (boolean result))
            (catch Exception e
              (throw e)))))))

  (logout-user-everywhere [this user-id]
    (let [context {:operation "logout-user-everywhere" :user-id user-id}]
      (logging/with-function-logging
        logger
        "logout-user-everywhere"
        context
        (fn []
          (.info logger "Logging out user from all sessions" {:user-id user-id})
          (try
            (.invalidate-all-user-sessions session-repository user-id)
            (catch Exception e
              (throw e))))))))

;; =============================================================================
;; Active Sessions Gauge Management
;; =============================================================================

(defn create-gauge-refresh-scheduler
  "Create a function that can be called periodically to refresh gauge metrics.
   
   Args:
     user-service: UserService instance
   
   Returns:
     Function that updates all gauge metrics when called"
  [user-service]
  (fn refresh-gauges []
    (try
      (update-active-sessions-gauge (:metrics-emitter user-service) (:session-repository user-service))
      (catch Exception e
        (.warn nil "Failed to refresh gauge metrics" {:error (.getMessage e)})))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-user-service
  "Create a user service instance with injected repositories.

         Args:
           user-repository: Implementation of IUserRepository
           session-repository: Implementation of IUserSessionRepository
           logger: Logging service instance
           metrics-emitter: Metrics collection service instance
           error-reporter: Error reporting service instance

         Returns:
           UserService instance with gauge refresh scheduler

         Example:
           (def service (create-user-service user-repo session-repo logger metrics error-reporter))"
  [user-repository session-repository logger metrics-emitter error-reporter]
  (let [service (->UserService user-repository session-repository logger metrics-emitter error-reporter)
        gauge-refresh-fn (create-gauge-refresh-scheduler service)]
    ;; Initialize gauges immediately
    (gauge-refresh-fn)
    ;; Return service with gauge refresh function attached as metadata
    (with-meta service {:gauge-refresh-fn gauge-refresh-fn})))

(defn get-gauge-refresh-fn
  "Get the gauge refresh function from service metadata.
   
   Args:
     user-service: UserService instance created with create-user-service
   
   Returns:
     Function that refreshes gauge metrics when called
     Should be called periodically (e.g., every 30 seconds) by system scheduler
   
   Example:
     (let [service (create-user-service user-repo session-repo logger metrics)
           refresh-fn (get-gauge-refresh-fn service)]
       ;; Schedule refresh-fn to run every 30 seconds
       (schedule refresh-fn 30 :seconds))"
  [user-service]
  (:gauge-refresh-fn (meta user-service)))