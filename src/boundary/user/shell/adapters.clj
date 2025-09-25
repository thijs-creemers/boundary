(ns boundary.user.shell.adapters
  "Multi-database user module adapters implementing user module ports.

   This namespace provides database-agnostic implementations of all user module ports:
   - IUserRepository: User data persistence with full CRUD and business queries
   - IUserSessionRepository: Session management with security tracking

   Key Features:
   - Database-agnostic using the new core database system
   - Supports SQLite, PostgreSQL, MySQL, and H2 databases
   - Tenant isolation enforcement at the database level
   - Comprehensive error handling and logging
   - Soft delete support with deleted_at timestamps
   - Batch operations with transaction safety
   - Cross-database compatible DDL and queries

   Database Schema:
   - users table: Core user data with tenant isolation
   - user_sessions table: Authentication sessions with security metadata
   - Indexes optimized for common query patterns
   
   Backward Compatibility:
   - SQLiteUserRepository and SQLiteUserSessionRepository remain available
   - New database-agnostic UserRepository and UserSessionRepository recommended"
  (:require
    [boundary.shared.utils.type-conversion :as type-conversion]
    [clojure.tools.logging :as log]
    [clojure.set]
    [boundary.user.ports :as ports]
    [boundary.shell.adapters.database.core :as db]
    [boundary.shell.adapters.database.sqlite :as sqlite]
    [boundary.shell.adapters.database.protocols :as protocols])
  (:import [java.util UUID]))

;; =============================================================================
;; Cross-Database Schema Initialization
;; =============================================================================

(defn- get-boolean-column-type
  "Get database-specific boolean column type."
  [ctx]
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)]
    (case dialect
      :sqlite "INTEGER CHECK(active IN (0, 1))"
      :mysql "TINYINT(1)"
      (:postgresql :h2) "BOOLEAN"
      "BOOLEAN"))) ; fallback

(defn- get-uuid-column-type
  "Get database-specific UUID column type."
  [ctx]
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)]
    (case dialect
      (:sqlite :mysql) "CHAR(36)"
      :postgresql "UUID"
      :h2 "UUID"
      "VARCHAR(36)"))) ; fallback

(defn- get-timestamp-column-type
  "Get database-specific timestamp column type."
  [ctx]
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)]
    (case dialect
      :sqlite "TEXT"
      :mysql "DATETIME"
      (:postgresql :h2) "TIMESTAMP"
      "TIMESTAMP"))) ; fallback

