(ns boundary.user.shell.adapters
  "SQLite database adapters implementing user module ports.

   This namespace provides concrete SQLite implementations of all user module ports:
   - IUserRepository: User data persistence with full CRUD and business queries
   - IUserSessionRepository: Session management with security tracking

   Key Features:
   - Leverages shared SQLite utilities from boundary.shell.adapters.database.sqlite
   - Tenant isolation enforcement at the database level
   - Comprehensive error handling and logging
   - Soft delete support with deleted_at timestamps
   - Batch operations with transaction safety
   - Connection pooling for performance

   Database Schema:
   - users table: Core user data with tenant isolation
   - user_sessions table: Authentication sessions with security metadata
   - Indexes optimized for common query patterns"
  (:require [boundary.shared.utils.type-conversion :as type-conversion]
            [clojure.tools.logging :as log]
            [clojure.set]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [boundary.user.ports :as ports]
            [boundary.user.schema :as schema]
            [boundary.shell.adapters.database.sqlite :as sqlite]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [next.jdbc.result-set :as rs])
  (:import [java.util UUID]))

;; =============================================================================
;; Database Schema Initialization
;; =============================================================================

(def ^:private user-table-ddl
  "CREATE TABLE IF NOT EXISTS users (
     id TEXT PRIMARY KEY,
     email TEXT NOT NULL,
     name TEXT NOT NULL,
     role TEXT NOT NULL CHECK(role IN ('admin', 'user', 'viewer')),
     active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0, 1)),
     tenant_id TEXT NOT NULL,
     created_at TEXT NOT NULL,
     updated_at TEXT,
     deleted_at TEXT,
     
     -- Constraints
     UNIQUE(email, tenant_id),
     
     -- Indexes for performance
     INDEX idx_users_tenant_id (tenant_id),
     INDEX idx_users_email_tenant (email, tenant_id),
     INDEX idx_users_role_tenant (role, tenant_id),
     INDEX idx_users_active_tenant (active, tenant_id),
     INDEX idx_users_created_at (created_at),
     INDEX idx_users_deleted_at (deleted_at)
   )")

(def ^:private user-sessions-table-ddl
  "CREATE TABLE IF NOT EXISTS user_sessions (
     id TEXT PRIMARY KEY,
     user_id TEXT NOT NULL,
     tenant_id TEXT NOT NULL,
     session_token TEXT NOT NULL UNIQUE,
     expires_at TEXT NOT NULL,
     created_at TEXT NOT NULL,
     user_agent TEXT,
     ip_address TEXT,
     last_accessed_at TEXT,
     revoked_at TEXT,
     
     -- Foreign key constraint
     FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
     
     -- Indexes for performance
     INDEX idx_sessions_token (session_token),
     INDEX idx_sessions_user_id (user_id),
     INDEX idx_sessions_tenant_id (tenant_id),
     INDEX idx_sessions_expires_at (expires_at),
     INDEX idx_sessions_revoked_at (revoked_at)
   )")

(defn initialize-database!
  "Initialize SQLite database with required tables and indexes."
  [datasource]
  (log/info "Initializing user module database schema")
  (try
    (jdbc/execute! datasource [user-table-ddl])
    (jdbc/execute! datasource [user-sessions-table-ddl])
    (log/info "Database schema initialized successfully")
    (catch Exception e
      (log/error "Failed to initialize database schema" {:error (.getMessage e)})
      (throw e))))

;; =============================================================================
;; Data Transformation Utilities (using shared SQLite utilities)
;; =============================================================================

;; Define ISO formatter for clj-time
(def ^:private iso-formatter (f/formatters :date-time))

;; Helper functions for clj-time <-> string
(defn- instant->string [inst]
  (when inst (f/unparse iso-formatter inst)))

(defn- string->instant [s]
  (when s (f/parse iso-formatter s)))

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
;; Query Execution Utilities
;; =============================================================================

(defn- execute-query!
  "Execute SELECT query with logging and error handling."
  [datasource query-map]
  (let [sql-query (sql/format query-map {:dialect :sqlite})
        start-time (System/currentTimeMillis)]
    (log/debug "Executing query" {:sql (first sql-query) :params (rest sql-query)})
    (try
      (let [result (jdbc/execute! datasource sql-query {:builder-fn rs/as-unqualified-lower-maps})
            duration (- (System/currentTimeMillis) start-time)]
        (log/debug "Query completed" {:duration-ms duration :row-count (count result)})
        result)
      (catch Exception e
        (log/error "Query failed" {:sql (first sql-query) :error (.getMessage e)})
        (throw e)))))

(defn- execute-one!
  "Execute query expecting single result."
  [datasource query-map]
  (first (execute-query! datasource query-map)))

(defn- execute-update!
  "Execute UPDATE/INSERT/DELETE query with affected row count."
  [datasource query-map]
  (let [sql-query (sql/format query-map {:dialect :sqlite})
        start-time (System/currentTimeMillis)]
    (log/debug "Executing update" {:sql (first sql-query) :params (rest sql-query)})
    (try
      (let [result (jdbc/execute! datasource sql-query)
            duration (- (System/currentTimeMillis) start-time)
            affected-rows (::jdbc/update-count (first result))]
        (log/debug "Update completed" {:duration-ms duration :affected-rows affected-rows})
        affected-rows)
      (catch Exception e
        (log/error "Update failed" {:sql (first sql-query) :error (.getMessage e)})
        (throw e)))))

;; =============================================================================
;; SQLite User Repository Implementation
;; =============================================================================

(defrecord SQLiteUserRepository [datasource]
  ports/IUserRepository

  ;; Basic CRUD Operations
  (find-user-by-id [_ user-id]
    (log/debug "Finding user by ID" {:user-id user-id})
    (let [query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:= :id (type-conversion/uuid->string user-id)]
                         [:is :deleted_at nil]]}
          result (execute-one! datasource query)]
      (db->user-entity result)))

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
    (let [now (t/now)
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
    (let [now (t/now)
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
    (let [now (t/now)
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
      (let [now (t/now)
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
      (let [now (t/now)
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
    (let [now (t/now)
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
    (let [now (t/now)
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
    (let [now (t/now)
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
    (let [now (t/now)
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
    (let [now (t/now)
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
