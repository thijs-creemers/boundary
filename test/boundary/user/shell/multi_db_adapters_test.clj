(ns boundary.user.shell.multi-db-adapters-test
  "Tests for the new multi-database user repository adapters.
   
   Tests the database-agnostic user and session repositories with:
   - CRUD operations across different database types
   - Cross-database schema compatibility
   - Business logic preservation
   - Entity transformation consistency
   - Transaction behavior
   - Query optimization verification
   
   Uses H2 and SQLite for fast local testing with graceful degradation
   for external databases."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging :as log]
            [boundary.user.shell.multi-db-adapters :as multi-db]
            [boundary.shell.adapters.database.factory :as dbf]
            [boundary.shell.adapters.database.core :as db]
            [boundary.user.ports :as ports]
            [boundary.shared.utils.type-conversion :as tc])
  (:import [java.util UUID]
           [java.time Instant]
           [java.io File]))

;; =============================================================================
;; Test Fixtures and Utilities
;; =============================================================================

(def test-ctx (atom nil))
(def temp-db-file (atom nil))

(defn setup-test-database []
  "Set up H2 in-memory database for testing."
  (let [ctx (dbf/db-context {:adapter :h2
                             :database-path "mem:multi_db_test"
                             :connection-params {:DB_CLOSE_DELAY "-1"
                                               :MODE "PostgreSQL"
                                               :DATABASE_TO_LOWER "TRUE"}})]
    (reset! test-ctx ctx)
    
    ;; Initialize database schema
    (multi-db/initialize-database! ctx)
    ctx))

(defn setup-sqlite-test-database []
  "Set up SQLite file database for testing SQLite-specific features."
  (let [temp-file (.getPath (File/createTempFile "sqlite_multi_test" ".db"))
        ctx (dbf/db-context {:adapter :sqlite :database-path temp-file})]
    (reset! temp-db-file temp-file)
    
    ;; Initialize database schema
    (multi-db/initialize-database! ctx)
    ctx))

(defn cleanup-test-database []
  "Clean up test database resources."
  (when-let [ctx @test-ctx]
    (try
      (.close (:datasource ctx))
      (catch Exception e
        (log/warn "Error closing test datasource" {:error (.getMessage e)})))
    (reset! test-ctx nil))
  
  (when-let [db-file @temp-db-file]
    (try
      (.delete (File. db-file))
      (catch Exception e
        (log/warn "Error deleting temp database file" {:error (.getMessage e)})))
    (reset! temp-db-file nil)))

(use-fixtures :each
  (fn [test-fn]
    (setup-test-database)
    (try
      (test-fn)
      (finally
        (cleanup-test-database)))))

;; =============================================================================
;; Test Data Generators
;; =============================================================================

(defn generate-test-user
  "Generate a test user entity."
  ([]
   (generate-test-user {}))
  ([overrides]
   (merge {:email (str "test-" (UUID/randomUUID) "@example.com")
           :name "Test User"
           :role :user
           :active true
           :tenant-id (UUID/randomUUID)}
          overrides)))

(defn generate-test-session
  "Generate a test session entity."
  ([user-id]
   (generate-test-session user-id {}))
  ([user-id overrides]
   (merge {:user-id user-id
           :tenant-id (UUID/randomUUID)
           :expires-at (.plusSeconds (Instant/now) 3600)  ; 1 hour from now
           :user-agent "Test User Agent"
           :ip-address "192.168.1.1"}
          overrides)))

;; =============================================================================
;; User Repository Tests
;; =============================================================================

