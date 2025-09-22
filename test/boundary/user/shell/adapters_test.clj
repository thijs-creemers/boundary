(ns boundary.user.shell.adapters-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [boundary.user.shell.adapters :as adapters]
    [boundary.user.ports :as ports]
    [java-time.api :as time])
  (:import [java.util UUID]))

;; =============================================================================
;; Test Fixtures and Mock Data
;; =============================================================================

(def test-tenant-id (UUID/fromString "123e4567-e89b-12d3-a456-426614174000"))
(def test-user-id (UUID/fromString "987fcdeb-51a2-43d7-b123-456789abcdef"))

(def sample-user
  {:id test-user-id
   :email "test@example.com"
   :name "Test User"
   :role :user
   :active true
   :tenant-id test-tenant-id
   :created-at (time/instant)})

(def sample-session
  {:id (UUID/randomUUID)
   :user-id test-user-id
   :tenant-id test-tenant-id
   :session-token "test-session-token-123"
   :expires-at (time/plus (time/instant) (time/hours 24))
   :created-at (time/instant)})

;; =============================================================================
;; Mock Database Connection
;; =============================================================================

(defrecord MockDatasource [queries results])

(defn- mock-execute!
  "Mock JDBC execute function that records queries and returns mock results."
  [datasource sql-or-query & args]
  (let [query (if (vector? sql-or-query) (first sql-or-query) sql-or-query)]
    (swap! (:queries datasource) conj {:query query :args args})
    (or (get @(:results datasource) query) [])))

(defn create-mock-datasource
  "Create mock datasource for testing."
  []
  (->MockDatasource (atom []) (atom {})))

(defn set-mock-result!
  "Set expected result for a specific query pattern."
  [datasource query-pattern result]
  (swap! (:results datasource) assoc query-pattern result))

(defn get-executed-queries
  "Get all queries executed on mock datasource."
  [datasource]
  @(:queries datasource))

;; =============================================================================
;; Data Transformation Tests
;; =============================================================================

(deftest user-entity-transformation-test
  (testing "user-entity->db transforms properly"
    ;; Since the actual transformation functions are private,
    ;; we test them through the repository operations
    (let [datasource (create-mock-datasource)
          repo (adapters/make-sqlite-user-repository datasource)]
      
      ;; Mock the database initialization
      (with-redefs [adapters/initialize-database! (fn [_] nil)
                    clojure.java.jdbc/execute! mock-execute!]
        
        ;; Test that create-user transforms the data correctly
        (set-mock-result! datasource :insert-into [{:id 1}])
        
        (testing "creates user with proper transformations"
          (let [user-input {:email "new@example.com"
                           :name "New User"
                           :role :user
                           :active true
                           :tenant-id test-tenant-id}
                created-user (ports/create-user repo user-input)]
            
            ;; Verify the user was created with generated fields
            (is (uuid? (:id created-user)))
            (is (inst? (:created-at created-user)))
            (is (= :user (:role created-user)))
            (is (= true (:active created-user)))
            (is (= test-tenant-id (:tenant-id created-user)))
            
            ;; Verify a query was executed
            (let [queries (get-executed-queries datasource)]
              (is (pos? (count queries)))))))))

  (testing "db->user-entity transforms properly"
    ;; Test the reverse transformation through find operations
    (let [datasource (create-mock-datasource)
          repo (adapters/make-sqlite-user-repository datasource)
          db-record {:id "987fcdeb-51a2-43d7-b123-456789abcdef"
                    :email "test@example.com"
                    :name "Test User"
                    :role "user"
                    :active 1
                    :tenant_id "123e4567-e89b-12d3-a456-426614174000"
                    :created_at "2023-01-01T12:00:00Z"
                    :updated_at nil
                    :deleted_at nil}]
      
      (with-redefs [adapters/initialize-database! (fn [_] nil)
                    clojure.java.jdbc/execute! mock-execute!]
        
        (set-mock-result! datasource :select [db-record])
        
        (testing "find-user-by-id transforms database record to entity"
          (let [found-user (ports/find-user-by-id repo test-user-id)]
            (is (= test-user-id (:id found-user)))
            (is (= "test@example.com" (:email found-user)))
            (is (= :user (:role found-user)))
            (is (= true (:active found-user)))
            (is (= test-tenant-id (:tenant-id found-user)))
            (is (inst? (:created-at found-user)))))))))

