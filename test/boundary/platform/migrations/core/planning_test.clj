(ns boundary.platform.migrations.core.planning-test
  "Unit tests for migration planning logic (pure functions).
   
   These tests verify the functional core of the migration system without
   any I/O or side effects. All functions tested are pure."
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.platform.migrations.core.planning :as planning])
  (:import [java.time LocalDateTime]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn make-test-migration
  "Create a valid migration map for testing."
  [version name module & {:keys [content checksum down? reversible file-path]
                          :or {content "SELECT 1;"
                               checksum "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                               down? false
                               reversible false
                               file-path (str "migrations/" module "/" version "_" name ".sql")}}]
  {:version version
   :name name
   :module module
   :content content
   :checksum checksum
   :down? down?
   :reversible reversible
   :file-path file-path})

;; =============================================================================
;; Version Parsing and Validation
;; =============================================================================

(deftest parse-version-test
  (testing "parses valid version strings"
    (let [result (planning/parse-version "20240515120000")]
      (is (= 2024 (:year result)))
      (is (= 5 (:month result)))
      (is (= 15 (:day result)))
      (is (= 12 (:hour result)))
      (is (= 0 (:minute result)))
      (is (= 0 (:second result)))))
  
  (testing "handles midnight and end-of-day times"
    (is (some? (planning/parse-version "20240101000000"))) ; midnight
    (is (some? (planning/parse-version "20240101235959")))) ; 23:59:59
  
  (testing "rejects invalid version strings"
    (is (nil? (planning/parse-version "2024051512")))      ; too short
    (is (nil? (planning/parse-version "202405151200000"))) ; too long
    (is (nil? (planning/parse-version "abcd0515120000")))  ; non-numeric
    (is (nil? (planning/parse-version "")))                ; empty
    (is (nil? (planning/parse-version nil)))))             ; nil

(deftest valid-version?-test
  (testing "validates correct version formats"
    (is (true? (planning/valid-version? "20240515120000")))
    (is (true? (planning/valid-version? "19990101000000")))
    (is (true? (planning/valid-version? "20991231235959"))))
  
  (testing "rejects invalid formats"
    (is (false? (planning/valid-version? "2024-05-15")))
    (is (false? (planning/valid-version? "20240515")))
    (is (false? (planning/valid-version? "invalid")))
    (is (false? (planning/valid-version? "")))))

(deftest version->timestamp-test
  (testing "converts valid versions to LocalDateTime"
    (let [ts (planning/version->timestamp "20240515120000")]
      (is (instance? LocalDateTime ts))
      (is (= 2024 (.getYear ts)))
      (is (= 5 (.getMonthValue ts)))
      (is (= 15 (.getDayOfMonth ts)))
      (is (= 12 (.getHour ts)))
      (is (= 0 (.getMinute ts)))
      (is (= 0 (.getSecond ts)))))
  
  (testing "returns nil for invalid versions"
    (is (nil? (planning/version->timestamp "invalid")))
    (is (nil? (planning/version->timestamp "")))
    (is (nil? (planning/version->timestamp nil))))
  
  (testing "handles edge cases"
    (is (some? (planning/version->timestamp "20240229120000")))  ; leap year
    (is (some? (planning/version->timestamp "20231231235959"))))) ; year boundary

;; =============================================================================
;; Filename Parsing
;; =============================================================================

(deftest migration-filename?-test
  (testing "validates correct migration filenames"
    (is (true? (planning/migration-filename? "20240515120000_create_users.sql")))
    (is (true? (planning/migration-filename? "20240515120000_add_email_index.sql")))
    (is (true? (planning/migration-filename? "20240515120000_create_users_down.sql"))))
  
  (testing "rejects invalid filenames"
    (is (false? (planning/migration-filename? "create_users.sql")))
    (is (false? (planning/migration-filename? "2024_create_users.sql")))
    (is (false? (planning/migration-filename? "20240515_create_users")))
    (is (false? (planning/migration-filename? "")))
    (is (false? (planning/migration-filename? nil)))))

(deftest parse-migration-filename-test
  (testing "parses up migration filenames"
    (let [result (planning/parse-migration-filename "20240515120000_create_users.sql")]
      (is (= "20240515120000" (:version result)))
      (is (= "create_users" (:name result)))
      (is (= :up (:direction result)))))
  
  (testing "parses down migration filenames"
    (let [result (planning/parse-migration-filename "20240515120000_create_users_down.sql")]
      (is (= "20240515120000" (:version result)))
      (is (= "create_users" (:name result)))
      (is (= :down (:direction result)))))
  
  (testing "handles complex names with underscores"
    (let [result (planning/parse-migration-filename "20240515120000_add_user_email_index.sql")]
      (is (= "add_user_email_index" (:name result)))))
  
  (testing "rejects invalid filenames"
    (is (nil? (planning/parse-migration-filename "invalid.sql")))
    (is (nil? (planning/parse-migration-filename "")))))