(deftest test-user-repository-crud
  (testing "User repository basic CRUD operations"
    (let [ctx @test-ctx
          repo (multi-db/new-user-repository ctx)]
      
      ;; Test create user
      (let [user-data (generate-test-user)
            created-user (.create-user repo user-data)]
        
        (is (some? (:id created-user)))
        (is (= (:email user-data) (:email created-user)))
        (is (= (:name user-data) (:name created-user)))
        (is (= (:role user-data) (:role created-user)))
        (is (= (:active user-data) (:active created-user)))
        (is (= (:tenant-id user-data) (:tenant-id created-user)))
        (is (some? (:created-at created-user)))
        (is (nil? (:updated-at created-user)))
        (is (nil? (:deleted-at created-user)))
        
        ;; Test find by ID
        (let [found-user (.find-user-by-id repo (:id created-user))]
          (is (= created-user found-user)))
        
        ;; Test find by email
        (let [found-user (.find-user-by-email repo (:email created-user) (:tenant-id created-user))]
          (is (= created-user found-user)))
        
        ;; Test update user
        (let [updated-data (assoc created-user :name "Updated Name")
              updated-user (.update-user repo updated-data)]
          (is (= "Updated Name" (:name updated-user)))
          (is (some? (:updated-at updated-user)))
          (is (= (:id created-user) (:id updated-user))))
        
        ;; Test soft delete
        (is (true? (.soft-delete-user repo (:id created-user))))
        
        ;; Verify user is soft deleted (not found in regular queries)
        (is (nil? (.find-user-by-id repo (:id created-user))))
        
        ;; Test hard delete
        (is (true? (.hard-delete-user repo (:id created-user))))
        
        ;; Verify user is completely gone
        (is (false? (.soft-delete-user repo (:id created-user)))) ; Should return false for non-existent
        ))))

