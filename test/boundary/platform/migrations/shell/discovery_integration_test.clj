(ns boundary.platform.migrations.shell.discovery-integration-test
  "Integration tests for filesystem-based migration discovery.
   
   Tests file system operations, SQL file scanning, and migration structure validation.
   Uses temporary directories for isolation."
  {:kaocha.testable/meta {:integration true :migrations true}}
  (:require [boundary.platform.migrations.ports :as ports]
            [boundary.platform.migrations.shell.discovery :as discovery]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; -----------------------------------------------------------------------------
;; Test Fixtures - Temporary Directory Management
;; -----------------------------------------------------------------------------

(def ^:dynamic *test-dir* nil)

(defn- create-temp-dir
  "Create a temporary directory for testing."
  []
  (let [path (Files/createTempDirectory "migration-discovery-test" (make-array FileAttribute 0))]
    (.toFile path)))

(defn- delete-directory-recursive
  "Recursively delete a directory and all its contents."
  [^File dir]
  (when (.exists dir)
    (doseq [file (.listFiles dir)]
      (if (.isDirectory file)
        (delete-directory-recursive file)
        (.delete file)))
    (.delete dir)))

(defn temp-dir-fixture
  "Fixture to create and cleanup temporary directory for each test."
  [f]
  (let [temp-dir (create-temp-dir)]
    (try
      (binding [*test-dir* temp-dir]
        (f))
      (finally
        (delete-directory-recursive temp-dir)))))

(use-fixtures :each temp-dir-fixture)

;; -----------------------------------------------------------------------------
;; Test Helpers - Migration File Creation
;; -----------------------------------------------------------------------------

(defn- create-migration-file!
  "Create a migration file in the test directory.
   
   Args:
     module - Module name (creates subdirectory)
     version - Migration version (14-digit timestamp string)
     name - Migration name (snake_case)
     content - SQL content string
     down? - If true, creates a _down.sql file (optional)
     
   Returns:
     File object for created file"
  [module version name content & {:keys [down?]}]
  (let [module-dir (io/file *test-dir* module)
        _ (.mkdirs module-dir)
        filename (str version "_" name (when down? "_down") ".sql")
        file (io/file module-dir filename)]
    (spit file content)
    file))

(defn- create-test-structure!
  "Create a typical migration directory structure for testing."
  []
  ;; User module migrations
  (create-migration-file! "user" "20240101120000" "create_users_table"
                          "CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT);")
  (create-migration-file! "user" "20240101120000" "create_users_table"
                          "DROP TABLE users;"
                          :down? true)
  (create-migration-file! "user" "20240102130000" "add_user_status"
                          "ALTER TABLE users ADD COLUMN status TEXT;")
  
  ;; Billing module migrations
  (create-migration-file! "billing" "20240103140000" "create_invoices_table"
                          "CREATE TABLE invoices (id INTEGER PRIMARY KEY);")
  (create-migration-file! "billing" "20240104150000" "add_invoice_date"
                          "ALTER TABLE invoices ADD COLUMN invoice_date DATE;")
  
  ;; Inventory module migrations
  (create-migration-file! "inventory" "20240105160000" "create_items_table"
                          "CREATE TABLE items (id INTEGER PRIMARY KEY, sku TEXT);"))

;; -----------------------------------------------------------------------------
;; Tests - Basic Discovery
;; -----------------------------------------------------------------------------

(deftest discover-empty-directory-test
  (testing "discovering migrations in empty directory"
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery nil {})]
      (is (empty? migrations)
          "Should return empty collection for empty directory"))))

(deftest discover-single-migration-test
  (testing "discovering a single migration file"
    (create-migration-file! "user" "20240101120000" "create_users_table"
                            "CREATE TABLE users (id INTEGER PRIMARY KEY);")
    
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery nil {})]
      
      (is (= 1 (count migrations))
          "Should discover exactly one migration")
      
      (let [mig (first migrations)]
        (is (= "user" (:module mig)))
        (is (= "20240101120000" (:version mig)))
        (is (= "create_users_table" (:name mig)))
        (is (= false (:down? mig)))
        (is (= :up (:direction mig)))
        (is (= false (:has-down? mig)))
        (is (string? (:content mig)))
        (is (string? (:checksum mig)))
        (is (string? (:file-path mig)))))))

