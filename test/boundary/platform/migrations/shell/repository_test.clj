(ns boundary.platform.migrations.shell.repository-test
  "Integration tests for migration repository with real database.
   
   Tests the shell/repository.clj implementation against H2 in-memory database."
  {:kaocha.testable/meta {:integration true :migrations true}}
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.platform.migrations.shell.repository :as repo]
            [boundary.platform.migrations.ports :as ports]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; -----------------------------------------------------------------------------
;; Test Database Setup
;; -----------------------------------------------------------------------------

(def ^:private test-db-spec
  "H2 in-memory database for testing."
  {:jdbcUrl "jdbc:h2:mem:migration_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"})

(defn- create-test-db-context
  "Create a fresh database context for testing."
  []
  (jdbc/get-datasource test-db-spec))

(defn- cleanup-test-db
  "Drop all tables from test database."
  [db-ctx]
  (try
    (jdbc/execute! db-ctx
                   ["DROP TABLE IF EXISTS schema_migrations"])
    (jdbc/execute! db-ctx
                   ["DROP TABLE IF EXISTS migration_locks"])
    (catch Exception e
      (println "Cleanup error:" (.getMessage e)))))

(def ^:dynamic *test-db-context* nil)

(defn test-db-fixture
  "Fixture to create and cleanup test database for each test."
  [test-fn]
  (let [db-ctx (create-test-db-context)]
    (try
      ;; Bind dynamic var for tests to access
      (binding [*test-db-context* db-ctx]
        (test-fn))
      (finally
        (cleanup-test-db db-ctx)))))

(use-fixtures :each test-db-fixture)

;; -----------------------------------------------------------------------------
;; Test Helpers
;; -----------------------------------------------------------------------------

(def ^:private version-counter (atom 0))

(defn- generate-test-version
  "Generate a unique test version string."
  []
  (format "202405%08d" (swap! version-counter inc)))

(defn- create-test-migration
  "Create a test migration map with optional overrides."
  [& {:keys [version name module checksum status error-message]
      :or {version nil
           name "test_migration"
           module "test"
           checksum "abc123def456"
           status :success
           error-message nil}}]
  {:version (or version (generate-test-version))
   :name name
   :module module
   :checksum checksum
   :execution-time-ms 100
   :db-type "h2"
   :status status
   :applied-at (java.time.Instant/now)
   :error-message error-message})

(defn- ensure-migrations-table
  "Ensure schema_migrations table exists."
  []
  (repo/ensure-schema-migrations-table! *test-db-context*))

;; -----------------------------------------------------------------------------
;; Repository Creation Tests
;; -----------------------------------------------------------------------------

(deftest create-repository-test
  (testing "creates repository with valid database context"
    (let [repository (repo/create-repository *test-db-context*)]
      (is (some? repository))
      (is (satisfies? ports/IMigrationRepository repository))))
  
  (testing "repository can ensure table creation"
    (let [repository (repo/create-repository *test-db-context*)]
      (is (nil? (ensure-migrations-table)))
      ;; Verify table exists by querying it
      (let [result (jdbc/execute! *test-db-context*
                                  ["SELECT COUNT(*) as cnt FROM schema_migrations"]
                                  {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 0 (:cnt (first result))))))))

;; -----------------------------------------------------------------------------
;; CRUD Operations Tests
;; -----------------------------------------------------------------------------

(deftest record-migration-test
  (testing "records a successful migration"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          migration (create-test-migration)
          result (ports/record-migration repository migration)]
      (is (some? result))
      (is (= (:version migration) (:version result)))
      (is (= "test_migration" (:name result)))
      (is (= "test" (:module result)))
      (is (inst? (:applied-at result)))
      (is (= :success (:status result)))))
  
  (testing "records migration with failure status"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          migration (create-test-migration
                                          :status :failed
                                          :error-message "Syntax error in SQL")
          result (ports/record-migration repository migration)]
      (is (= :failed (:status result)))
      (is (= "Syntax error in SQL" (:error-message result)))))
  
  (testing "records multiple migrations"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          count-before (ports/count-migrations repository)
          m1 (create-test-migration :name "create_users")
          m2 (create-test-migration :name "add_email_index")
          m3 (create-test-migration :name "create_products")]
      (ports/record-migration repository m1)
      (ports/record-migration repository m2)
      (ports/record-migration repository m3)
      (is (= (+ count-before 3) (ports/count-migrations repository))))))