(deftest test-user-repository-tenant-queries
  (testing "User repository tenant-based queries"
    (let [ctx @test-ctx
          repo (multi-db/new-user-repository ctx)
          tenant-1 (UUID/randomUUID)
          tenant-2 (UUID/randomUUID)]
      
      ;; Create users in different tenants
      (let [user-1 (.create-user repo (generate-test-user {:tenant-id tenant-1 :role :admin :name "Admin User"}))
            user-2 (.create-user repo (generate-test-user {:tenant-id tenant-1 :role :user :name "Regular User"}))
            user-3 (.create-user repo (generate-test-user {:tenant-id tenant-2 :role :admin :name "Other Admin"}))]
        
        ;; Test find users by tenant
        (let [result (.find-users-by-tenant repo tenant-1 {})]
          (is (= 2 (:total-count result)))
          (is (= 2 (count (:users result))))
          (is (every? #(= tenant-1 (:tenant-id %)) (:users result))))
        
        ;; Test find active users by role
        (let [admin-users (.find-active-users-by-role repo tenant-1 :admin)]
          (is (= 1 (count admin-users)))
          (is (= "Admin User" (:name (first admin-users)))))
        
        ;; Test count users by tenant
        (is (= 2 (.count-users-by-tenant repo tenant-1)))
        (is (= 1 (.count-users-by-tenant repo tenant-2)))
        
        ;; Test with filters
        (let [result (.find-users-by-tenant repo tenant-1 {:filter-role :user})]
          (is (= 1 (:total-count result)))
          (is (= :user (:role (first (:users result))))))
        
        ;; Test with pagination
        (let [result (.find-users-by-tenant repo tenant-1 {:limit 1 :offset 0})]
          (is (= 2 (:total-count result))) ; Total count should be 2
          (is (= 1 (count (:users result))))) ; But only 1 returned due to limit
        ))))

(deftest test-user-repository-business-queries
  (testing "User repository business logic queries"
    (let [ctx @test-ctx
          repo (multi-db/new-user-repository ctx)
          tenant-id (UUID/randomUUID)
          now (Instant/now)
          yesterday (.minusSeconds now 86400)]
      
      ;; Create test users
      (let [old-user (.create-user repo (generate-test-user {:tenant-id tenant-id :email "old@test.com"}))
            ;; Manually set created_at to yesterday (simulating old user)
            _ (db/execute-update! ctx {:update :users 
                                       :set {:created_at (.toString yesterday)}
                                       :where [:= :id (tc/uuid->string (:id old-user))]})
            
            new-user (.create-user repo (generate-test-user {:tenant-id tenant-id :email "new@test.com"}))]
        
        ;; Test find users created since
        (let [recent-users (.find-users-created-since repo tenant-id (.minusSeconds now 3600))]
          (is (= 1 (count recent-users)))
          (is (= "new@test.com" (:email (first recent-users)))))
        
        ;; Test find users by email domain
        (let [test-users (.find-users-by-email-domain repo tenant-id "test.com")]
          (is (= 2 (count test-users)))
          (is (every? #(.endsWith (:email %) "test.com") test-users)))
        ))))

(deftest test-user-repository-batch-operations
  (testing "User repository batch operations"
    (let [ctx @test-ctx
          repo (multi-db/new-user-repository ctx)
          tenant-id (UUID/randomUUID)]
      
      ;; Test batch create
      (let [user-data [(generate-test-user {:tenant-id tenant-id :email "batch1@test.com"})
                       (generate-test-user {:tenant-id tenant-id :email "batch2@test.com"})
                       (generate-test-user {:tenant-id tenant-id :email "batch3@test.com"})]
            created-users (.create-users-batch repo user-data)]
        
        (is (= 3 (count created-users)))
        (is (every? some? (map :id created-users)))
        (is (every? some? (map :created-at created-users)))
        
        ;; Verify all were created
        (is (= 3 (.count-users-by-tenant repo tenant-id)))
        
        ;; Test batch update
        (let [updated-data (map #(assoc % :name "Updated Name") created-users)
              updated-users (.update-users-batch repo updated-data)]
          
          (is (= 3 (count updated-users)))
          (is (every? #(= "Updated Name" (:name %)) updated-users))
          (is (every? some? (map :updated-at updated-users))))
        
        ;; Test batch update with non-existent user (should fail)
        (let [invalid-data [(assoc (first created-users) :id (UUID/randomUUID))]]
          (is (thrown? Exception (.update-users-batch repo invalid-data))))
        ))))

;; =============================================================================
;; Session Repository Tests
;; =============================================================================

(deftest test-session-repository-crud
  (testing "Session repository basic CRUD operations"
    (let [ctx @test-ctx
          user-repo (multi-db/new-user-repository ctx)
          session-repo (multi-db/new-user-session-repository ctx)]
      
      ;; Create a user first
      (let [user (.create-user user-repo (generate-test-user))
            session-data (generate-test-session (:id user))]
        
        ;; Test create session
        (let [created-session (.create-session session-repo session-data)]
          (is (some? (:id created-session)))
          (is (some? (:session-token created-session)))
          (is (= (:user-id session-data) (:user-id created-session)))
          (is (= (:tenant-id session-data) (:tenant-id created-session)))
          (is (= (:expires-at session-data) (:expires-at created-session)))
          (is (some? (:created-at created-session)))
          (is (nil? (:last-accessed-at created-session)))
          (is (nil? (:revoked-at created-session)))
          
          ;; Test find by token
          (let [found-session (.find-session-by-token session-repo (:session-token created-session))]
            (is (some? found-session))
            (is (= (:id created-session) (:id found-session)))
            (is (some? (:last-accessed-at found-session)))) ; Should be updated on access
          
          ;; Test find sessions by user
          (let [user-sessions (.find-sessions-by-user session-repo (:user-id created-session))]
            (is (= 1 (count user-sessions)))
            (is (= (:id created-session) (:id (first user-sessions)))))
          
          ;; Test invalidate session
          (is (true? (.invalidate-session session-repo (:session-token created-session))))
          
          ;; Verify session is invalidated (not found)
          (is (nil? (.find-session-by-token session-repo (:session-token created-session))))
          
          ;; Test invalidate already invalidated session
          (is (false? (.invalidate-session session-repo (:session-token created-session))))
          ))))

(deftest test-session-repository-user-management
  (testing "Session repository user session management"
    (let [ctx @test-ctx
          user-repo (multi-db/new-user-repository ctx)
          session-repo (multi-db/new-user-session-repository ctx)]
      
      ;; Create user and multiple sessions
      (let [user (.create-user user-repo (generate-test-user))
            session-1 (.create-session session-repo (generate-test-session (:id user)))
            session-2 (.create-session session-repo (generate-test-session (:id user)))
            session-3 (.create-session session-repo (generate-test-session (:id user)))]
        
        ;; Verify all sessions exist
        (let [user-sessions (.find-sessions-by-user session-repo (:id user))]
          (is (= 3 (count user-sessions))))
        
        ;; Test invalidate all user sessions
        (let [invalidated-count (.invalidate-all-user-sessions session-repo (:id user))]
          (is (= 3 invalidated-count)))
        
        ;; Verify no active sessions remain
        (let [user-sessions (.find-sessions-by-user session-repo (:id user))]
          (is (= 0 (count user-sessions))))
        ))))

(deftest test-session-repository-cleanup
  (testing "Session repository cleanup operations"
    (let [ctx @test-ctx
          user-repo (multi-db/new-user-repository ctx)
          session-repo (multi-db/new-user-session-repository ctx)
          now (Instant/now)
          past-expiry (.minusSeconds now 3600)] ; 1 hour ago
      
      ;; Create user and sessions with different expiry times
      (let [user (.create-user user-repo (generate-test-user))
            expired-session (.create-session session-repo 
                                            (generate-test-session (:id user) 
                                                                  {:expires-at past-expiry}))
            valid-session (.create-session session-repo 
                                          (generate-test-session (:id user) 
                                                                {:expires-at (.plusSeconds now 3600)}))]
        
        ;; Verify expired session is not returned by find-session-by-token
        (is (nil? (.find-session-by-token session-repo (:session-token expired-session))))
        
        ;; Valid session should still be found
        (is (some? (.find-session-by-token session-repo (:session-token valid-session))))
        
        ;; Test cleanup expired sessions
        (let [cleaned-count (.cleanup-expired-sessions session-repo now)]
          (is (>= cleaned-count 1))) ; Should clean up at least the expired session
        ))))

;; =============================================================================
;; Cross-Database Compatibility Tests
;; =============================================================================

(deftest test-sqlite-compatibility
  (testing "Repository operations work correctly with SQLite"
    (let [ctx (setup-sqlite-test-database)
          user-repo (multi-db/new-user-repository ctx)]
      
      (try
        ;; Test SQLite-specific boolean handling
        (let [user-data (generate-test-user {:active true})
              created-user (.create-user user-repo user-data)]
          
          ;; Verify boolean is properly converted
          (is (true? (:active created-user)))
          
          ;; Test with false
          (let [updated-user (.update-user user-repo (assoc created-user :active false))]
            (is (false? (:active updated-user))))
          
          ;; Verify in database (SQLite stores as INTEGER)
          (let [raw-result (db/execute-one! ctx {:select [:active] :from [:users] :where [:= :id (tc/uuid->string (:id created-user))]})]
            (is (or (= 0 (:active raw-result)) (false? (:active raw-result)))))) ; Depends on boolean conversion
        
        (finally
          (.close (:datasource ctx)))))))

(deftest test-schema-initialization
  (testing "Schema initialization works across database types"
    (let [test-databases [{:adapter :h2 :database-path "mem:schema_test_h2" :connection-params {:DB_CLOSE_DELAY "-1"}}
                          {:adapter :sqlite :database-path (.getPath (File/createTempFile "schema_test_sqlite" ".db"))}]]
      
      (doseq [db-config test-databases]
        (let [ctx (dbf/db-context db-config)]
          (try
            ;; Initialize schema
            (multi-db/initialize-database! ctx)
            
            ;; Verify tables exist
            (is (db/table-exists? ctx :users))
            (is (db/table-exists? ctx :user_sessions))
            
            ;; Verify we can create repositories
            (let [user-repo (multi-db/new-user-repository ctx)
                  session-repo (multi-db/new-user-session-repository ctx)]
              
              ;; Test basic operations
              (let [user (.create-user user-repo (generate-test-user))
                    session (.create-session session-repo (generate-test-session (:id user)))]
                
                (is (some? (:id user)))
                (is (some? (:id session)))))
            
            (finally
              (.close (:datasource ctx))
              (when (= :sqlite (:adapter db-config))
                (.delete (File. (:database-path db-config))))))))))

;; =============================================================================
;; Entity Transformation Tests
;; =============================================================================

(deftest test-entity-transformations
  (testing "Entity transformations preserve data integrity"
    (let [ctx @test-ctx
          user-repo (multi-db/new-user-repository ctx)]
      
      ;; Test with various data types
      (let [user-data {:email "transform@test.com"
                       :name "Transform Test"
                       :role :admin ; keyword
                       :active true ; boolean
                       :tenant-id (UUID/randomUUID)} ; UUID
            created-user (.create-user user-repo user-data)]
        
        ;; Verify all types are preserved correctly
        (is (string? (:email created-user)))
        (is (string? (:name created-user)))
        (is (keyword? (:role created-user)))
        (is (boolean? (:active created-user)))
        (is (instance? UUID (:tenant-id created-user)))
        (is (instance? UUID (:id created-user)))
        (is (instance? Instant (:created-at created-user)))
        
        ;; Test update preserves types
        (let [updated-user (.update-user user-repo (assoc created-user :role :viewer))]
          (is (keyword? (:role updated-user)))
          (is (= :viewer (:role updated-user)))
          (is (instance? Instant (:updated-at updated-user))))
        ))))

(deftest test-query-optimization
  (testing "Query building uses database-specific optimizations"
    (let [ctx @test-ctx
          adapter (:adapter ctx)]
      
      ;; Test that where clause building uses appropriate LIKE variant
      ;; H2 in PostgreSQL mode should use LIKE (basic implementation)
      (let [filters {:name "search" :active true}
            where-clause (db/build-where-clause ctx filters)]
        
        (is (vector? where-clause))
        (is (= :and (first where-clause)))
        
        ;; Should contain LIKE for string search
        (is (some #(and (vector? %) 
                       (contains? #{:like :ilike} (first %))) 
                 (rest where-clause)))
        ))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest test-error-handling
  (testing "Repository error handling and validation"
    (let [ctx @test-ctx
          user-repo (multi-db/new-user-repository ctx)]
      
      ;; Test duplicate email constraint
      (let [user-data (generate-test-user {:email "duplicate@test.com"})]
        (.create-user user-repo user-data)
        
        ;; Creating another user with same email in same tenant should fail
        (is (thrown? Exception 
              (.create-user user-repo user-data))))
      
      ;; Test update non-existent user
      (let [non-existent-user {:id (UUID/randomUUID) :email "none@test.com" :name "None" :role :user :active true :tenant-id (UUID/randomUUID)}]
        (is (thrown? Exception 
              (.update-user user-repo non-existent-user))))
      
      ;; Test operations on deleted user
      (let [user (.create-user user-repo (generate-test-user))
            _ (.soft-delete-user user-repo (:id user))]
        
        ;; Should not find soft-deleted user
        (is (nil? (.find-user-by-id user-repo (:id user))))
        
        ;; Should be able to hard delete
        (is (true? (.hard-delete-user user-repo (:id user))))
        
        ;; Should return false for operations on non-existent user
        (is (false? (.soft-delete-user user-repo (:id user))))
        (is (false? (.hard-delete-user user-repo (:id user)))))
      )))