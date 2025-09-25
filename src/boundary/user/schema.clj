(ns boundary.user.schema
  (:require
   [malli.core :as m]
   [malli.transform :as mt]
   [malli.util :as mu])
  (:import (java.time Instant)
           (java.util UUID)))

;; =============================================================================
;; Domain Entity Schemas
;; =============================================================================

(def User
  "Schema for User entity."
  [:map {:title "User"}
   [:id :uuid]
   [:email :string]
   [:name :string]
   [:role [:enum :admin :user :viewer]]
   [:active :boolean]
   [:login-count {:optional true} :int]
   [:last-login {:optional true} :string]
   [:date-format {:optional true} [:enum :iso :us :eu]]
   [:time-format {:optional true} [:enum :12h :24h]]
   [:tenant-id :uuid]
   [:avatar-url {:optional true} :string]
   [:created-at :string]
   [:updated-at {:optional true} :string]
   [:deleted-at {:optional true} :string]])

(def UserPreferences
  "Schema for user preferences."
  [:map {:title "User Preferences"}
   [:notifications
    [:map
     [:email :boolean]
     [:push :boolean]
     [:sms :boolean]]]
   [:theme [:enum :light :dark :auto]]
   [:language :string]
   [:timezone :string]
   [:date-format [:enum :iso :us :eu]]
   [:time-format [:enum :12h :24h]]])

(def UserSession
  "Schema for user session."
  [:map {:title "User Session"}
   [:user-id :uuid]
   [:token :string]
   [:expires-at :string]
   [:created-at :string]])

(def UserProfile
  "Schema for user profile, combining user and preferences."
  (mu/merge User UserPreferences))

;; =============================================================================
;; API Request Schemas
;; =============================================================================

(def CreateUserRequest
  "Schema for create user API requests."
  [:map {:title "Create User Request"}
   [:email :string]
   [:name :string]
   [:role [:enum :admin :user :viewer]]
   [:active {:optional true} :boolean]
   [:tenant-id :uuid]
   [:send-welcome {:optional true} :boolean]])

(def UpdateUserRequest
  "Schema for update user API requests."
  [:map {:title "Update User Request"}
   [:name {:optional true} :string]
   [:role {:optional true} [:enum :admin :user :viewer]]
   [:active {:optional true} :boolean]])

(def LoginRequest
  "Schema for login API requests."
  [:map {:title "Login Request"}
   [:email :string]
   [:password :string]
   [:remember {:optional true} :boolean]])

(def ChangePasswordRequest
  "Schema for password change requests."
  [:map {:title "Change Password Request"}
   [:current-password :string]
   [:new-password :string]
   [:confirm-password :string]])

(def ResetPasswordRequest
  "Schema for password reset requests."
  [:map {:title "Reset Password Request"}
   [:email :string]])

(def CompletePasswordResetRequest
  "Schema for completing password reset."
  [:map {:title "Complete Password Reset Request"}
   [:token :string]
   [:new-password :string]
   [:confirm-password :string]])

;; =============================================================================
;; API Response Schemas
;; =============================================================================

(def UserResponse
  "Schema for user responses."
  [:map {:title "User Response"}
   [:id :string] ; UUID as string
   [:email :string]
   [:name :string]
   [:role :string] ; Enum as string
   [:active :boolean]
   [:loginCount {:optional true} :int]
   [:lastLogin {:optional true} :string] ; Instant as ISO string
   [:tenantId :string] ; UUID as string
   [:avatarUrl {:optional true} :string]
   [:createdAt :string] ; Instant as ISO string
   [:updatedAt {:optional true} :string]]) ; Instant as ISO string

(def UserProfileResponse
  "Schema for user profile responses."
  (mu/merge
   UserResponse
   [:map
    [:notifications
     [:map
      [:email :boolean]
      [:push :boolean]
      [:sms :boolean]]]
    [:theme :string] ; Enum as string
    [:language :string]
    [:timezone :string]
    [:dateFormat :string] ; Enum as string
    [:timeFormat :string]])) ; Enum as string

(def LoginResponse
  "Schema for login responses."
  [:map {:title "Login Response"}
   [:token :string]
   [:user UserResponse]
   [:expiresAt :string]]) ; Instant as ISO string

(def PaginatedUsersResponse
  "Schema for paginated user responses."
  [:map {:title "Paginated Users Response"}
   [:data [:vector UserResponse]]
   [:meta
    [:map
     [:total :int]
     [:limit :int]
     [:offset :int]
     [:page :int]
     [:pages :int]]]])

;; =============================================================================
;; CLI Argument Schemas
;; =============================================================================

(def CreateUserCLIArgs
  "Schema for CLI arguments when creating users."
  [:map {:title "Create User CLI Arguments"}
   [:email :string]
   [:name :string]
   [:role :string] ; String input, converted to keyword
   [:active {:optional true} [:or :boolean :string]] ; CLI can provide "true"/"false"
   [:send-welcome {:optional true} [:or :boolean :string]]
   [:tenant-id {:optional true} :string]]) ; UUID as string from CLI