(deftest find-operations-test
  (testing "find-all-applied returns all migrations"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          m1 (create-test-migration :name "create_users")
          m2 (create-test-migration :name "create_products")]
      (ports/record-migration repository m1)
      (ports/record-migration repository m2)
      (let [all-migrations (ports/find-all-applied repository)]
        (is (= 2 (count all-migrations)))
        (is (= #{(:version m1) (:version m2)}
               (set (map :version all-migrations)))))))
  
  (testing "find-by-version retrieves specific migration"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          migration (create-test-migration)]
      (ports/record-migration repository migration)
      (let [found (ports/find-by-version repository (:version migration))]
        (is (some? found))
        (is (= (:version migration) (:version found)))
        (is (= "test_migration" (:name found))))))
  
  (testing "find-by-version returns nil for non-existent version"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)]
      (is (nil? (ports/find-by-version repository "99999999999999")))))
  
  (testing "find-applied-by-module filters by module"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          user-m1 (create-test-migration :module "user" :name "create_users")
          user-m2 (create-test-migration :module "user" :name "add_sessions")
          billing-m1 (create-test-migration :module "billing" :name "create_invoices")]
      (ports/record-migration repository user-m1)
      (ports/record-migration repository user-m2)
      (ports/record-migration repository billing-m1)
      (let [user-migrations (ports/find-applied-by-module repository "user")]
        (is (= 2 (count user-migrations)))
        (is (every? #(= "user" (:module %)) user-migrations)))
      (let [billing-migrations (ports/find-applied-by-module repository "billing")]
        (is (= 1 (count billing-migrations)))))))

(deftest update-migration-status-test
  (testing "updates migration status to failed"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          migration (create-test-migration :status :success)]
      (ports/record-migration repository migration)
      (ports/update-migration-status repository (:version migration) :failed 250 "Constraint violation")
      (let [updated (ports/find-by-version repository (:version migration))]
        (is (= :failed (:status updated)))
        (is (= 250 (:execution-time-ms updated)))
        (is (= "Constraint violation" (:error-message updated))))))
  
  (testing "updates migration status to rolled-back"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          migration (create-test-migration)]
      (ports/record-migration repository migration)
      (ports/update-migration-status repository (:version migration) :rolled-back 0 nil)
      (let [updated (ports/find-by-version repository (:version migration))]
        (is (= :rolled-back (:status updated)))))))

(deftest delete-migration-test
  (testing "deletes migration from ledger"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          migration (create-test-migration)]
      (ports/record-migration repository migration)
      (is (= 1 (ports/count-migrations repository)))
      (let [deleted? (ports/delete-migration repository (:version migration))]
        (is (true? deleted?))
        (is (= 0 (ports/count-migrations repository)))
        (is (nil? (ports/find-by-version repository (:version migration)))))))
  
  (testing "delete returns true even for non-existent migration"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)]
      (is (true? (ports/delete-migration repository "99999999999999"))))))

;; -----------------------------------------------------------------------------
;; Checksum Verification Tests
;; -----------------------------------------------------------------------------

(deftest verify-checksum-test
  (testing "verifies matching checksum"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          migration (create-test-migration :checksum "abc123def456")]
      (ports/record-migration repository migration)
      (is (true? (ports/verify-checksum repository (:version migration) "abc123def456")))))
  
  (testing "detects mismatched checksum"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          migration (create-test-migration :checksum "abc123def456")]
      (ports/record-migration repository migration)
      (is (false? (ports/verify-checksum repository (:version migration) "different-checksum")))))
  
  (testing "returns false for non-existent version"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)]
      (is (false? (ports/verify-checksum repository "99999999999999" "any-checksum"))))))

;; -----------------------------------------------------------------------------
;; Query Helper Tests
;; -----------------------------------------------------------------------------