;; =============================================================================
;; Migration Sorting and Ordering
;; =============================================================================

(deftest sort-migrations-test
  (testing "sorts migrations by version ascending"
    (let [migrations [(make-test-migration "20240515120000" "third" "user")
                      (make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20240301120000" "second" "user")]
          sorted (planning/sort-migrations migrations)]
      (is (= "20240101120000" (:version (first sorted))))
      (is (= "20240301120000" (:version (second sorted))))
      (is (= "20240515120000" (:version (nth sorted 2))))))
  
  (testing "handles empty list"
    (is (empty? (planning/sort-migrations []))))
  
  (testing "handles single migration"
    (let [migrations [(make-test-migration "20240101120000" "only" "user")]
          sorted (planning/sort-migrations migrations)]
      (is (= 1 (count sorted)))
      (is (= "20240101120000" (:version (first sorted))))))
  
  (testing "preserves other fields during sorting"
    (let [migrations [(make-test-migration "20240515120000" "second" "user" :content "CREATE TABLE foo;")
                      (make-test-migration "20240101120000" "first" "billing" :content "CREATE TABLE bar;")]
          sorted (planning/sort-migrations migrations)]
      (is (= "CREATE TABLE bar;" (:content (first sorted))))
      (is (= "CREATE TABLE foo;" (:content (second sorted)))))))

(deftest group-migrations-by-module-test
  (testing "groups migrations correctly"
    (let [migrations [(make-test-migration "20240101120000" "m1" "user")
                      (make-test-migration "20240101120001" "m2" "billing")
                      (make-test-migration "20240101120002" "m3" "user")]
          grouped (planning/group-migrations-by-module migrations)]
      (is (= 2 (count (get grouped "user"))))
      (is (= 1 (count (get grouped "billing"))))))
  
  (testing "handles empty list"
    (is (empty? (planning/group-migrations-by-module []))))
  
  (testing "handles nil module"
    (let [migrations [{:version "20240101120000" :module nil}]
          grouped (planning/group-migrations-by-module migrations)]
      (is (contains? grouped nil)))))

(deftest filter-migrations-by-module-test
  (testing "filters by module name"
    (let [migrations [(make-test-migration "20240101120000" "m1" "user")
                      (make-test-migration "20240101120001" "m2" "billing")
                      (make-test-migration "20240101120002" "m3" "user")]
          filtered (planning/filter-migrations-by-module migrations "user")]
      (is (= 2 (count filtered)))
      (is (every? #(= "user" (:module %)) filtered))))
  
  (testing "returns empty when no matches"
    (let [migrations [(make-test-migration "20240101120000" "m1" "user")]
          filtered (planning/filter-migrations-by-module migrations "nonexistent")]
      (is (empty? filtered))))
  
  (testing "handles empty list"
    (is (empty? (planning/filter-migrations-by-module [] "user")))))

;; =============================================================================
;; Gap Detection
;; =============================================================================

(deftest detect-version-gaps-test
  (testing "detects large time gaps"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20240601120000" "second" "user")]  ; 5 months gap
          gaps (planning/detect-version-gaps migrations 1440)]  ; 1 day threshold
      (is (= 1 (count gaps)))
      (is (= "20240101120000" (:before (first gaps))))
      (is (= "20240601120000" (:after (first gaps))))))
  
  (testing "no gaps when migrations are close together"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20240101130000" "second" "user")]  ; 1 hour gap
          gaps (planning/detect-version-gaps migrations 1440)]
      (is (empty? gaps))))
  
  (testing "handles single migration"
    (let [migrations [(make-test-migration "20240101120000" "only" "user")]
          gaps (planning/detect-version-gaps migrations)]
      (is (empty? gaps))))
  
  (testing "handles empty list"
    (is (empty? (planning/detect-version-gaps [])))))

;; =============================================================================
;; Duplicate Detection
;; =============================================================================

(deftest detect-duplicate-versions-test
  (testing "detects duplicate versions across modules"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20240101120000" "second" "billing")]
          duplicates (planning/detect-duplicate-versions migrations)]
      (is (= 1 (count duplicates)))
      (is (= "20240101120000" (:version (first duplicates))))
      (is (= 2 (count (:migrations (first duplicates)))))))
  
  (testing "no duplicates when all versions unique"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20240101120001" "second" "billing")]
          duplicates (planning/detect-duplicate-versions migrations)]
      (is (empty? duplicates))))
  
  (testing "detects triple duplicates"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20240101120000" "second" "billing")
                      (make-test-migration "20240101120000" "third" "inventory")]
          duplicates (planning/detect-duplicate-versions migrations)]
      (is (= 1 (count duplicates)))
      (is (= 3 (count (:migrations (first duplicates)))))))
  
  (testing "handles empty list"
    (is (empty? (planning/detect-duplicate-versions [])))))

;; =============================================================================
;; Migration Planning
;; =============================================================================

