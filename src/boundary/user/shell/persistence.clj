(ns boundary.user.shell.persistence
  "Shell layer for user module - implements user ports using database storage.
   
   This namespace contains the SHELL (I/O) implementation that handles database persistence.
   In FC/IS architecture, this is the SHELL that:
   - Handles all I/O operations (database reads/writes)
   - Manages external system interactions
   - Contains side effects and impure operations
   - Implements boundary.user.ports interfaces
   - Transforms between domain entities and database records
   
   Key FC/IS principles:
   - ALL business logic stays in boundary.user.core.*
   - This is ONLY for I/O and persistence concerns
   - No business decisions made here - only data transformation
   - Shell coordinates with core for business logic
   
   This provides proper FC/IS separation where:
   - Core contains pure business logic (no I/O)
   - Shell handles all I/O and external systems
   - Clean boundary between functional and imperative code"
  (:require [boundary.shared.core.utils.type-conversion :as type-conversion]
            [boundary.shell.adapters.database.common.core :as db]
            [boundary.shell.adapters.database.protocols :as protocols]
            [boundary.shell.adapters.database.utils.schema :as db-schema]
            [boundary.user.ports :as ports]
            [boundary.user.schema :as user-schema]
            [boundary.error-reporting.core :as error-reporting]
            [clojure.set]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

;; =============================================================================
;; Schema Initialization
;; =============================================================================

(defn initialize-user-schema!
  "Initialize database schema for user entities using Malli schema definitions.
   
   Creates the following tables:
   - users: User accounts with tenant isolation
   - user_sessions: User authentication sessions
   
   Includes indexes for:
   - Foreign keys (tenant_id, user_id)
   - Unique constraints (email per tenant, session tokens)
   - Query performance (role, active status, expiration dates)
   
   Args:
     ctx: Database context
     
   Returns:
     nil
     
   Example:
     (initialize-user-schema! ctx)"
  [ctx]
  (log/info "Initializing user schema from Malli definitions")
  (db-schema/initialize-tables-from-schemas! ctx
                                             {"users" user-schema/User
                                              "user_sessions" user-schema/UserSession}))

;; =============================================================================
;; Entity Transformations
;; =============================================================================

(defn- user-entity->db
  "Transform user domain entity to database format using adapter-specific conversions."
  [ctx user-entity]
  (let [adapter (:adapter ctx)]
    (-> user-entity
        (update :id type-conversion/uuid->string)
        (update :role type-conversion/keyword->string)
        (update :active #(protocols/boolean->db adapter %))
        (update :tenant-id type-conversion/uuid->string)
        (update :created-at type-conversion/instant->string)
        (update :updated-at type-conversion/instant->string)
        (update :deleted-at type-conversion/instant->string)
        ;; Convert kebab-case to snake_case for database
        type-conversion/kebab-case->snake-case)))

(defn- db->user-entity
  "Transform database record to user domain entity."
  [ctx db-record]
  (when db-record
    (let [adapter (:adapter ctx)]
      (-> db-record
          ;; Convert snake_case to kebab-case
          (clojure.set/rename-keys {:tenant_id :tenant-id
                                    :created_at :created-at
                                    :updated_at :updated-at
                                    :deleted_at :deleted-at})
          (update :id type-conversion/string->uuid)
          (update :role type-conversion/string->keyword)
          (update :active #(protocols/db->boolean adapter %))
          (update :tenant-id type-conversion/string->uuid)
          (update :created-at type-conversion/string->instant)
          (update :updated-at type-conversion/string->instant)
          (update :deleted-at type-conversion/string->instant)))))

(defn- session-entity->db
  "Transform session domain entity to database format."
  [session-entity]
  (-> session-entity
      (update :id type-conversion/uuid->string)
      (update :user-id type-conversion/uuid->string)
      (update :tenant-id type-conversion/uuid->string)
      (update :expires-at type-conversion/instant->string)
      (update :created-at type-conversion/instant->string)
      (update :last-accessed-at type-conversion/instant->string)
      (update :revoked-at type-conversion/instant->string)
      ;; Convert kebab-case to snake_case
      (clojure.set/rename-keys {:user-id :user_id
                                :tenant-id :tenant_id
                                :session-token :session_token
                                :expires-at :expires_at
                                :created-at :created_at
                                :user-agent :user_agent
                                :ip-address :ip_address
                                :last-accessed-at :last_accessed_at
                                :revoked-at :revoked_at})))

(defn- db->session-entity
  "Transform database record to session domain entity."
  [db-record]
  (when db-record
    (-> db-record
        ;; Convert snake_case to kebab-case
        (clojure.set/rename-keys {:user_id :user-id
                                  :tenant_id :tenant-id
                                  :session_token :session-token
                                  :expires_at :expires-at
                                  :created_at :created-at
                                  :user_agent :user-agent
                                  :ip_address :ip-address
                                  :last_accessed_at :last-accessed-at
                                  :revoked_at :revoked-at})
        (update :id type-conversion/string->uuid)
        (update :user-id type-conversion/string->uuid)
        (update :tenant-id type-conversion/string->uuid)
        (update :expires-at type-conversion/string->instant)
        (update :created-at type-conversion/string->instant)
        (update :last-accessed-at type-conversion/string->instant)
        (update :revoked-at type-conversion/string->instant))))

(defn- add-persistence-breadcrumb
  "Add persistence operation breadcrumb for tracking database operations.
   
   Args:
     operation: String describing the database operation
     status: :start, :success, or :error
     details: Map with operation details"
  [operation status details]
  ;; Skip breadcrumb addition since persistence layer doesn't have error context
  ;; This prevents protocol errors when called from contexts without proper error reporting setup
  nil)

;; =============================================================================
;; User Repository Implementation
;; =============================================================================

(defrecord DatabaseUserRepository [ctx]
  ports/IUserRepository

  ;; Basic CRUD Operations
  (find-user-by-id [_ user-id]
    (log/debug "Finding user by ID" {:user-id user-id})
    (add-persistence-breadcrumb "find-user-by-id" :start
                                {:user-id user-id})
    (try
      (let [query {:select [:*]
                   :from [:users]
                   :where [:and
                           [:= :id (type-conversion/uuid->string user-id)]
                           [:is :deleted_at nil]]}
            result (db/execute-one! ctx query)
            user-entity (db->user-entity ctx result)]
        (if user-entity
          (add-persistence-breadcrumb "find-user-by-id" :success
                                      {:user-id user-id :found true})
          (add-persistence-breadcrumb "find-user-by-id" :success
                                      {:user-id user-id :found false}))
        user-entity)
      (catch Exception ex
        (add-persistence-breadcrumb "find-user-by-id" :error
                                    {:user-id user-id
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to find user by ID"
                                                  {:operation "find-user-by-id"
                                                   :user-id user-id})
        (throw ex))))

  (find-user-by-email [_ email tenant-id]
    (log/debug "Finding user by email" {:email email :tenant-id tenant-id})
    (add-persistence-breadcrumb "find-user-by-email" :start
                                {:email email :tenant-id tenant-id})
    (try
      (let [filters {:email email :tenant-id tenant-id :deleted_at nil}
            where-clause (db/build-where-clause ctx filters)
            query {:select [:*]
                   :from [:users]
                   :where where-clause}
            result (db/execute-one! ctx query)
            user-entity (db->user-entity ctx result)]
        (if user-entity
          (add-persistence-breadcrumb "find-user-by-email" :success
                                      {:email email :tenant-id tenant-id :found true})
          (add-persistence-breadcrumb "find-user-by-email" :success
                                      {:email email :tenant-id tenant-id :found false}))
        user-entity)
      (catch Exception ex
        (add-persistence-breadcrumb "find-user-by-email" :error
                                    {:email email :tenant-id tenant-id
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to find user by email"
                                                  {:operation "find-user-by-email"
                                                   :email email
                                                   :tenant-id tenant-id})
        (throw ex))))

  (find-users-by-tenant [_ tenant-id options]
    (log/debug "Finding users by tenant" {:tenant-id tenant-id :options options})
    (let [;; Build filters with kebab-case (query builder handles all conversions)
          base-filters {:tenant-id tenant-id}
          filters (cond-> base-filters
                    (not (:include-deleted? options)) (assoc :deleted-at nil)
                    (:filter-role options) (assoc :role (:filter-role options))
                    (contains? options :filter-active) (assoc :active (:filter-active options)))
          ;; Enhanced query builder handles: type conversion, case conversion, and boolean conversion
          where-base (db/build-where-clause ctx filters)
          ;; Handle email LIKE separately (special case not covered by standard filters)
          where-clause (if-let [pattern (:filter-email-contains options)]
                         (if where-base
                           [:and where-base [:like :email (str "%" pattern "%")]]
                           [:like :email (str "%" pattern "%")])
                         where-base)
          ;; Build pagination & ordering
          pagination (db/build-pagination options)
          ordering (db/build-ordering options :created_at)

          query {:select [:*]
                 :from [:users]
                 :where where-clause
                 :order-by ordering
                 :limit (:limit pagination)
                 :offset (:offset pagination)}

          ;; Count query with explicit alias to ensure consistent key across databases
          count-query {:select [[:%count.* :total]]
                       :from [:users]
                       :where where-clause}
          users (map #(db->user-entity ctx %) (db/execute-query! ctx query))
          ;; Extract count with defensive fallback to 0
          count-result (db/execute-one! ctx count-query)
          total-count (or (:total count-result)
                          (:count count-result)
                          (get count-result (keyword "COUNT(*)"))
                          0)]
      {:users users
       :total-count total-count}))

  (create-user [_ user-entity]
    (log/info "Creating user" {:email (:email user-entity) :tenant-id (:tenant-id user-entity)})
    (add-persistence-breadcrumb "create-user" :start
                                {:email (:email user-entity)
                                 :tenant-id (:tenant-id user-entity)})
    (try
      (let [now (java.time.Instant/now)
            user-with-metadata (-> user-entity
                                   (assoc :id (UUID/randomUUID))
                                   (assoc :created-at now)
                                   (assoc :updated-at nil)
                                   (assoc :deleted-at nil))
            db-user (user-entity->db ctx user-with-metadata)
            query {:insert-into :users
                   :values [db-user]}]

        (db/execute-update! ctx query)
        (add-persistence-breadcrumb "create-user" :success
                                    {:user-id (:id user-with-metadata)
                                     :email (:email user-entity)})
        (log/info "User created successfully" {:user-id (:id user-with-metadata)})
        user-with-metadata)
      (catch Exception ex
        (add-persistence-breadcrumb "create-user" :error
                                    {:email (:email user-entity)
                                     :tenant-id (:tenant-id user-entity)
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to create user in database"
                                                  {:operation "create-user"
                                                   :user-email (:email user-entity)
                                                   :tenant-id (:tenant-id user-entity)})
        (throw ex))))

  (update-user [_ user-entity]
    (log/info "Updating user" {:user-id (:id user-entity)})
    (add-persistence-breadcrumb "update-user" :start
                                {:user-id (:id user-entity)})
    (try
      (let [now (java.time.Instant/now)
            updated-user (assoc user-entity :updated-at now)
            db-user (user-entity->db ctx updated-user)
            query {:update :users
                   :set (dissoc db-user :id :created_at :deleted_at)
                   :where [:= :id (:id db-user)]}
            affected-rows (db/execute-update! ctx query)]

        (if (> affected-rows 0)
          (do
            (add-persistence-breadcrumb "update-user" :success
                                        {:user-id (:id user-entity)
                                         :affected-rows affected-rows})
            (log/info "User updated successfully" {:user-id (:id user-entity)})
            updated-user)
          (do
            (add-persistence-breadcrumb "update-user" :error
                                        {:user-id (:id user-entity)
                                         :reason "user-not-found"
                                         :affected-rows affected-rows})
            (throw (ex-info "User not found or update failed"
                            {:type :user-not-found
                             :user-id (:id user-entity)})))))
      (catch Exception ex
        (when-not (= (:type (ex-data ex)) :user-not-found)
          (add-persistence-breadcrumb "update-user" :error
                                      {:user-id (:id user-entity)
                                       :error-message (.getMessage ex)
                                       :error-type (or (:type (ex-data ex)) "database-error")})
          (error-reporting/report-application-error {} ex "Failed to update user in database"
                                                    {:operation "update-user"
                                                     :user-id (:id user-entity)}))
        (throw ex))))

  (soft-delete-user [_ user-id]
    (log/info "Soft deleting user" {:user-id user-id})
    (add-persistence-breadcrumb "soft-delete-user" :start
                                {:user-id user-id})
    (try
      (let [now (java.time.Instant/now)
            query {:update :users
                   :set {:deleted_at (type-conversion/instant->string now)
                         :updated_at (type-conversion/instant->string now)}
                   :where [:and
                           [:= :id (type-conversion/uuid->string user-id)]
                           [:is :deleted_at nil]]}
            affected-rows (db/execute-update! ctx query)]

        (if (> affected-rows 0)
          (do
            (add-persistence-breadcrumb "soft-delete-user" :success
                                        {:user-id user-id :affected-rows affected-rows})
            (log/info "User soft deleted successfully" {:user-id user-id})
            true)
          (do
            (add-persistence-breadcrumb "soft-delete-user" :success
                                        {:user-id user-id :found false :affected-rows affected-rows})
            (log/warn "User not found or already deleted" {:user-id user-id})
            false)))
      (catch Exception ex
        (add-persistence-breadcrumb "soft-delete-user" :error
                                    {:user-id user-id
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to soft delete user"
                                                  {:operation "soft-delete-user"
                                                   :user-id user-id})
        (throw ex))))

  (hard-delete-user [_ user-id]
    (log/warn "Hard deleting user - IRREVERSIBLE" {:user-id user-id})
    (add-persistence-breadcrumb "hard-delete-user" :start
                                {:user-id user-id})
    (try
      (let [query {:delete-from :users
                   :where [:= :id (type-conversion/uuid->string user-id)]}
            affected-rows (db/execute-update! ctx query)]

        (if (> affected-rows 0)
          (do
            (add-persistence-breadcrumb "hard-delete-user" :success
                                        {:user-id user-id :affected-rows affected-rows})
            (log/warn "User hard deleted successfully" {:user-id user-id})
            true)
          (do
            (add-persistence-breadcrumb "hard-delete-user" :success
                                        {:user-id user-id :found false :affected-rows affected-rows})
            (log/warn "User not found for hard deletion" {:user-id user-id})
            false)))
      (catch Exception ex
        (add-persistence-breadcrumb "hard-delete-user" :error
                                    {:user-id user-id
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to hard delete user"
                                                  {:operation "hard-delete-user"
                                                   :user-id user-id})
        (throw ex))))

  ;; Business-Specific Queries
  (find-active-users-by-role [_ tenant-id role]
    (log/debug "Finding active users by role" {:tenant-id tenant-id :role role})
    (let [adapter (:adapter ctx)
          query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                         [:= :role (type-conversion/keyword->string role)]
                         [:= :active (protocols/boolean->db adapter true)]
                         [:is :deleted_at nil]]
                 :order-by [[:created_at :desc]]}
          results (db/execute-query! ctx query)]
      (map #(db->user-entity ctx %) results)))

  (count-users-by-tenant [_ tenant-id]
    (log/debug "Counting users by tenant" {:tenant-id tenant-id})
    (let [query {:select [[:%count.* :total]]
                 :from [:users]
                 :where [:and
                         [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                         [:is :deleted_at nil]]}
          result (db/execute-one! ctx query)]
      (or (:total result)
          (:count result)
          (get result (keyword "COUNT(*)"))
          0)))

  (find-users-created-since [_ tenant-id since-date]
    (log/debug "Finding users created since" {:tenant-id tenant-id :since-date since-date})
    (let [query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                         [:>= :created_at (type-conversion/instant->string since-date)]
                         [:is :deleted_at nil]]
                 :order-by [[:created_at :desc]]}
          results (db/execute-query! ctx query)]
      (map #(db->user-entity ctx %) results)))

  (find-users-by-email-domain [_ tenant-id email-domain]
    (log/debug "Finding users by email domain" {:tenant-id tenant-id :email-domain email-domain})
    (let [query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                         [:like :email (str "%@" email-domain)]
                         [:is :deleted_at nil]]
                 :order-by [[:created_at :desc]]}
          results (db/execute-query! ctx query)]
      (map #(db->user-entity ctx %) results)))

  ;; Batch Operations
  (create-users-batch [_ user-entities]
    (log/info "Creating users batch" {:count (count user-entities)})
    (add-persistence-breadcrumb "create-users-batch" :start
                                {:count (count user-entities)})
    (try
      (db/with-transaction [tx ctx]
        (let [now (java.time.Instant/now)
              users-with-metadata (map (fn [user]
                                         (-> user
                                             (assoc :id (UUID/randomUUID))
                                             (assoc :created-at now)
                                             (assoc :updated-at nil)
                                             (assoc :deleted-at nil)))
                                       user-entities)
              db-users (map #(user-entity->db tx %) users-with-metadata)]

          (doseq [db-user db-users]
            (let [query {:insert-into :users
                         :values [db-user]}]
              (db/execute-update! tx query)))

          (add-persistence-breadcrumb "create-users-batch" :success
                                      {:count (count user-entities)})
          (log/info "Users batch created successfully" {:count (count user-entities)})
          users-with-metadata))
      (catch Exception ex
        (add-persistence-breadcrumb "create-users-batch" :error
                                    {:count (count user-entities)
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to create users batch"
                                                  {:operation "create-users-batch"
                                                   :count (count user-entities)})
        (throw ex))))

  (update-users-batch [_ user-entities]
    (log/info "Updating users batch" {:count (count user-entities)})
    (add-persistence-breadcrumb "update-users-batch" :start
                                {:count (count user-entities)})
    (try
      (db/with-transaction [tx ctx]
        (let [now (java.time.Instant/now)
              updated-users (map #(assoc % :updated-at now) user-entities)]

          (doseq [user updated-users]
            (let [db-user (user-entity->db tx user)
                  query {:update :users
                         :set (dissoc db-user :id :created_at :deleted_at)
                         :where [:= :id (:id db-user)]}
                  affected-rows (db/execute-update! tx query)]

              (when (= affected-rows 0)
                (throw (ex-info "User not found in batch update"
                                {:type :user-not-found
                                 :user-id (:id user)})))))

          (add-persistence-breadcrumb "update-users-batch" :success
                                      {:count (count user-entities)})
          (log/info "Users batch updated successfully" {:count (count user-entities)})
          updated-users))
      (catch Exception ex
        (add-persistence-breadcrumb "update-users-batch" :error
                                    {:count (count user-entities)
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to update users batch"
                                                  {:operation "update-users-batch"
                                                   :count (count user-entities)})
        (throw ex)))))

;; =============================================================================
;; Session Repository Implementation
;; =============================================================================

(defn- generate-session-token
  "Generate cryptographically secure session token."
  []
  (let [uuid1 (UUID/randomUUID)
        uuid2 (UUID/randomUUID)]
    (str (.toString uuid1) (.toString uuid2))))

(defrecord DatabaseUserSessionRepository [ctx]
  ports/IUserSessionRepository

  (create-session [_ session-entity]
    (log/info "Creating user session" {:user-id (:user-id session-entity)})
    (add-persistence-breadcrumb "create-session" :start
                                {:user-id (:user-id session-entity)})
    (try
      (let [now (java.time.Instant/now)
            session-with-metadata (-> session-entity
                                      (assoc :id (UUID/randomUUID))
                                      (assoc :session-token (generate-session-token))
                                      (assoc :created-at now)
                                      (assoc :last-accessed-at nil)
                                      (assoc :revoked-at nil))
            db-session (session-entity->db session-with-metadata)
            query {:insert-into :user_sessions
                   :values [db-session]}]

        (db/execute-update! ctx query)
        (add-persistence-breadcrumb "create-session" :success
                                    {:session-id (:id session-with-metadata)
                                     :user-id (:user-id session-entity)})
        (log/info "Session created successfully" {:session-id (:id session-with-metadata)})
        session-with-metadata)
      (catch Exception ex
        (add-persistence-breadcrumb "create-session" :error
                                    {:user-id (:user-id session-entity)
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to create user session"
                                                  {:operation "create-session"
                                                   :user-id (:user-id session-entity)})
        (throw ex))))

  (find-session-by-token [_ session-token]
    (log/debug "Finding session by token" {:token-prefix (subs session-token 0 8)})
    (add-persistence-breadcrumb "find-session-by-token" :start
                                {:token-prefix (subs session-token 0 8)})
    (try
      (let [now (java.time.Instant/now)
            query {:select [:*]
                   :from [:user_sessions]
                   :where [:and
                           [:= :session_token session-token]
                           [:> :expires_at (type-conversion/instant->string now)]
                           [:is :revoked_at nil]]}
            result (db/execute-one! ctx query)]
        (if result
          (do
            (let [update-query {:update :user_sessions
                                :set {:last_accessed_at (type-conversion/instant->string now)}
                                :where [:= :session_token session-token]}]
              (db/execute-update! ctx update-query))
            (log/debug "Session found and access timestamp updated")
            (add-persistence-breadcrumb "find-session-by-token" :success
                                        {:token-prefix (subs session-token 0 8) :found true})
            (-> result
                db->session-entity
                (assoc :last-accessed-at now)))
          (do
            (add-persistence-breadcrumb "find-session-by-token" :success
                                        {:token-prefix (subs session-token 0 8) :found false})
            nil)))
      (catch Exception ex
        (add-persistence-breadcrumb "find-session-by-token" :error
                                    {:token-prefix (subs session-token 0 8)
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to find session by token"
                                                  {:operation "find-session-by-token"
                                                   :token-prefix (subs session-token 0 8)})
        (throw ex))))

  (find-sessions-by-user [_ user-id]
    (log/debug "Finding sessions by user" {:user-id user-id})
    (add-persistence-breadcrumb "find-sessions-by-user" :start
                                {:user-id user-id})
    (try
      (let [now (java.time.Instant/now)
            query {:select [:*]
                   :from [:user_sessions]
                   :where [:and
                           [:= :user_id (type-conversion/uuid->string user-id)]
                           [:> :expires_at (type-conversion/instant->string now)]
                           [:is :revoked_at nil]]
                   :order-by [[:created_at :desc]]}
            results (db/execute-query! ctx query)
            sessions (map db->session-entity results)]
        (add-persistence-breadcrumb "find-sessions-by-user" :success
                                    {:user-id user-id :count (count sessions)})
        sessions)
      (catch Exception ex
        (add-persistence-breadcrumb "find-sessions-by-user" :error
                                    {:user-id user-id
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to find sessions by user"
                                                  {:operation "find-sessions-by-user"
                                                   :user-id user-id})
        (throw ex))))

  (invalidate-session [_ session-token]
    (log/info "Invalidating session" {:token-prefix (subs session-token 0 8)})
    (add-persistence-breadcrumb "invalidate-session" :start
                                {:token-prefix (subs session-token 0 8)})
    (try
      (let [now (java.time.Instant/now)
            query {:update :user_sessions
                   :set {:revoked_at (type-conversion/instant->string now)}
                   :where [:and
                           [:= :session_token session-token]
                           [:is :revoked_at nil]]}
            affected-rows (db/execute-update! ctx query)]

        (if (> affected-rows 0)
          (do
            (add-persistence-breadcrumb "invalidate-session" :success
                                        {:token-prefix (subs session-token 0 8) :affected-rows affected-rows})
            (log/info "Session invalidated successfully")
            true)
          (do
            (add-persistence-breadcrumb "invalidate-session" :success
                                        {:token-prefix (subs session-token 0 8) :found false :affected-rows affected-rows})
            (log/warn "Session not found or already invalidated")
            false)))
      (catch Exception ex
        (add-persistence-breadcrumb "invalidate-session" :error
                                    {:token-prefix (subs session-token 0 8)
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to invalidate session"
                                                  {:operation "invalidate-session"
                                                   :token-prefix (subs session-token 0 8)})
        (throw ex))))

  (invalidate-all-user-sessions [_ user-id]
    (log/warn "Invalidating all sessions for user" {:user-id user-id})
    (add-persistence-breadcrumb "invalidate-all-user-sessions" :start
                                {:user-id user-id})
    (try
      (let [now (java.time.Instant/now)
            query {:update :user_sessions
                   :set {:revoked_at (type-conversion/instant->string now)}
                   :where [:and
                           [:= :user_id (type-conversion/uuid->string user-id)]
                           [:is :revoked_at nil]]}
            affected-rows (db/execute-update! ctx query)]

        (add-persistence-breadcrumb "invalidate-all-user-sessions" :success
                                    {:user-id user-id :affected-rows affected-rows})
        (log/info "User sessions invalidated" {:user-id user-id :count affected-rows})
        affected-rows)
      (catch Exception ex
        (add-persistence-breadcrumb "invalidate-all-user-sessions" :error
                                    {:user-id user-id
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to invalidate all user sessions"
                                                  {:operation "invalidate-all-user-sessions"
                                                   :user-id user-id})
        (throw ex))))

  (cleanup-expired-sessions [_ before-timestamp]
    (log/info "Cleaning up expired sessions" {:before-timestamp before-timestamp})
    (add-persistence-breadcrumb "cleanup-expired-sessions" :start
                                {:before-timestamp before-timestamp})
    (try
      (let [query {:delete-from :user_sessions
                   :where [:< :expires_at (type-conversion/instant->string before-timestamp)]}
            affected-rows (db/execute-update! ctx query)]

        (add-persistence-breadcrumb "cleanup-expired-sessions" :success
                                    {:before-timestamp before-timestamp :affected-rows affected-rows})
        (log/info "Expired sessions cleaned up" {:count affected-rows})
        affected-rows)
      (catch Exception ex
        (add-persistence-breadcrumb "cleanup-expired-sessions" :error
                                    {:before-timestamp before-timestamp
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to cleanup expired sessions"
                                                  {:operation "cleanup-expired-sessions"
                                                   :before-timestamp before-timestamp})
        (throw ex))))

  (update-session [_ session-entity]
    (log/debug "Updating user session" {:session-id (:id session-entity)})
    (add-persistence-breadcrumb "update-session" :start
                                {:session-id (:id session-entity)})
    (try
      (let [db-session (session-entity->db session-entity)
            query {:update :user_sessions
                   :set (dissoc db-session :id)
                   :where [:= :id (type-conversion/uuid->string (:id session-entity))]}
            affected-rows (db/execute-update! ctx query)]

        (if (> affected-rows 0)
          (do
            (add-persistence-breadcrumb "update-session" :success
                                        {:session-id (:id session-entity) :affected-rows affected-rows})
            (log/debug "Session updated successfully")
            session-entity)
          (do
            (add-persistence-breadcrumb "update-session" :success
                                        {:session-id (:id session-entity) :found false :affected-rows affected-rows})
            (log/warn "Session not found for update")
            nil)))
      (catch Exception ex
        (add-persistence-breadcrumb "update-session" :error
                                    {:session-id (:id session-entity)
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to update session"
                                                  {:operation "update-session"
                                                   :session-id (:id session-entity)})
        (throw ex))))

  (find-all-sessions [_]
    (log/debug "Finding all sessions")
    (add-persistence-breadcrumb "find-all-sessions" :start {})
    (try
      (let [query {:select [:*]
                   :from [:user_sessions]
                   :order-by [[:created_at :desc]]}
            results (db/execute-query! ctx query)
            sessions (map db->session-entity results)]
        (add-persistence-breadcrumb "find-all-sessions" :success
                                    {:count (count sessions)})
        (log/debug "Found all sessions" {:count (count sessions)})
        sessions)
      (catch Exception ex
        (add-persistence-breadcrumb "find-all-sessions" :error
                                    {:error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to find all sessions"
                                                  {:operation "find-all-sessions"})
        (throw ex))))

  (delete-session [_ session-id]
    (log/warn "Permanently deleting session" {:session-id session-id})
    (add-persistence-breadcrumb "delete-session" :start
                                {:session-id session-id})
    (try
      (let [query {:delete-from :user_sessions
                   :where [:= :id (type-conversion/uuid->string session-id)]}
            affected-rows (db/execute-update! ctx query)]

        (if (> affected-rows 0)
          (do
            (add-persistence-breadcrumb "delete-session" :success
                                        {:session-id session-id :affected-rows affected-rows})
            (log/info "Session deleted successfully")
            true)
          (do
            (add-persistence-breadcrumb "delete-session" :success
                                        {:session-id session-id :found false :affected-rows affected-rows})
            (log/warn "Session not found for deletion")
            false)))
      (catch Exception ex
        (add-persistence-breadcrumb "delete-session" :error
                                    {:session-id session-id
                                     :error-message (.getMessage ex)
                                     :error-type (or (:type (ex-data ex)) "database-error")})
        (error-reporting/report-application-error {} ex "Failed to delete session"
                                                  {:operation "delete-session"
                                                   :session-id session-id})
        (throw ex)))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-user-repository
  "Create a user repository instance using database storage.
   
   Args:
     ctx: Database context from boundary.shell.adapters.database.factory
     
   Returns:
     DatabaseUserRepository implementing IUserRepository
     
   Example:
     (create-user-repository ctx)"
  [ctx]
  (->DatabaseUserRepository ctx))

(defn create-session-repository
  "Create a user session repository instance using database storage.
   
   Args:
     ctx: Database context from boundary.shell.adapters.database.factory
     
   Returns:
     DatabaseUserSessionRepository implementing IUserSessionRepository
     
   Example:
     (create-session-repository ctx)"
  [ctx]
  (->DatabaseUserSessionRepository ctx))
