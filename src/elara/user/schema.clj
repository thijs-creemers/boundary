(ns elara.user.schema
  "User module schema definitions using Malli.
   
   This namespace contains all schemas related to the user domain:
   - Domain entity schemas (User, UserPreferences, UserSession)
   - API request/response schemas with proper transformations
   - CLI argument schemas with string-to-type coercions
   - Schema transformers for different interfaces
   - Validation and transformation utilities"
  (:require [malli.core :as m]
            [malli.transform :as mt]
            [malli.util :as mu]
            [elara.shared.schema :as shared]
            [java-time :as time]))

;; =============================================================================
;; Domain Entity Schemas
;; =============================================================================

(def User
  "Core user domain entity schema with all user attributes."
  [:map {:title "User Entity" :description "Complete user domain model"}
   [:id :uuid]
   [:email [:string {:min 5 :max 255 :description "User's email address"}]]
   [:name [:string {:min 1 :max 100 :description "User's display name"}]]
   [:role [:enum {:description "User's role in the system"}
           :admin :user :viewer]]
   [:active {:description "Whether the user account is active"}
    :boolean]
   [:tenant-id {:description "Tenant isolation - users belong to specific tenant"}
    :uuid]
   [:created-at {:description "Account creation timestamp"}
    :inst]
   [:updated-at {:description "Last account modification timestamp"}
    [:maybe :inst]]
   [:deleted-at {:description "Soft delete timestamp - NULL means active user"} 
    [:maybe :inst]]])

(def UserPreferences
  "User preference settings schema."
  [:map {:title "User Preferences"}
   [:notifications {:description "Notification preferences"}
    [:map
     [:email :boolean]
     [:push :boolean]
     [:sms {:optional true} :boolean]]]
   [:theme {:description "UI theme preference"}
    [:enum :light :dark :auto]]
   [:language {:description "Language preference (ISO 639-1)"}
    [:string {:min 2 :max 2}]]
   [:timezone {:description "User's timezone (IANA format)"}
    [:string {:min 3 :max 50}]]
   [:date-format {:description "Preferred date format"}
    [:enum :iso :us :eu]]
   [:time-format {:description "Preferred time format"}
    [:enum :12h :24h]]])

(def UserSession
  "User authentication session schema."
  [:map {:title "User Session"}
   [:id :uuid]
   [:user-id {:description "ID of the user this session belongs to"}
    :uuid]
   [:tenant-id {:description "Tenant context for the session"}
    :uuid]
   [:session-token {:description "Secure session token"}
    [:string {:min 32 :max 128}]]
   [:expires-at {:description "Session expiration timestamp"}
    :inst]
   [:created-at {:description "Session creation timestamp"}
    :inst]
   [:user-agent {:description "Browser/client user agent string"}
    [:maybe [:string {:max 500}]]]
   [:ip-address {:description "Client IP address"}
    [:maybe [:string {:max 45}]]]  ; IPv6 max length
   [:last-accessed-at {:description "Last time session was used"}
    [:maybe :inst]]
   [:revoked-at {:description "Session revocation timestamp"}
    [:maybe :inst]]])

(def UserProfile
  "Extended user profile with additional details."
  [:map {:title "User Profile"}
   [:user User]
   [:preferences {:optional true} UserPreferences]
   [:last-login {:optional true} :inst]
   [:login-count {:optional true} [:int {:min 0}]]
   [:avatar-url {:optional true} [:string {:max 500}]]
   [:bio {:optional true} [:string {:max 1000}]]
   [:phone {:optional true} [:string {:min 10 :max 20}]]
   [:location {:optional true} [:string {:max 100}]]])

;; =============================================================================
;; API Request Schemas
;; =============================================================================

(def CreateUserRequest
  "Schema for creating a new user via API."
  [:map {:title "Create User Request"}
   [:email [:string {:min 5 :max 255}]]
   [:name [:string {:min 1 :max 100}]]
   [:role [:enum :admin :user :viewer]]
   [:active {:optional true :default true} :boolean]
   [:send-welcome {:optional true :default true} :boolean]
   [:preferences {:optional true} UserPreferences]])

(def UpdateUserRequest
  "Schema for updating an existing user via API."
  [:map {:title "Update User Request"}
   [:name {:optional true} [:string {:min 1 :max 100}]]
   [:role {:optional true} [:enum :admin :user :viewer]]
   [:active {:optional true} :boolean]
   [:preferences {:optional true} UserPreferences]])

(def ChangePasswordRequest
  "Schema for user password change requests."
  [:map {:title "Change Password Request"}
   [:current-password [:string {:min 1 :max 255}]]
   [:new-password [:string {:min 8 :max 255}]]
   [:confirm-password [:string {:min 8 :max 255}]]])

