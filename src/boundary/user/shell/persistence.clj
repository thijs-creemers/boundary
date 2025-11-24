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
            [boundary.shared.core.persistence-interceptors :as persistence-interceptors]
            [clojure.set]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

;; =============================================================================
;; Schema Initialization
;; =============================================================================

(defn initialize-user-schema!
  "Initialize database schema for user entities using Malli schema definitions.
   
   Creates the following tables:
   - users: User accounts
   - user_sessions: User authentication sessions
   
   Includes indexes for:
   - Foreign keys (user_id)
   - Unique constraints (email, session tokens)
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
          (clojure.set/rename-keys {:created_at :created-at
                                    :updated_at :updated-at
                                    :deleted_at :deleted-at})
          (update :id type-conversion/string->uuid)
          (update :role type-conversion/string->keyword)
          (update :active #(protocols/db->boolean adapter %))
          (update :created-at type-conversion/string->instant)
          (update :updated-at type-conversion/string->instant)
          (update :deleted-at type-conversion/string->instant)))))

(defn- session-entity->db
  "Transform session domain entity to database format."
  [session-entity]
  (-> session-entity
      (update :id type-conversion/uuid->string)
      (update :user-id type-conversion/uuid->string)
      (update :expires-at type-conversion/instant->string)
      (update :created-at type-conversion/instant->string)
      (update :last-accessed-at type-conversion/instant->string)
      (update :revoked-at type-conversion/instant->string)
      ;; Convert kebab-case to snake_case
      (clojure.set/rename-keys {:user-id :user_id
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
                                  :session_token :session-token
                                  :expires_at :expires-at
                                  :created_at :created-at
                                  :user_agent :user-agent
                                  :ip_address :ip-address
                                  :last_accessed_at :last-accessed-at
                                  :revoked_at :revoked-at})
        (update :id type-conversion/string->uuid)
        (update :user-id type-conversion/string->uuid)
        (update :expires-at type-conversion/string->instant)
        (update :created-at type-conversion/string->instant)
        (update :last-accessed-at type-conversion/string->instant)
        (update :revoked-at type-conversion/string->instant))))

;; =============================================================================
;; User Repository Implementation
;; =============================================================================