(def UpdateUserCLIArgs
  "Schema for CLI arguments when updating users."
  [:map {:title "Update User CLI Arguments"}
   [:id :string] ; UUID as string from CLI
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

;; =============================================================================
;; Transformer Helper Functions
;; =============================================================================

(defn camel-case->kebab-case-map
  "Transforms camelCase API keys to kebab-case internal keys."
  [value]
  (cond-> value
    (:tenantId value) (-> (assoc :tenant-id (:tenantId value))
                          (dissoc :tenantId))
    (:sendWelcome value) (-> (assoc :send-welcome (:sendWelcome value))
                             (dissoc :sendWelcome))))

(defn string->enum
  "Converts string values to keywords for enums."
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

(defn string->boolean
  "Converts string values to booleans with support for various formats."
  [value]
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
                      value)
    :else value))

(defn kebab-case->camel-case-map
  "Transforms kebab-case internal keys to camelCase API keys."
  [value]
  (cond-> value
    (:id value) (assoc :id (str (:id value)))
    (:tenant-id value) (-> (assoc :tenantId (str (:tenant-id value)))
                           (dissoc :tenant-id))
    (:created-at value) (-> (assoc :createdAt (:created-at value))
                            (dissoc :created-at))
    (:updated-at value) (-> (assoc :updatedAt (:updated-at value))
                            (dissoc :updated-at))
    (:last-login value) (-> (assoc :lastLogin (:last-login value))
                            (dissoc :last-login))
    (:login-count value) (-> (assoc :loginCount (:login-count value))
                             (dissoc :login-count))
    (:avatar-url value) (-> (assoc :avatarUrl (:avatar-url value))
                            (dissoc :avatar-url))
    (:date-format value) (-> (assoc :dateFormat (name (:date-format value)))
                             (dissoc :date-format))
    (:time-format value) (-> (assoc :timeFormat (name (:time-format value)))
                             (dissoc :time-format))
    (:role value) (assoc :role (name (:role value)))
    (:theme value) (assoc :theme (name (:theme value)))))

(defn string->int
  "Converts string values to integers."
  [value]
  (cond
    (int? value) value
    (string? value) (try
                      (Integer/parseInt value)
                      (catch NumberFormatException _
                        value))
    :else value))

(defn string->uuid
  "Converts string values to UUIDs."
  [value]
  (cond
    (uuid? value) value
    (string? value) (try
                      (UUID/fromString value)
                      (catch IllegalArgumentException _
                        value))
    :else value))

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
    {:map {:compile (fn [_schema _options] camel-case->kebab-case-map)}
     :enum {:compile (fn [_schema _options] string->enum)}
     :boolean {:compile (fn [_schema _options] string->boolean)}}}))

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
    {:map {:compile (fn [_schema _options] kebab-case->camel-case-map)}
     :uuid {:compile (fn [_schema _options] str)}
     :inst {:compile (fn [_schema _options] #(.toString %))}
     :enum {:compile (fn [_schema _options] name)}}}))

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
    {:boolean {:compile (fn [_schema _options] string->boolean)}
     :int {:compile (fn [_schema _options] string->int)}
     :uuid {:compile (fn [_schema _options] string->uuid)}
     :enum {:compile (fn [_schema _options] string->enum)}}}))

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
  (let [transformed-data (m/decode CreateUserRequest request-data user-request-transformer)]
    (if (m/validate CreateUserRequest transformed-data)
      {:valid? true :data transformed-data}
      {:valid? false :errors (m/explain CreateUserRequest transformed-data)})))

(defn validate-update-user-request
  "Validates update user request with transformation."
  [request-data]
  (let [transformed-data (m/decode UpdateUserRequest request-data user-request-transformer)]
    (if (m/validate UpdateUserRequest transformed-data)
      {:valid? true :data transformed-data}
      {:valid? false :errors (m/explain UpdateUserRequest transformed-data)})))

(defn validate-login-request
  "Validates login request with transformation."
  [request-data]
  (let [transformed-data (m/decode LoginRequest request-data user-request-transformer)]
    (if (m/validate LoginRequest transformed-data)
      {:valid? true :data transformed-data}
      {:valid? false :errors (m/explain LoginRequest transformed-data)})))

(defn validate-cli-args
  "Validates CLI arguments with appropriate transformations."
  [schema args]
  (let [transformed-args (m/decode schema args cli-transformer)]
    (if (m/validate schema transformed-args)
      {:valid? true :data transformed-args}
      {:valid? false :errors (m/explain schema transformed-args)})))

;; =============================================================================
;; Data Transformation Utilities
;; =============================================================================

(defn user-entity->response
  "Transforms a user entity into an API response."
  [user-data]
  (kebab-case->camel-case-map user-data))

(defn user-profile-entity->response
  "Transforms a user profile entity into an API response."
  [user-profile-data]
  (kebab-case->camel-case-map user-profile-data))

(defn users->paginated-response
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
       (remove (fn [[_key props _schema]]
                 (or (:optional props)
                     (= :maybe (m/type _schema)))))
       (mapv (comp name first))))

(defn get-optional-user-fields
  "Returns the optional field names for the User schema."
  []
  (->> User
       m/children
       (filter (fn [[_key props _schema]]
                 (:optional props)))
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
  ([]
   (generate-user {}))
  ([overrides]
   (merge
    {:id (UUID/randomUUID)
     :email (str "user" (rand-int 10000) "@example.com")
     :name (str "Test User " (rand-int 1000))
     :role (rand-nth [:admin :user :viewer])
     :active true
     :tenant-id (UUID/randomUUID)
     :created-at (str (Instant/now))
     :updated-at nil
     :deleted-at nil}
    overrides)))

(defn generate-user-preferences
  "Generates valid user preferences for testing."
  ([]
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
  ([]
   (generate-create-user-request {}))
  ([overrides]
   (merge
    {:email (str "newuser" (rand-int 10000) "@example.com")
     :name (str "New User " (rand-int 1000))
     :role (rand-nth [:admin :user :viewer])
     :active true
     :tenant-id (UUID/randomUUID)
     :send-welcome true}
    overrides)))

(defn generate-update-user-request
  "Generates a valid update user request for testing."
  ([]
   (generate-update-user-request {}))
  ([overrides]
   (merge
    {:name (str "Updated User " (rand-int 1000))
     :role (rand-nth [:admin :user :viewer])
     :active (rand-nth [true false])}
    overrides)))