(def ResetPasswordRequest
  "Schema for password reset initiation."
  [:map {:title "Reset Password Request"}
   [:email [:string {:min 5 :max 255}]]])

(def CompletePasswordResetRequest
  "Schema for completing password reset."
  [:map {:title "Complete Password Reset Request"}
   [:token [:string {:min 32 :max 128}]]
   [:new-password [:string {:min 8 :max 255}]]
   [:confirm-password [:string {:min 8 :max 255}]]])

(def LoginRequest
  "Schema for user login requests."
  [:map {:title "Login Request"}
   [:email [:string {:min 5 :max 255}]]
   [:password [:string {:min 1 :max 255}]]
   [:remember-me {:optional true :default false} :boolean]
   [:client-info {:optional true}
    [:map
     [:user-agent {:optional true} :string]
     [:ip-address {:optional true} :string]
     [:device-type {:optional true} [:enum :desktop :mobile :tablet]]]]])

;; =============================================================================
;; API Response Schemas
;; =============================================================================

(def UserResponse
  "Schema for user data in API responses."
  [:map {:title "User Response"}
   [:id :string]  ; UUIDs serialized as strings in API
   [:email :string]
   [:name :string]
   [:role :string]
   [:active :boolean]
   [:tenantId :string]  ; camelCase for API consistency
   [:createdAt :string]  ; ISO 8601 timestamp
   [:updatedAt [:maybe :string]]])

(def UserProfileResponse
  "Schema for extended user profile in API responses."
  [:map {:title "User Profile Response"}
   [:user UserResponse]
   [:preferences {:optional true}
    [:map
     [:notifications [:map
                     [:email :boolean]
                     [:push :boolean]
                     [:sms {:optional true} :boolean]]]
     [:theme :string]
     [:language :string]
     [:timezone :string]
     [:dateFormat :string]
     [:timeFormat :string]]]
   [:lastLogin {:optional true} :string]
   [:loginCount {:optional true} :int]
   [:avatarUrl {:optional true} :string]
   [:bio {:optional true} :string]
   [:phone {:optional true} :string]
   [:location {:optional true} :string]])

(def LoginResponse
  "Schema for login response with session information."
  [:map {:title "Login Response"}
   [:user UserResponse]
   [:session [:map
             [:token :string]
             [:expiresAt :string]
             [:createdAt :string]]]
   [:permissions {:optional true} [:vector :string]]])

(def PaginatedUsersResponse
  "Schema for paginated user list responses."
  [:map {:title "Paginated Users Response"}
   [:data [:vector UserResponse]]
   [:meta shared/PaginationResponse]])

;; =============================================================================
;; CLI Argument Schemas
;; =============================================================================

(def CreateUserCLIArgs
  "Schema for CLI arguments when creating users."
  [:map {:title "Create User CLI Arguments"}
   [:email :string]
   [:name :string]
   [:role :string]  ; String input, converted to keyword
   [:active {:optional true} [:or :boolean :string]]  ; CLI can provide "true"/"false"
   [:send-welcome {:optional true} [:or :boolean :string]]
   [:tenant-id {:optional true} :string]])  ; UUID as string from CLI

(def UpdateUserCLIArgs
  "Schema for CLI arguments when updating users."
  [:map {:title "Update User CLI Arguments"}
   [:id :string]  ; UUID as string from CLI
   [:name {:optional true} :string]
   [:role {:optional true} :string]
   [:active {:optional true} [:or :boolean :string]]])

(def ListUsersCLIArgs
  "Schema for CLI arguments when listing users."
  [:map {:title "List Users CLI Arguments"}
   [:limit {:optional true} [:or :int :string]]
   [:offset {:optional true} [:or :int :string]]
   [:sort {:optional true} :string]
   [:filter-role {:optional true} :string]
   [:filter-active {:optional true} [:or :boolean :string]]
   [:filter-email {:optional true} :string]
   [:tenant-id {:optional true} :string]])

(def LoginCLIArgs
  "Schema for CLI login arguments."
  [:map {:title "Login CLI Arguments"}
   [:email :string]
   [:password :string]
   [:save-session {:optional true} [:or :boolean :string]]])

;; =============================================================================
;; Schema Transformers
;; =============================================================================