(deftest discover-up-and-down-migrations-test
  (testing "discovering up and down migration pair"
    (create-migration-file! "user" "20240101120000" "create_users_table"
                            "CREATE TABLE users (id INTEGER PRIMARY KEY);")
    (create-migration-file! "user" "20240101120000" "create_users_table"
                            "DROP TABLE users;"
                            :down? true)
    
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery nil {})]
      
      (is (= 2 (count migrations))
          "Should discover both up and down migrations")
      
      (let [up-mig (first (filter #(= :up (:direction %)) migrations))
            down-mig (first (filter #(= :down (:direction %)) migrations))]
        
        ;; Up migration checks
        (is (some? up-mig) "Should find up migration")
        (is (= false (:down? up-mig)))
        (is (= :up (:direction up-mig)))
        (is (= true (:has-down? up-mig)) "Up migration should know it has a down counterpart")
        
        ;; Down migration checks
        (is (some? down-mig) "Should find down migration")
        (is (= true (:down? down-mig)))
        (is (= :down (:direction down-mig)))
        
        ;; Matching checks
        (is (= (:version up-mig) (:version down-mig)))
        (is (= (:name up-mig) (:name down-mig)))
        (is (= (:module up-mig) (:module down-mig)))))))

(deftest discover-multiple-modules-test
  (testing "discovering migrations across multiple modules"
    (create-test-structure!)
    
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery nil {})]
      
      ;; Total count: 3 user (2 up, 1 down) + 2 billing + 1 inventory = 6
      (is (= 6 (count migrations))
          "Should discover all migrations across all modules")
      
      (let [by-module (group-by :module migrations)]
        (is (= 3 (count (get by-module "user")))
            "Should find 3 user migrations (2 up, 1 down)")
        (is (= 2 (count (get by-module "billing")))
            "Should find 2 billing migrations")
        (is (= 1 (count (get by-module "inventory")))
            "Should find 1 inventory migration")))))

;; -----------------------------------------------------------------------------
;; Tests - Module Filtering
;; -----------------------------------------------------------------------------

(deftest discover-with-module-filter-test
  (testing "filtering migrations by module"
    (create-test-structure!)
    
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          user-migrations (ports/discover-migrations discovery nil {:module "user"})
          billing-migrations (ports/discover-migrations discovery nil {:module "billing"})]
      
      (is (= 3 (count user-migrations))
          "Should find only user module migrations")
      (is (every? #(= "user" (:module %)) user-migrations)
          "All returned migrations should be from user module")
      
      (is (= 2 (count billing-migrations))
          "Should find only billing module migrations")
      (is (every? #(= "billing" (:module %)) billing-migrations)
          "All returned migrations should be from billing module"))))

(deftest discover-nonexistent-module-test
  (testing "filtering by nonexistent module"
    (create-test-structure!)
    
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery nil {:module "nonexistent"})]
      
      (is (empty? migrations)
          "Should return empty collection for nonexistent module"))))

;; -----------------------------------------------------------------------------
;; Tests - Module Listing
;; -----------------------------------------------------------------------------

(deftest list-migration-modules-test
  (testing "listing all migration modules"
    (create-test-structure!)
    
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          modules (ports/list-migration-modules discovery nil)]
      
      (is (= 3 (count modules))
          "Should list all three modules")
      (is (contains? (set modules) "user"))
      (is (contains? (set modules) "billing"))
      (is (contains? (set modules) "inventory")))))

(deftest list-modules-empty-directory-test
  (testing "listing modules in empty directory"
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          modules (ports/list-migration-modules discovery nil)]
      
      (is (empty? modules)
          "Should return empty collection for empty directory"))))

;; -----------------------------------------------------------------------------
;; Tests - File Reading
;; -----------------------------------------------------------------------------