(deftest get-last-migration-test
  (testing "returns most recently applied migration"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          m1 (create-test-migration :name "first")
          m2 (create-test-migration :name "second")
          m3 (create-test-migration :name "third")]
      (ports/record-migration repository m1)
      (Thread/sleep 10) ; Ensure different timestamps
      (ports/record-migration repository m2)
      (Thread/sleep 10)
      (ports/record-migration repository m3)
      (let [last-migration (ports/get-last-migration repository)]
        (is (some? last-migration))
        (is (= (:version m3) (:version last-migration)))
        (is (= "third" (:name last-migration)))))))

(deftest count-migrations-test
  (testing "counts total migrations"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)]
      (is (= 0 (ports/count-migrations repository)))
      (ports/record-migration repository (create-test-migration))
      (is (= 1 (ports/count-migrations repository)))
      (ports/record-migration repository (create-test-migration))
      (is (= 2 (ports/count-migrations repository)))
      (ports/record-migration repository (create-test-migration))
      (is (= 3 (ports/count-migrations repository))))))

;; -----------------------------------------------------------------------------
;; Edge Cases and Error Handling
;; -----------------------------------------------------------------------------

(deftest edge-cases-test
  (testing "handles migrations with long names"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          long-name (apply str (repeat 200 "a"))
          migration (create-test-migration :name long-name)]
      (is (some? (ports/record-migration repository migration)))))
  
  (testing "handles migrations with special characters in name"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          special-name "create_users_with_email@domain.com_support"
          migration (create-test-migration :name special-name)]
      (is (some? (ports/record-migration repository migration)))
      (let [found (ports/find-by-version repository (:version migration))]
        (is (= special-name (:name found))))))
  
  (testing "handles nil error message gracefully"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          migration (create-test-migration :error-message nil)]
      (is (some? (ports/record-migration repository migration)))
      (let [found (ports/find-by-version repository (:version migration))]
        (is (nil? (:error-message found))))))
  
  (testing "preserves migration order by version"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)]
      ;; Insert in order (auto-generated versions are sequential)
      (let [m1 (create-test-migration)
            m2 (create-test-migration)
            m3 (create-test-migration)]
        (ports/record-migration repository m1)
        (ports/record-migration repository m2)
        (ports/record-migration repository m3)
        (let [all-migrations (ports/find-all-applied repository)
              versions (map :version all-migrations)]
          ;; Should be returned in version order (ascending)
          (is (= (sort versions) versions)))))))

;; -----------------------------------------------------------------------------
;; Module Filtering Tests
;; -----------------------------------------------------------------------------

(deftest module-filtering-test
  (testing "filters migrations across multiple modules"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)]
      ;; Create migrations for different modules
      (ports/record-migration repository (create-test-migration :module "user" :name "create_users"))
      (ports/record-migration repository (create-test-migration :module "user" :name "add_sessions"))
      (ports/record-migration repository (create-test-migration :module "billing" :name "create_invoices"))
      (ports/record-migration repository (create-test-migration :module "billing" :name "add_payments"))
      (ports/record-migration repository (create-test-migration :module "platform" :name "system_settings"))
      
      ;; Test each module filter
      (is (= 2 (count (ports/find-applied-by-module repository "user"))))
      (is (= 2 (count (ports/find-applied-by-module repository "billing"))))
      (is (= 1 (count (ports/find-applied-by-module repository "platform"))))
      (is (= 0 (count (ports/find-applied-by-module repository "nonexistent"))))))
  
  (testing "module filtering with mixed statuses"
    (let [repository (repo/create-repository *test-db-context*)
          _ (ensure-migrations-table)
          count-before (count (ports/find-applied-by-module repository "user"))]
      (ports/record-migration repository (create-test-migration :module "user" :status :success))
      (ports/record-migration repository (create-test-migration :module "user" :status :failed))
      (ports/record-migration repository (create-test-migration :module "user" :status :rolled-back))
      
      (let [user-migrations (ports/find-applied-by-module repository "user")]
        (is (= (+ count-before 3) (count user-migrations)))
        ;; Check that the new migrations we added have all three statuses
        (is (every? #(#{:success :failed :rolled-back} (:status %))
                    (take-last 3 user-migrations)))))))