(defrecord DatabaseUserRepository [ctx]
  ports/IUserRepository

  ;; Basic CRUD Operations
  (find-user-by-id [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :find-user-by-id
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             query {:select [:*]
                    :from [:users]
                    :where [:and
                            [:= :id (type-conversion/uuid->string user-id)]
                            [:is :deleted_at nil]]}
             result (db/execute-one! ctx query)
             user-entity (db->user-entity ctx result)]
         user-entity))
     {:db-ctx ctx}))

  (find-user-by-email [_ email]
    (persistence-interceptors/execute-persistence-operation
     :find-user-by-email
     {:email email}
     (fn [{:keys [params]}]
       (let [email (:email params)
             filters {:email email :deleted_at nil}
             where-clause (db/build-where-clause ctx filters)
             query {:select [:*]
                    :from [:users]
                    :where where-clause}
             result (db/execute-one! ctx query)
             user-entity (db->user-entity ctx result)]
         user-entity))
     {:db-ctx ctx}))

  (find-users [_ options]
    (log/debug "Finding users" {:options options})
    (let [;; Build filters with kebab-case (query builder handles all conversions)
          base-filters {}
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

          ;; Build query - only include :where clause if we have a non-nil where-clause
          query (cond-> {:select [:*]
                         :from [:users]
                         :order-by ordering
                         :limit (:limit pagination)
                         :offset (:offset pagination)}
                  where-clause (assoc :where where-clause))

          ;; Count query - only include :where clause if we have a non-nil where-clause
          count-query (cond-> {:select [[:%count.* :total]]
                               :from [:users]}
                        where-clause (assoc :where where-clause))
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
    (persistence-interceptors/execute-persistence-operation
     :create-user
     {:user-entity user-entity}
     (fn [{:keys [params]}]
       (let [user-entity (:user-entity params)
             now (java.time.Instant/now)
             user-with-metadata (-> user-entity
                                    (assoc :id (UUID/randomUUID))
                                    (assoc :created-at now)
                                    (assoc :updated-at nil)
                                    (assoc :deleted-at nil))
             db-user (user-entity->db ctx user-with-metadata)
             query {:insert-into :users
                    :values [db-user]}]
         (db/execute-update! ctx query)
         user-with-metadata))
     {:db-ctx ctx}))

  (update-user [_ user-entity]
    (persistence-interceptors/execute-persistence-operation
     :update-user
     {:user-entity user-entity}
     (fn [{:keys [params]}]
       (let [user-entity (:user-entity params)
             ;; Use updated-at and deleted-at from user-entity if provided by service layer
             ;; This allows the service layer to control these fields based on business logic
             db-user (user-entity->db ctx user-entity)
             query {:update :users
                    :set (dissoc db-user :id :created_at)
                    :where [:= :id (:id db-user)]}
             affected-rows (db/execute-update! ctx query)]
         (if (> affected-rows 0)
           user-entity
           (throw (ex-info "User not found or update failed"
                           {:type :user-not-found
                            :user-id (:id user-entity)})))))
     {:db-ctx ctx}))

  (soft-delete-user [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :soft-delete-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now (java.time.Instant/now)
             query {:update :users
                    :set {:deleted_at (type-conversion/instant->string now)
                          :updated_at (type-conversion/instant->string now)}
                    :where [:and
                            [:= :id (type-conversion/uuid->string user-id)]
                            [:is :deleted_at nil]]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (hard-delete-user [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :hard-delete-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             query {:delete-from :users
                    :where [:= :id (type-conversion/uuid->string user-id)]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  ;; Business-Specific Queries
  (find-active-users-by-role [_ role]
    (log/debug "Finding active users by role" {:role role})
    (let [adapter (:adapter ctx)
          query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:= :role (type-conversion/keyword->string role)]
                         [:= :active (protocols/boolean->db adapter true)]
                         [:is :deleted_at nil]]
                 :order-by [[:created_at :desc]]}
          results (db/execute-query! ctx query)]
      (map #(db->user-entity ctx %) results)))

  (count-users [_]
    (log/debug "Counting users")
    (let [query {:select [[:%count.* :total]]
                 :from [:users]
                 :where [:is :deleted_at nil]}
          result (db/execute-one! ctx query)]
      (or (:total result)
          (:count result)
          (get result (keyword "COUNT(*)"))
          0)))

  (find-users-created-since [_ since-date]
    (log/debug "Finding users created since" {:since-date since-date})
    (let [query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:>= :created_at (type-conversion/instant->string since-date)]
                         [:is :deleted_at nil]]
                 :order-by [[:created_at :desc]]}
          results (db/execute-query! ctx query)]
      (map #(db->user-entity ctx %) results)))

  (find-users-by-email-domain [_ email-domain]
    (log/debug "Finding users by email domain" {:email-domain email-domain})
    (let [query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:like :email (str "%@" email-domain)]
                         [:is :deleted_at nil]]
                 :order-by [[:created_at :desc]]}
          results (db/execute-query! ctx query)]
      (map #(db->user-entity ctx %) results)))

  ;; Batch Operations
  (create-users-batch [_ user-entities]
    (persistence-interceptors/execute-persistence-operation
     :create-users-batch
     {:user-entities user-entities}
     (fn [{:keys [params]}]
       (let [user-entities (:user-entities params)]
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
             users-with-metadata))))
     {:db-ctx ctx}))

  (update-users-batch [_ user-entities]
    (persistence-interceptors/execute-persistence-operation
     :update-users-batch
     {:user-entities user-entities}
     (fn [{:keys [params]}]
       (let [user-entities (:user-entities params)]
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
             updated-users))))
     {:db-ctx ctx})))

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
    (persistence-interceptors/execute-persistence-operation
     :create-session
     {:session-entity session-entity}
     (fn [{:keys [params]}]
       (let [session-entity (:session-entity params)
             now (java.time.Instant/now)
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
         session-with-metadata))
     {:db-ctx ctx}))

  (find-session-by-token [_ session-token]
    (persistence-interceptors/execute-persistence-operation
     :find-session-by-token
     {:session-token session-token}
     (fn [{:keys [params]}]
       (let [session-token (:session-token params)
             now (java.time.Instant/now)
             query {:select [:*]
                    :from [:user_sessions]
                    :where [:and
                            [:= :session_token session-token]
                            [:> :expires_at (type-conversion/instant->string now)]
                            [:is :revoked_at nil]]}
             result (db/execute-one! ctx query)]
         (when result
           (let [update-query {:update :user_sessions
                               :set {:last_accessed_at (type-conversion/instant->string now)}
                               :where [:= :session_token session-token]}]
             (db/execute-update! ctx update-query))
           (-> result
               db->session-entity
               (assoc :last-accessed-at now)))))
     {:db-ctx ctx}))

  (find-sessions-by-user [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :find-sessions-by-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now (java.time.Instant/now)
             query {:select [:*]
                    :from [:user_sessions]
                    :where [:and
                            [:= :user_id (type-conversion/uuid->string user-id)]
                            [:> :expires_at (type-conversion/instant->string now)]
                            [:is :revoked_at nil]]
                    :order-by [[:created_at :desc]]}
             results (db/execute-query! ctx query)]
         (map db->session-entity results)))
     {:db-ctx ctx}))

  (invalidate-session [_ session-token]
    (persistence-interceptors/execute-persistence-operation
     :invalidate-session
     {:session-token session-token}
     (fn [{:keys [params]}]
       (let [session-token (:session-token params)
             now (java.time.Instant/now)
             query {:update :user_sessions
                    :set {:revoked_at (type-conversion/instant->string now)}
                    :where [:and
                            [:= :session_token session-token]
                            [:is :revoked_at nil]]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (invalidate-all-user-sessions [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :invalidate-all-user-sessions
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now (java.time.Instant/now)
             query {:update :user_sessions
                    :set {:revoked_at (type-conversion/instant->string now)}
                    :where [:and
                            [:= :user_id (type-conversion/uuid->string user-id)]
                            [:is :revoked_at nil]]}
             affected-rows (db/execute-update! ctx query)]
         affected-rows))
     {:db-ctx ctx}))

  (cleanup-expired-sessions [_ before-timestamp]
    (persistence-interceptors/execute-persistence-operation
     :cleanup-expired-sessions
     {:before-timestamp before-timestamp}
     (fn [{:keys [params]}]
       (let [before-timestamp (:before-timestamp params)
             query {:delete-from :user_sessions
                    :where [:< :expires_at (type-conversion/instant->string before-timestamp)]}
             affected-rows (db/execute-update! ctx query)]
         affected-rows))
     {:db-ctx ctx}))

  (update-session [_ session-entity]
    (persistence-interceptors/execute-persistence-operation
     :update-session
     {:session-entity session-entity}
     (fn [{:keys [params]}]
       (let [session-entity (:session-entity params)
             db-session (session-entity->db session-entity)
             query {:update :user_sessions
                    :set (dissoc db-session :id)
                    :where [:= :id (type-conversion/uuid->string (:id session-entity))]}
             affected-rows (db/execute-update! ctx query)]
         (when (> affected-rows 0)
           session-entity)))
     {:db-ctx ctx}))

  (find-all-sessions [_]
    (persistence-interceptors/execute-persistence-operation
     :find-all-sessions
     {}
     (fn [_]
       (let [query {:select [:*]
                    :from [:user_sessions]
                    :order-by [[:created_at :desc]]}
             results (db/execute-query! ctx query)]
         (map db->session-entity results)))
     {:db-ctx ctx}))

  (delete-session [_ session-id]
    (persistence-interceptors/execute-persistence-operation
     :delete-session
     {:session-id session-id}
     (fn [{:keys [params]}]
       (let [session-id (:session-id params)
             query {:delete-from :user_sessions
                    :where [:= :id (type-conversion/uuid->string session-id)]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx})))

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