(deftest read-migration-file-test
  (testing "reading a specific migration file"
    (let [file (create-migration-file! "user" "20240101120000" "create_users_table"
                                       "CREATE TABLE users (id INTEGER PRIMARY KEY);")
          discovery (discovery/create-discovery (.getPath *test-dir*))
          migration (ports/read-migration-file discovery (.getPath file))]
      
      (is (some? migration) "Should successfully read migration file")
      (is (= "user" (:module migration)))
      (is (= "20240101120000" (:version migration)))
      (is (= "create_users_table" (:name migration)))
      (is (= "CREATE TABLE users (id INTEGER PRIMARY KEY);" (:content migration)))
      (is (string? (:checksum migration)))
      (is (= false (:down? migration))))))

(deftest read-nonexistent-file-test
  (testing "reading a nonexistent file"
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          fake-path (str (.getPath *test-dir*) "/user/nonexistent.sql")
          migration (ports/read-migration-file discovery fake-path)]
      
      (is (nil? migration)
          "Should return nil for nonexistent file"))))

(deftest read-down-migration-file-test
  (testing "reading a down migration file"
    (let [file (create-migration-file! "user" "20240101120000" "create_users_table"
                                       "DROP TABLE users;"
                                       :down? true)
          discovery (discovery/create-discovery (.getPath *test-dir*))
          migration (ports/read-migration-file discovery (.getPath file))]
      
      (is (some? migration) "Should successfully read down migration file")
      (is (= true (:down? migration)))
      (is (= "DROP TABLE users;" (:content migration))))))

;; -----------------------------------------------------------------------------
;; Tests - Structure Validation
;; -----------------------------------------------------------------------------

(deftest validate-valid-structure-test
  (testing "validating a valid migration structure"
    (create-test-structure!)
    
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          results (ports/validate-migration-structure discovery nil)]
      
      (is (= 1 (count results))
          "Should return single validation result")
      
      (let [result (first results)]
        (is (= true (:valid? result))
            "Structure should be valid")
        (is (empty? (:errors result))
            "Should have no errors")))))

(deftest validate-missing-up-migration-test
  (testing "validating structure with orphaned down migration"
    ;; Create only a down migration without corresponding up
    (create-migration-file! "user" "20240101120000" "create_users_table"
                            "DROP TABLE users;"
                            :down? true)
    
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          results (ports/validate-migration-structure discovery nil)]
      
      (is (= 1 (count results)))
      (let [result (first results)]
        (is (= false (:valid? result))
            "Structure should be invalid")
        (is (seq (:errors result))
            "Should have errors")
        (is (some #(= :missing-up-migration (:type %)) (:errors result))
            "Should report missing up migration")))))

(deftest validate-name-mismatch-test
  (testing "validating structure with mismatched up/down names"
    ;; Create up and down migrations with different names
    (create-migration-file! "user" "20240101120000" "create_users_table"
                            "CREATE TABLE users (id INTEGER PRIMARY KEY);")
    (create-migration-file! "user" "20240101120000" "drop_users_table"
                            "DROP TABLE users;"
                            :down? true)
    
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          results (ports/validate-migration-structure discovery nil)]
      
      (is (= 1 (count results)))
      (let [result (first results)]
        (is (= false (:valid? result))
            "Structure should be invalid")
        (is (some #(= :name-mismatch (:type %)) (:errors result))
            "Should report name mismatch")))))

(deftest validate-nonexistent-directory-test
  (testing "validating a nonexistent directory"
    (let [fake-path (str (.getPath *test-dir*) "/nonexistent")
          discovery (discovery/create-discovery fake-path)
          results (ports/validate-migration-structure discovery nil)]
      
      (is (= 1 (count results)))
      (let [result (first results)]
        (is (= false (:valid? result))
            "Should be invalid for nonexistent directory")
        (is (some #(= :directory-not-found (:type %)) (:errors result))
            "Should report directory not found")))))

;; -----------------------------------------------------------------------------
;; Tests - Edge Cases
;; -----------------------------------------------------------------------------

(deftest ignore-non-sql-files-test
  (testing "ignoring non-SQL files in migrations directory"
    (let [module-dir (io/file *test-dir* "user")
          _ (.mkdirs module-dir)
          _ (spit (io/file module-dir "README.md") "# User Migrations")
          _ (spit (io/file module-dir "notes.txt") "Some notes")
          _ (create-migration-file! "user" "20240101120000" "create_users_table"
                                    "CREATE TABLE users (id INTEGER PRIMARY KEY);")
          discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery nil {})]
      
      (is (= 1 (count migrations))
          "Should discover only SQL files, ignoring README.md and notes.txt"))))