;; =============================================================================
;; SQLite User Repository Tests
;; =============================================================================

(deftest sqlite-user-repository-test
  (let [datasource (create-mock-datasource)]
    
    (with-redefs [adapters/initialize-database! (fn [_] nil)
                  clojure.java.jdbc/execute! mock-execute!]
      
      (testing "factory function creates repository"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (is (satisfies? ports/IUserRepository repo))
          (is (instance? boundary.user.shell.adapters.SQLiteUserRepository repo))))
      
      (testing "find-user-by-id generates correct SQL"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (set-mock-result! datasource :select [])
          
          (ports/find-user-by-id repo test-user-id)
          
          (let [queries (get-executed-queries datasource)]
            (is (pos? (count queries)))
            ;; The query should include the user ID and check for non-deleted users
            (let [last-query (:query (last queries))]
              (is (string? last-query))
              (is (.contains last-query "SELECT"))
              (is (.contains last-query "users"))))))
      
      (testing "find-user-by-email enforces tenant isolation"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (set-mock-result! datasource :select [])
          
          (ports/find-user-by-email repo "test@example.com" test-tenant-id)
          
          (let [queries (get-executed-queries datasource)]
            (is (pos? (count queries)))
            ;; Should query with both email and tenant_id
            (let [last-query (:query (last queries))]
              (is (.contains last-query "email"))
              (is (.contains last-query "tenant_id"))))))
      
      (testing "create-user inserts with generated metadata"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (set-mock-result! datasource :insert-into [{:update-count 1}])
          
          (let [user-input {:email "new@example.com"
                           :name "New User"
                           :role :user
                           :active true
                           :tenant-id test-tenant-id}
                created-user (ports/create-user repo user-input)]
            
            (is (uuid? (:id created-user)))
            (is (inst? (:created-at created-user)))
            (is (nil? (:updated-at created-user)))
            (is (nil? (:deleted-at created-user)))
            
            (let [queries (get-executed-queries datasource)]
              (is (pos? (count queries)))
              (let [last-query (:query (last queries))]
                (is (.contains last-query "INSERT")))))))
      
      (testing "update-user sets updated-at timestamp"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (set-mock-result! datasource :update [{:update-count 1}])
          
          (let [existing-user (assoc sample-user :name "Updated Name")
                updated-user (ports/update-user repo existing-user)]
            
            (is (= "Updated Name" (:name updated-user)))
            (is (inst? (:updated-at updated-user)))
            
            (let [queries (get-executed-queries datasource)]
              (is (pos? (count queries)))
              (let [last-query (:query (last queries))]
                (is (.contains last-query "UPDATE")))))))
      
      (testing "soft-delete-user sets deleted-at timestamp"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (set-mock-result! datasource :update [{:update-count 1}])
          
          (let [result (ports/soft-delete-user repo test-user-id)]
            (is (true? result))
            
            (let [queries (get-executed-queries datasource)]
              (is (pos? (count queries)))
              (let [last-query (:query (last queries))]
                (is (.contains last-query "UPDATE"))
                (is (.contains last-query "deleted_at")))))))
      
      (testing "hard-delete-user removes record permanently"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (set-mock-result! datasource :delete-from [{:update-count 1}])
          
          (let [result (ports/hard-delete-user repo test-user-id)]
            (is (true? result))
            
            (let [queries (get-executed-queries datasource)]
              (is (pos? (count queries)))
              (let [last-query (:query (last queries))]
                (is (.contains last-query "DELETE")))))))
      
      (testing "find-users-by-tenant supports pagination and filtering"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (set-mock-result! datasource :select [])
          (set-mock-result! datasource :count [{:count 0}])
          
          (let [options {:limit 10 :offset 20 :filter-role :admin}
                result (ports/find-users-by-tenant repo test-tenant-id options)]
            
            (is (vector? (:users result)))
            (is (number? (:total-count result)))
            
            (let [queries (get-executed-queries datasource)]
              (is (>= (count queries) 2)) ; Should have both data and count queries
              ))))
      
      (testing "batch operations use transactions"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (set-mock-result! datasource :insert-into [{:update-count 1}])
          
          ;; Mock transaction behavior
          (with-redefs [clojure.java.jdbc/with-transaction (fn [_ body] (body datasource))]
            (let [users [{:email "batch1@example.com" :name "Batch User 1" :role :user :active true :tenant-id test-tenant-id}
                        {:email "batch2@example.com" :name "Batch User 2" :role :user :active true :tenant-id test-tenant-id}]
                  created-users (ports/create-users-batch repo users)]
              
              (is (= 2 (count created-users)))
              (is (every? #(uuid? (:id %)) created-users))
              (is (every? #(inst? (:created-at %)) created-users)))))))))

;; =============================================================================
;; SQLite Session Repository Tests
;; =============================================================================

(deftest sqlite-session-repository-test
  (let [datasource (create-mock-datasource)]
    
    (with-redefs [adapters/initialize-database! (fn [_] nil)
                  clojure.java.jdbc/execute! mock-execute!]
      
      (testing "factory function creates session repository"
        (let [session-repo (adapters/make-sqlite-user-session-repository datasource)]
          (is (satisfies? ports/IUserSessionRepository session-repo))
          (is (instance? boundary.user.shell.adapters.SQLiteUserSessionRepository session-repo))))
      
      (testing "create-session generates secure token"
        (let [session-repo (adapters/make-sqlite-user-session-repository datasource)]
          (set-mock-result! datasource :insert-into [{:update-count 1}])
          
          (let [session-input {:user-id test-user-id
                              :tenant-id test-tenant-id
                              :expires-at (time/plus (time/instant) (time/hours 24))}
                created-session (ports/create-session session-repo session-input)]
            
            (is (uuid? (:id created-session)))
            (is (string? (:session-token created-session)))
            (is (> (count (:session-token created-session)) 20)) ; Should be substantial length
            (is (inst? (:created-at created-session)))
            (is (nil? (:revoked-at created-session))))))
      
      (testing "find-session-by-token checks expiration and updates access time"
        (let [session-repo (adapters/make-sqlite-user-session-repository datasource)
              valid-session {:id "session-id"
                           :user_id "user-id"
                           :tenant_id "tenant-id"
                           :session_token "valid-token"
                           :expires_at (str (time/plus (time/instant) (time/hours 1)))
                           :created_at (str (time/instant))
                           :revoked_at nil}]
          
          (set-mock-result! datasource :select [valid-session])
          (set-mock-result! datasource :update [{:update-count 1}])
          
          (let [found-session (ports/find-session-by-token session-repo "valid-token")]
            (is (some? found-session))
            (is (= "valid-token" (:session-token found-session)))
            
            ;; Should have executed both SELECT and UPDATE queries
            (let [queries (get-executed-queries datasource)]
              (is (>= (count queries) 2))))))
      
      (testing "invalidate-session sets revoked-at timestamp"
        (let [session-repo (adapters/make-sqlite-user-session-repository datasource)]
          (set-mock-result! datasource :update [{:update-count 1}])
          
          (let [result (ports/invalidate-session session-repo "session-to-revoke")]
            (is (true? result))
            
            (let [queries (get-executed-queries datasource)]
              (is (pos? (count queries)))
              (let [last-query (:query (last queries))]
                (is (.contains last-query "UPDATE"))
                (is (.contains last-query "revoked_at")))))))
      
      (testing "cleanup-expired-sessions removes old sessions"
        (let [session-repo (adapters/make-sqlite-user-session-repository datasource)]
          (set-mock-result! datasource :delete-from [{:update-count 5}])
          
          (let [cutoff-time (time/minus (time/instant) (time/days 30))
                cleaned-count (ports/cleanup-expired-sessions session-repo cutoff-time)]
            
            (is (= 5 cleaned-count))
            
            (let [queries (get-executed-queries datasource)]
              (is (pos? (count queries)))
              (let [last-query (:query (last queries))]
                (is (.contains last-query "DELETE"))
                (is (.contains last-query "expires_at"))))))))))

;; =============================================================================
;; Integration and Factory Function Tests
;; =============================================================================

(deftest factory-functions-test
  (let [datasource (create-mock-datasource)]
    
    (with-redefs [adapters/initialize-database! (fn [_] nil)]
      
      (testing "make-sqlite-repositories creates all repositories"
        (let [repositories (adapters/make-sqlite-repositories datasource)]
          (is (map? repositories))
          (is (contains? repositories :user-repository))
          (is (contains? repositories :session-repository))
          (is (satisfies? ports/IUserRepository (:user-repository repositories)))
          (is (satisfies? ports/IUserSessionRepository (:session-repository repositories)))))
      
      (testing "factory functions validate input"
        (is (thrown? AssertionError (adapters/make-sqlite-user-repository nil)))
        (is (thrown? AssertionError (adapters/make-sqlite-user-session-repository nil)))
        (is (thrown? AssertionError (adapters/make-sqlite-repositories nil)))))))

;; =============================================================================
;; Database Schema and Initialization Tests
;; =============================================================================

(deftest database-initialization-test
  (testing "initialize-database! executes DDL statements"
    (let [datasource (create-mock-datasource)
          executed-ddl (atom [])]
      
      (with-redefs [clojure.java.jdbc/execute! (fn [ds ddl]
                                                (swap! executed-ddl conj ddl)
                                                [])]
        
        (adapters/initialize-database! datasource)
        
        ;; Should execute DDL for both users and user_sessions tables
        (is (= 2 (count @executed-ddl)))
        (let [ddl-statements (map first @executed-ddl)]
          (is (some #(.contains % "CREATE TABLE") ddl-statements))
          (is (some #(.contains % "users") ddl-statements))
          (is (some #(.contains % "user_sessions") ddl-statements))))))
  
  (testing "database schema includes proper constraints"
    ;; Test that the DDL includes expected constraints and indexes
    (is (.contains @#'adapters/user-table-ddl "UNIQUE(email, tenant_id)"))
    (is (.contains @#'adapters/user-table-ddl "INDEX idx_users_tenant_id"))
    (is (.contains @#'adapters/user-sessions-table-ddl "FOREIGN KEY"))
    (is (.contains @#'adapters/user-sessions-table-ddl "INDEX idx_sessions_token"))))

;; =============================================================================
;; Error Handling and Edge Cases Tests
;; =============================================================================

(deftest error-handling-test
  (let [datasource (create-mock-datasource)]
    
    (with-redefs [adapters/initialize-database! (fn [_] nil)]
      
      (testing "update-user throws on user not found"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (with-redefs [clojure.java.jdbc/execute! (fn [_ _] [{:update-count 0}])]
            (is (thrown-with-msg? Exception #"User not found"
                                 (ports/update-user repo sample-user))))))
      
      (testing "soft-delete-user returns false for non-existent user"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (with-redefs [clojure.java.jdbc/execute! (fn [_ _] [{:update-count 0}])]
            (is (false? (ports/soft-delete-user repo test-user-id))))))
      
      (testing "hard-delete-user returns false for non-existent user"
        (let [repo (adapters/make-sqlite-user-repository datasource)]
          (with-redefs [clojure.java.jdbc/execute! (fn [_ _] [{:update-count 0}])]
            (is (false? (ports/hard-delete-user repo test-user-id))))))
      
      (testing "invalidate-session returns false for non-existent session"
        (let [session-repo (adapters/make-sqlite-user-session-repository datasource)]
          (with-redefs [clojure.java.jdbc/execute! (fn [_ _] [{:update-count 0}])]
            (is (false? (ports/invalidate-session session-repo "non-existent-token")))))))))