(deftest pending-migrations-test
  (testing "identifies pending migrations"
    (let [discovered [(make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20240101120001" "second" "user")
                      (make-test-migration "20240101120002" "third" "user")]
          applied [(make-test-migration "20240101120000" "first" "user")]
          pending (planning/pending-migrations discovered applied)]
      (is (= 2 (count pending)))
      (is (= "20240101120001" (:version (first pending))))
      (is (= "20240101120002" (:version (second pending))))))
  
  (testing "returns empty when all applied"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")]
          pending (planning/pending-migrations migrations migrations)]
      (is (empty? pending))))
  
  (testing "returns all when none applied"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20240101120001" "second" "user")]
          pending (planning/pending-migrations migrations [])]
      (is (= 2 (count pending)))))
  
  (testing "returns sorted by version"
    (let [discovered [(make-test-migration "20240101120002" "third" "user")
                      (make-test-migration "20240101120001" "second" "user")]
          pending (planning/pending-migrations discovered [])]
      (is (= "20240101120001" (:version (first pending))))
      (is (= "20240101120002" (:version (second pending)))))))

(deftest migrations-to-version-test
  (testing "plans forward migration to target version"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20240101120001" "second" "user")
                      (make-test-migration "20240101120002" "third" "user")]
          applied [(make-test-migration "20240101120000" "first" "user")]
          result (planning/migrations-to-version migrations applied "20240101120002")]
      (is (= :success (:status result)))
      (is (= 2 (count (:migrations result))))))
  
  (testing "plans rollback to target version"
    (let [migrations [(make-test-migration "20240101120000" "first" "user" :reversible true :down-sql "DROP TABLE users;")
                      (make-test-migration "20240101120001" "second" "user" :reversible true :down-sql "DROP TABLE posts;")
                      (make-test-migration "20240101120002" "third" "user" :reversible true :down-sql "DROP TABLE comments;")]
          applied migrations
          result (planning/migrations-to-version migrations applied "20240101120000")]
      (is (= :success (:status result)))
      (is (= 2 (count (:migrations result))))
      (is (= "20240101120002" (:version (first (:migrations result)))))
      (is (= "20240101120001" (:version (second (:migrations result)))))))
  
  (testing "returns error for non-existent version"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")]
          result (planning/migrations-to-version migrations [] "99999999999999")]
      (is (= :error (:status result)))
      (is (some? (:message result)))))
  
  (testing "returns empty migrations when already at target"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")]
          applied migrations
          result (planning/migrations-to-version migrations applied "20240101120000")]
      (is (= :success (:status result)))
      (is (empty? (:migrations result))))))

;; =============================================================================
;; Validation
;; =============================================================================

(deftest validate-migration-plan-test
  (testing "validates correct migration plan"
    (let [migrations [(make-test-migration "20240101120000" "create_users" "user")]
          result (planning/validate-migration-plan migrations)]
      (is (true? (:valid? result)))
      (is (empty? (:errors result)))))
  
  (testing "detects duplicate versions"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20240101120000" "second" "billing")]
          result (planning/validate-migration-plan migrations)]
      (is (false? (:valid? result)))
      (is (= :duplicate-versions (:type (first (:errors result)))))))
  
  (testing "warns about version gaps"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20241231120000" "second" "user")]
          result (planning/validate-migration-plan migrations)]
      (is (true? (:valid? result)))  ; warnings don't fail validation
      (is (seq (:warnings result)))
      (is (= :version-gaps (:type (first (:warnings result)))))))
  
  (testing "handles empty migration list"
    (let [result (planning/validate-migration-plan [])]
      (is (true? (:valid? result)))
      (is (empty? (:errors result)))
      (is (empty? (:warnings result))))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest edge-cases-test
  (testing "handles migrations at year boundaries"
    (is (some? (planning/version->timestamp "20231231235959")))
    (is (some? (planning/version->timestamp "20240101000000"))))
  
  (testing "handles leap year dates"
    (is (some? (planning/version->timestamp "20240229120000")))  ; leap year
    (is (nil? (planning/version->timestamp "20230229120000")))) ; non-leap year
  
  (testing "handles migrations with same timestamp different names"
    (let [migrations [(make-test-migration "20240101120000" "first" "user")
                      (make-test-migration "20240101120000" "second" "user")]
          sorted (planning/sort-migrations migrations)]
      (is (= 2 (count sorted)))))
  
  (testing "handles very old and very new dates"
    (is (some? (planning/version->timestamp "19700101000000")))
    (is (some? (planning/version->timestamp "20991231235959"))))
  
  (testing "sorts migrations correctly with microsecond precision"
    (let [migrations [(make-test-migration "20240101120003" "third" "user")
                      (make-test-migration "20240101120001" "first" "user")
                      (make-test-migration "20240101120002" "second" "user")]
          sorted (planning/sort-migrations migrations)]
      (is (= "20240101120001" (:version (first sorted))))
      (is (= "20240101120002" (:version (second sorted))))
      (is (= "20240101120003" (:version (nth sorted 2)))))))