(defn- create-users-table-ddl
  "Generate cross-database users table DDL."
  [ctx]
  (let [uuid-type (get-uuid-column-type ctx)
        boolean-type (get-boolean-column-type ctx)
        timestamp-type (get-timestamp-column-type ctx)]
    (str "CREATE TABLE IF NOT EXISTS users (
       id " uuid-type " PRIMARY KEY,
       email VARCHAR(255) NOT NULL,
       name VARCHAR(255) NOT NULL,
       role VARCHAR(50) NOT NULL CHECK(role IN ('admin', 'user', 'viewer')),
       active " boolean-type " NOT NULL DEFAULT " (if (= (protocols/dialect (:adapter ctx)) :sqlite) "1" "true") ",
       tenant_id " uuid-type " NOT NULL,
       created_at " timestamp-type " NOT NULL,
       updated_at " timestamp-type ",
       deleted_at " timestamp-type ",
       CONSTRAINT uk_users_email_tenant UNIQUE(email, tenant_id)
     )")))

(defn- create-user-sessions-table-ddl
  "Generate cross-database user_sessions table DDL."
  [ctx]
  (let [uuid-type (get-uuid-column-type ctx)
        timestamp-type (get-timestamp-column-type ctx)]
    (str "CREATE TABLE IF NOT EXISTS user_sessions (
       id " uuid-type " PRIMARY KEY,
       user_id " uuid-type " NOT NULL,
       tenant_id " uuid-type " NOT NULL,
       session_token VARCHAR(255) NOT NULL UNIQUE,
       expires_at " timestamp-type " NOT NULL,
       created_at " timestamp-type " NOT NULL,
       user_agent TEXT,
       ip_address VARCHAR(45),
       last_accessed_at " timestamp-type ",
       revoked_at " timestamp-type ",
       CONSTRAINT fk_sessions_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
     )")))

(defn- create-indexes-ddl
  "Generate cross-database index creation statements."
  [ctx]
  ["CREATE INDEX IF NOT EXISTS idx_users_tenant_id ON users (tenant_id)"
   "CREATE INDEX IF NOT EXISTS idx_users_email_tenant ON users (email, tenant_id)"
   "CREATE INDEX IF NOT EXISTS idx_users_role_tenant ON users (role, tenant_id)"
   "CREATE INDEX IF NOT EXISTS idx_users_active_tenant ON users (active, tenant_id)"
   "CREATE INDEX IF NOT EXISTS idx_users_created_at ON users (created_at)"
   "CREATE INDEX IF NOT EXISTS idx_users_deleted_at ON users (deleted_at)"
   "CREATE INDEX IF NOT EXISTS idx_sessions_token ON user_sessions (session_token)"
   "CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON user_sessions (user_id)"
   "CREATE INDEX IF NOT EXISTS idx_sessions_tenant_id ON user_sessions (tenant_id)"
   "CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON user_sessions (expires_at)"
   "CREATE INDEX IF NOT EXISTS idx_sessions_revoked_at ON user_sessions (revoked_at)"])

(defn initialize-database!
  "Initialize database with required tables and indexes for any supported database.
   
   Args:
     ctx: Database context from factory/db-context
     
   Example:
     (def ctx (dbf/db-context {:adapter :postgresql :host 'localhost' ...}))
     (initialize-database! ctx)"
  [ctx]
  (log/info "Initializing user module database schema" {:dialect (protocols/dialect (:adapter ctx))})
  (try
    ;; Create tables
    (db/execute-ddl! ctx (create-users-table-ddl ctx))
    (db/execute-ddl! ctx (create-user-sessions-table-ddl ctx))
    
    ;; Create indexes separately for cross-database compatibility
    (doseq [index-ddl (create-indexes-ddl ctx)]
      (db/execute-ddl! ctx index-ddl))
    
    (log/info "Database schema initialized successfully")
    (catch Exception e
      (log/error "Failed to initialize database schema" {:error (.getMessage e)})
      (throw e))))

;; =============================================================================
;; Data Transformation Utilities (using shared SQLite utilities)
;; =============================================================================

;; Define ISO formatter for Java time
(def ^:private iso-formatter java.time.format.DateTimeFormatter/ISO_INSTANT)

;; Helper functions for Java time <-> string
(defn instant->string
  "Transform an Instant to a string using ISO format."
  [inst]
  (when inst (.format iso-formatter inst)))

(defn string->instant
  "Transform a string in ISO format to an Instant."
  [s]
  (when s (java.time.Instant/parse s)))

;; =============================================================================
;; User Entity Transformations
;; =============================================================================

(defn- user-entity->db
  "Transform user entity to database format using shared SQLite utilities."
  [user-entity]
  (-> user-entity
      (update :id type-conversion/uuid->string)
      (update :role type-conversion/keyword->string)
      (update :active type-conversion/boolean->int)
      (update :tenant-id type-conversion/uuid->string)
      (update :created-at instant->string)
      (update :updated-at instant->string)
      (update :deleted-at instant->string)
      ;; Convert kebab-case to snake_case for SQLite
      type-conversion/kebab-case->snake-case))

(defn- db->user-entity
  "Transform database record to user entity."
  [db-record]
  (when db-record
    (-> db-record
        ;; Convert snake_case to kebab-case
        (clojure.set/rename-keys {:tenant_id :tenant-id
                                  :created_at :created-at
                                  :updated_at :updated-at
                                  :deleted_at :deleted-at})
        (update :id type-conversion/string->uuid)
        (update :role type-conversion/string->keyword)
        (update :active type-conversion/int->boolean)
        (update :tenant-id type-conversion/string->uuid)
        (update :created-at string->instant)
        (update :updated-at string->instant)
        (update :deleted-at string->instant))))

;; =============================================================================
;; Session Entity Transformations
;; =============================================================================

(defn- session-entity->db
  "Transform session entity to database format."
  [session-entity]
  (-> session-entity
      (update :id type-conversion/uuid->string)
      (update :user-id type-conversion/uuid->string)
      (update :tenant-id type-conversion/uuid->string)
      (update :expires-at instant->string)
      (update :created-at instant->string)
      (update :last-accessed-at instant->string)
      (update :revoked-at instant->string)
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
  "Transform database record to session entity."
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
        (update :expires-at string->instant)
        (update :created-at string->instant)
        (update :last-accessed-at string->instant)
        (update :revoked-at string->instant))))

;; =============================================================================
;; Database Value Conversion Utilities
;; =============================================================================

(defn- convert-user-filters-to-db
  "Convert user filters to database-compatible format."
  [ctx filters]
  (let [adapter (:adapter ctx)]
    (reduce-kv (fn [acc k v]
                 (assoc acc k
                        (cond
                          (#{:tenant-id :id} k) (type-conversion/uuid->string v)
                          (= k :role) (type-conversion/keyword->string v)
                          (= k :active) (protocols/boolean->db adapter v)
                          :else v)))
               {}
               filters)))

;; =============================================================================
;; Database-Agnostic User Repository Implementation
;; =============================================================================

;; =============================================================================
;; Backward Compatibility - SQLite-Specific Repository Implementations
;; =============================================================================

(defrecord SQLiteUserRepository [datasource]
  ports/IUserRepository

  ;; Delegate all methods to the new multi-db implementation
  (find-user-by-id [_ user-id]
    (let [adapter (sqlite/new-adapter)
          ctx {:datasource datasource :adapter adapter}
          repo (boundary.user.shell.multi-db-adapters/new-user-repository ctx)]
      (.find-user-by-id repo user-id)))

  (find-user-by-email [_ email tenant-id]
    (log/debug "Finding user by email" {:email email :tenant-id tenant-id})
    (let [query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:= :email email]
                         [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                         [:is :deleted_at nil]]}
          result (execute-one! datasource query)]
      (db->user-entity result)))

  (find-users-by-tenant [_ tenant-id options]
    (log/debug "Finding users by tenant" {:tenant-id tenant-id :options options})
    (let [limit (get options :limit 20)
          offset (get options :offset 0)
          sort-by (get options :sort-by :created_at)
          sort-direction (get options :sort-direction :desc)
          include-deleted? (get options :include-deleted? false)

          base-where [:and
                      [:= :tenant_id (type-conversion/uuid->string tenant-id)]]

          where-clause (cond-> base-where
                         (not include-deleted?) (conj [:is :deleted_at nil])
                         (:filter-role options) (conj [:= :role (type-conversion/keyword->string (:filter-role options))])
                         (:filter-active options) (conj [:= :active (type-conversion/boolean->int (:filter-active options))])
                         (:filter-email-contains options) (conj [:like :email (str "%" (:filter-email-contains options) "%")]))

          query {:select [:*]
                 :from [:users]
                 :where where-clause
                 :order-by [[sort-by sort-direction]]
                 :limit limit
                 :offset offset}

          count-query {:select [:%count.*]
                       :from [:users]
                       :where where-clause}

          users (map db->user-entity (execute-query! datasource query))
          total-count (:count (execute-one! datasource count-query))]

      {:users users
       :total-count total-count}))

  (create-user [_ user-entity]
    (log/info "Creating user" {:email (:email user-entity) :tenant-id (:tenant-id user-entity)})
    (let [now (java.time.Instant/now)
          user-with-metadata (-> user-entity
                                 (assoc :id (UUID/randomUUID))
                                 (assoc :created-at now)
                                 (assoc :updated-at nil)
                                 (assoc :deleted-at nil))
          db-user (user-entity->db user-with-metadata)
          query {:insert-into :users
                 :values [db-user]}]

      (execute-update! datasource query)
      (log/info "User created successfully" {:user-id (:id user-with-metadata)})
      user-with-metadata))

  (update-user [_ user-entity]
    (log/info "Updating user" {:user-id (:id user-entity)})
    (let [now (java.time.Instant/now)
          updated-user (assoc user-entity :updated-at now)
          db-user (user-entity->db updated-user)
          query {:update :users
                 :set (dissoc db-user :id :created_at :deleted_at)
                 :where [:= :id (:id db-user)]}
          affected-rows (execute-update! datasource query)]

      (if (> affected-rows 0)
        (do
          (log/info "User updated successfully" {:user-id (:id user-entity)})
          updated-user)
        (throw (ex-info "User not found or update failed"
                        {:type :user-not-found
                         :user-id (:id user-entity)})))))

  (soft-delete-user [_ user-id]
    (log/info "Soft deleting user" {:user-id user-id})
    (let [now (java.time.Instant/now)
          query {:update :users
                 :set {:deleted_at (instant->string now)
                       :updated_at (instant->string now)}
                 :where [:and
                         [:= :id (type-conversion/uuid->string user-id)]
                         [:is :deleted_at nil]]}
          affected-rows (execute-update! datasource query)]

      (if (> affected-rows 0)
        (do
          (log/info "User soft deleted successfully" {:user-id user-id})
          true)
        (do
          (log/warn "User not found or already deleted" {:user-id user-id})
          false))))

  (hard-delete-user [_ user-id]
    (log/warn "Hard deleting user - IRREVERSIBLE" {:user-id user-id})
    (let [query {:delete-from :users
                 :where [:= :id (type-conversion/uuid->string user-id)]}
          affected-rows (execute-update! datasource query)]

      (if (> affected-rows 0)
        (do
          (log/warn "User hard deleted successfully" {:user-id user-id})
          true)
        (do
          (log/warn "User not found for hard deletion" {:user-id user-id})
          false))))

  ;; Business-Specific Queries
  (find-active-users-by-role [_ tenant-id role]
    (log/debug "Finding active users by role" {:tenant-id tenant-id :role role})
    (let [query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                         [:= :role (type-conversion/keyword->string role)]
                         [:= :active 1]
                         [:is :deleted_at nil]]
                 :order-by [[:created_at :desc]]}
          results (execute-query! datasource query)]
      (map db->user-entity results)))

  (count-users-by-tenant [_ tenant-id]
    (log/debug "Counting users by tenant" {:tenant-id tenant-id})
    (let [query {:select [:%count.*]
                 :from [:users]
                 :where [:and
                         [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                         [:is :deleted_at nil]]}
          result (execute-one! datasource query)]
      (:count result)))

  (find-users-created-since [_ tenant-id since-date]
    (log/debug "Finding users created since" {:tenant-id tenant-id :since-date since-date})
    (let [query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                         [:>= :created_at (instant->string since-date)]
                         [:is :deleted_at nil]]
                 :order-by [[:created_at :desc]]}
          results (execute-query! datasource query)]
      (map db->user-entity results)))

  (find-users-by-email-domain [_ tenant-id email-domain]
    (log/debug "Finding users by email domain" {:tenant-id tenant-id :email-domain email-domain})
    (let [query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                         [:like :email (str "%@" email-domain)]
                         [:is :deleted_at nil]]
                 :order-by [[:created_at :desc]]}
          results (execute-query! datasource query)]
      (map db->user-entity results)))

  ;; Batch Operations
  (create-users-batch [_ user-entities]
    (log/info "Creating users batch" {:count (count user-entities)})
    (jdbc/with-transaction [tx datasource]
      (let [now (java.time.Instant/now)
            users-with-metadata (map (fn [user]
                                       (-> user
                                           (assoc :id (UUID/randomUUID))
                                           (assoc :created-at now)
                                           (assoc :updated-at nil)
                                           (assoc :deleted-at nil)))
                                     user-entities)
            db-users (map user-entity->db users-with-metadata)]

        (doseq [db-user db-users]
          (let [query {:insert-into :users
                       :values [db-user]}]
            (execute-update! tx query)))

        (log/info "Users batch created successfully" {:count (count user-entities)})
        users-with-metadata)))

  (update-users-batch [_ user-entities]
    (log/info "Updating users batch" {:count (count user-entities)})
    (jdbc/with-transaction [tx datasource]
      (let [now (java.time.Instant/now)
            updated-users (map #(assoc % :updated-at now) user-entities)]

        (doseq [user updated-users]
          (let [db-user (user-entity->db user)
                query {:update :users
                       :set (dissoc db-user :id :created_at :deleted_at)
                       :where [:= :id (:id db-user)]}
                affected-rows (execute-update! tx query)]

            (when (= affected-rows 0)
              (throw (ex-info "User not found in batch update"
                              {:type :user-not-found
                               :user-id (:id user)})))))

        (log/info "Users batch updated successfully" {:count (count user-entities)})
        updated-users))))

;; =============================================================================
;; SQLite Session Repository Implementation  
;; =============================================================================

(defn- generate-session-token
  "Generate cryptographically secure session token."
  []
  (let [uuid1 (UUID/randomUUID)
        uuid2 (UUID/randomUUID)]
    (str (.toString uuid1) (.toString uuid2))))

(defrecord SQLiteUserSessionRepository [datasource]
  ports/IUserSessionRepository

  (create-session [_ session-entity]
    (log/info "Creating user session" {:user-id (:user-id session-entity)})
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

      (execute-update! datasource query)
      (log/info "Session created successfully" {:session-id (:id session-with-metadata)})
      session-with-metadata))

  (find-session-by-token [_ session-token]
    (log/debug "Finding session by token" {:token-prefix (subs session-token 0 8)})
    (let [now (java.time.Instant/now)
          query {:select [:*]
                 :from [:user_sessions]
                 :where [:and
                         [:= :session_token session-token]
                         [:> :expires_at (instant->string now)]
                         [:is :revoked_at nil]]}
          result (execute-one! datasource query)]
      (when result
        (let [update-query {:update :user_sessions
                            :set {:last_accessed_at (instant->string now)}
                            :where [:= :session_token session-token]}]
          (execute-update! datasource update-query))
        (log/debug "Session found and access timestamp updated")
        (-> result
            db->session-entity
            (assoc :last-accessed-at now)))))

  (find-sessions-by-user [_ user-id]
    (log/debug "Finding sessions by user" {:user-id user-id})
    (let [now (java.time.Instant/now)
          query {:select [:*]
                 :from [:user_sessions]
                 :where [:and
                         [:= :user_id (type-conversion/uuid->string user-id)]
                         [:> :expires_at (instant->string now)]
                         [:is :revoked_at nil]]
                 :order-by [[:created_at :desc]]}
          results (execute-query! datasource query)]
      (map db->session-entity results)))

  (invalidate-session [_ session-token]
    (log/info "Invalidating session" {:token-prefix (subs session-token 0 8)})
    (let [now (java.time.Instant/now)
          query {:update :user_sessions
                 :set {:revoked_at (instant->string now)}
                 :where [:and
                         [:= :session_token session-token]
                         [:is :revoked_at nil]]}
          affected-rows (execute-update! datasource query)]

      (if (> affected-rows 0)
        (do
          (log/info "Session invalidated successfully")
          true)
        (do
          (log/warn "Session not found or already invalidated")
          false))))

  (invalidate-all-user-sessions [_ user-id]
    (log/warn "Invalidating all sessions for user" {:user-id user-id})
    (let [now (java.time.Instant/now)
          query {:update :user_sessions
                 :set {:revoked_at (instant->string now)}
                 :where [:and
                         [:= :user_id (type-conversion/uuid->string user-id)]
                         [:is :revoked_at nil]]}
          affected-rows (execute-update! datasource query)]

      (log/info "User sessions invalidated" {:user-id user-id :count affected-rows})
      affected-rows))

  (cleanup-expired-sessions [_ before-timestamp]
    (log/info "Cleaning up expired sessions" {:before-timestamp before-timestamp})
    (let [query {:delete-from :user_sessions
                 :where [:< :expires_at (instant->string before-timestamp)]}
          affected-rows (execute-update! datasource query)]

      (log/info "Expired sessions cleaned up" {:count affected-rows})
      affected-rows)))