(def user-request-transformer
  "Transforms external API data to internal domain format.
   
   Handles:
   - String to keyword conversion for enums
   - String boolean to boolean conversion
   - Extra field stripping
   - Nested object transformation"
  (mt/transformer
   mt/strip-extra-keys-transformer
   mt/string-transformer
   {:name :user-request
    :transformers
    {:map {:compile (fn [schema _options]
                     (fn [value]
                       ;; Transform camelCase API keys to kebab-case internal keys
                       (cond-> value
                         (:tenantId value) (-> (assoc :tenant-id (:tenantId value))
                                               (dissoc :tenantId))
                         (:sendWelcome value) (-> (assoc :send-welcome (:sendWelcome value))
                                                  (dissoc :sendWelcome))))}
     :enum {:compile (fn [_schema _options]
                      (fn [value]
                        (cond
                          (keyword? value) value
                          (string? value) (keyword value)
                          :else value)))}
     :boolean {:compile (fn [_schema _options]
                         (fn [value]
                           (cond
                             (boolean? value) value
                             (string? value) (case (.toLowerCase value)
                                              "true" true
                                              "false" false
                                              "1" true
                                              "0" false
                                              value)
                             :else value)))}}})

(def user-response-transformer
  "Transforms internal domain data to external API format.
   
   Handles:
   - UUID to string conversion
   - Instant to ISO 8601 string conversion
   - Kebab-case to camelCase key transformation
   - Keyword to string conversion for enums"
  (mt/transformer
   {:name :user-response
    :transformers
    {:map {:compile (fn [schema _options]
                     (fn [value]
                       ;; Transform kebab-case internal keys to camelCase API keys
                       (cond-> value
                         (:id value) (assoc :id (str (:id value)))
                         (:tenant-id value) (-> (assoc :tenantId (str (:tenant-id value)))
                                                 (dissoc :tenant-id))
                         (:created-at value) (-> (assoc :createdAt (.toString (:created-at value)))
                                                 (dissoc :created-at))
                         (:updated-at value) (-> (assoc :updatedAt 
                                                        (when (:updated-at value)
                                                          (.toString (:updated-at value))))
                                                 (dissoc :updated-at))
                         (:last-login value) (-> (assoc :lastLogin
                                                        (when (:last-login value)
                                                          (.toString (:last-login value))))
                                                 (dissoc :last-login))
                         (:login-count value) (-> (assoc :loginCount (:login-count value))
                                                  (dissoc :login-count))
                         (:avatar-url value) (-> (assoc :avatarUrl (:avatar-url value))
                                                 (dissoc :avatar-url))
                         (:date-format value) (-> (assoc :dateFormat (name (:date-format value)))
                                                  (dissoc :date-format))
                         (:time-format value) (-> (assoc :timeFormat (name (:time-format value)))
                                                  (dissoc :time-format))))}
     :uuid {:compile (fn [_schema _options] str)}
     :inst {:compile (fn [_schema _options] #(.toString %))}
     :enum {:compile (fn [_schema _options] name)}}})

(def cli-transformer
  "Transforms CLI string arguments to appropriate types.
   
   Handles:
   - String to boolean conversion (true/false, 1/0)
   - String to integer conversion
   - String to UUID conversion
   - String to keyword conversion for enums"
  (mt/transformer
   mt/string-transformer
   {:name :cli-transformer
    :transformers
    {:boolean {:compile (fn [_schema _options]
                         (fn [value]
                           (cond
                             (boolean? value) value
                             (string? value) (case (.toLowerCase value)
                                              "true" true
                                              "false" false
                                              "1" true
                                              "0" false
                                              "yes" true
                                              "no" false
                                              "on" true
                                              "off" false
                                              value) ; Return unchanged if not recognized
                             :else value)))}
     :int {:compile (fn [_schema _options]
                     (fn [value]
                       (cond
                         (int? value) value
                         (string? value) (try
                                          (Integer/parseInt value)
                                          (catch NumberFormatException _
                                            value))
                         :else value)))}
     :uuid {:compile (fn [_schema _options]
                      (fn [value]
                        (cond
                          (uuid? value) value
                          (string? value) (try
                                           (java.util.UUID/fromString value)
                                           (catch IllegalArgumentException _
                                             value))
                          :else value)))}
     :enum {:compile (fn [_schema _options]
                      (fn [value]
                        (cond
                          (keyword? value) value
                          (string? value) (keyword value)
                          :else value)))}}})

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate-user
  "Validates a user entity against the User schema."
  [user-data]
  (m/validate User user-data))

(defn explain-user
  "Provides detailed validation errors for user data."
  [user-data]
  (m/explain User user-data))

(defn validate-create-user-request
  "Validates create user request with transformation."
  [request-data]
  (let [transformed-data (m/transform CreateUserRequest request-data user-request-transformer)]
    (if (m/validate CreateUserRequest transformed-data)
      {:valid? true :data transformed-data}
      {:valid? false :errors (m/explain CreateUserRequest transformed-data)})))

(defn validate-update-user-request
  "Validates update user request with transformation."
  [request-data]
  (let [transformed-data (m/transform UpdateUserRequest request-data user-request-transformer)]
    (if (m/validate UpdateUserRequest transformed-data)
      {:valid? true :data transformed-data}
      {:valid? false :errors (m/explain UpdateUserRequest transformed-data)})))

(defn validate-login-request
  "Validates login request with transformation."
  [request-data]
  (let [transformed-data (m/transform LoginRequest request-data user-request-transformer)]
    (if (m/validate LoginRequest transformed-data)
      {:valid? true :data transformed-data}
      {:valid? false :errors (m/explain LoginRequest transformed-data)})))

(defn validate-cli-args
  "Validates CLI arguments with appropriate transformations."
  [schema args]
  (let [transformed-args (m/transform schema args cli-transformer)]
    (if (m/validate schema transformed-args)
      {:valid? true :data transformed-args}
      {:valid? false :errors (m/explain schema transformed-args)})))

;; =============================================================================
;; Data Transformation Utilities
;; =============================================================================

(defn user-entity->response
  "Converts internal user entity to API response format."
  [user-entity]
  (m/transform UserResponse user-entity user-response-transformer))

(defn user-profile->response
  "Converts internal user profile to API response format."
  [user-profile]
  (m/transform UserProfileResponse user-profile user-response-transformer))

(defn request->user-entity
  "Converts API request to internal user entity format."
  [request-data]
  (m/transform User request-data user-request-transformer))

(defn paginated-users->response
  "Converts paginated user data to API response format."
  [users pagination-meta]
  {:data (mapv user-entity->response users)
   :meta pagination-meta})

;; =============================================================================
;; Schema Utilities
;; =============================================================================

(defn get-user-fields
  "Returns the field names for the User schema."
  []
  (->> User
       m/children
       (mapv (comp name first))))

(defn get-required-user-fields
  "Returns the required field names for the User schema."
  []
  (->> User
       m/children
       (remove (fn [[_ schema]] 
                 (or (m/optional-key? schema)
                     (= :maybe (m/type schema)))))
       (mapv (comp name first))))

(defn get-optional-user-fields
  "Returns the optional field names for the User schema."
  []
  (->> User
       m/children
       (filter (fn [[_ schema]] 
                 (or (m/optional-key? schema)
                     (= :maybe (m/type schema)))))
       (mapv (comp name first))))

(defn merge-user-schemas
  "Merges multiple user-related schemas."
  [& schemas]
  (reduce mu/merge schemas))

;; =============================================================================
;; Schema Registry
;; =============================================================================

(def schema-registry
  "Registry of all user module schemas for easy access."
  {:domain-entities
   {:user User
    :user-preferences UserPreferences
    :user-session UserSession
    :user-profile UserProfile}
   
   :api-requests
   {:create-user CreateUserRequest
    :update-user UpdateUserRequest
    :change-password ChangePasswordRequest
    :reset-password ResetPasswordRequest
    :complete-password-reset CompletePasswordResetRequest
    :login LoginRequest}
   
   :api-responses
   {:user UserResponse
    :user-profile UserProfileResponse
    :login LoginResponse
    :paginated-users PaginatedUsersResponse}
   
   :cli-arguments
   {:create-user CreateUserCLIArgs
    :update-user UpdateUserCLIArgs
    :list-users ListUsersCLIArgs
    :login LoginCLIArgs}})

(defn get-schema
  "Retrieves a schema from the registry by category and name."
  [category schema-name]
  (get-in schema-registry [category schema-name]))

(defn list-schemas
  "Lists all available schemas in a category."
  [category]
  (keys (get schema-registry category)))

;; =============================================================================
;; Schema Generators (for testing)
;; =============================================================================

(defn generate-user
  "Generates a valid user entity for testing purposes."
  ([]]
   (generate-user {}))
  ([overrides]
   (merge
    {:id (java.util.UUID/randomUUID)
     :email (str "user" (rand-int 10000) "@example.com")
     :name (str "Test User " (rand-int 1000))
     :role (rand-nth [:admin :user :viewer])
     :active true
     :tenant-id (java.util.UUID/randomUUID)
     :created-at (time/instant)
     :updated-at nil
     :deleted-at nil}
    overrides)))

(defn generate-user-preferences
  "Generates valid user preferences for testing."
  ([]]
   (generate-user-preferences {}))
  ([overrides]
   (merge
    {:notifications {:email true
                    :push false
                    :sms true}
     :theme (rand-nth [:light :dark :auto])
     :language "en"
     :timezone "UTC"
     :date-format (rand-nth [:iso :us :eu])
     :time-format (rand-nth [:12h :24h])}
    overrides)))

(defn generate-create-user-request
  "Generates a valid create user request for testing."
  ([]]
   (generate-create-user-request {}))
  ([overrides]
   (merge
    {:email (str "newuser" (rand-int 10000) "@example.com")
     :name (str "New User " (rand-int 1000))
     :role (rand-nth [:admin :user :viewer])
     :active true
     :send-welcome true}
    overrides)))