(deftest invalid-version-format-test
  (testing "ignoring files with invalid version format"
    (let [module-dir (io/file *test-dir* "user")
          _ (.mkdirs module-dir)
          _ (spit (io/file module-dir "abc_create_users.sql") "CREATE TABLE users (id INTEGER);")
          _ (spit (io/file module-dir "20240101_create_users.sql") "CREATE TABLE users (id INTEGER);")  ; Too short
          _ (create-migration-file! "user" "20240101120000" "create_users_table"
                                    "CREATE TABLE users (id INTEGER PRIMARY KEY);")
          discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery nil {})]
      
      (is (= 1 (count migrations))
          "Should discover only files with valid 14-digit version format"))))

(deftest nested-subdirectories-test
  (testing "discovering files in nested subdirectories within module"
    (let [module-dir (io/file *test-dir* "user")
          nested-dir (io/file module-dir "archive")
          _ (.mkdirs nested-dir)
          _ (spit (io/file nested-dir "20240101120000_old_migration.sql") "CREATE TABLE old (id INTEGER);")
          _ (create-migration-file! "user" "20240202120000" "create_users_table"
                                    "CREATE TABLE users (id INTEGER PRIMARY KEY);")
          discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery nil {})]
      
      ;; Both files should be discovered (nested structure is allowed)
      (is (= 2 (count migrations))
          "Should discover both migrations including nested ones")
      
      (let [names (set (map :name migrations))]
        (is (contains? names "old_migration") "Should find nested migration")
        (is (contains? names "create_users_table") "Should find root-level migration")))))

(deftest checksum-calculation-test
  (testing "checksums are calculated correctly"
    (let [content1 "CREATE TABLE users (id INTEGER PRIMARY KEY);"
          content2 "CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT);"
          _ (create-migration-file! "user" "20240101120000" "create_users_v1" content1)
          _ (create-migration-file! "user" "20240102120000" "create_users_v2" content2)
          discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery nil {})
          mig1 (first (filter #(= "create_users_v1" (:name %)) migrations))
          mig2 (first (filter #(= "create_users_v2" (:name %)) migrations))]
      
      (is (some? (:checksum mig1)) "Migration 1 should have checksum")
      (is (some? (:checksum mig2)) "Migration 2 should have checksum")
      (is (not= (:checksum mig1) (:checksum mig2))
          "Different content should produce different checksums"))))

(deftest empty-migration-file-test
  (testing "handling empty migration files"
    (create-migration-file! "user" "20240101120000" "empty_migration" "")
    
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery nil {})]
      
      (is (= 1 (count migrations)))
      (let [mig (first migrations)]
        (is (= "" (:content mig)))
        (is (string? (:checksum mig)) "Empty content should still have checksum")))))

;; -----------------------------------------------------------------------------
;; Tests - Path Handling
;; -----------------------------------------------------------------------------

(deftest custom-base-path-test
  (testing "using custom base path in discover-migrations"
    (create-test-structure!)
    
    (let [custom-path (.getPath (io/file *test-dir* "user"))
          discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery custom-path {})]
      
      ;; When scanning only the user subdirectory, migrations won't be found
      ;; because extract-module-from-path expects migrations/{module}/{file} structure
      ;; and we're starting from {module} level
      (is (empty? migrations)
          "Should not find migrations when starting from module directory"))))

(deftest absolute-vs-relative-paths-test
  (testing "handling absolute paths correctly"
    (create-migration-file! "user" "20240101120000" "create_users_table"
                            "CREATE TABLE users (id INTEGER PRIMARY KEY);")
    
    (let [discovery (discovery/create-discovery (.getPath *test-dir*))
          migrations (ports/discover-migrations discovery nil {})]
      
      (is (= 1 (count migrations)))
      (let [mig (first migrations)]
        (is (string? (:file-path mig)))
        (is (.isAbsolute (io/file (:file-path mig)))
            "File path should be absolute")))))
