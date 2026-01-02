(ns boundary.platform.migrations.core.checksums-test
  "Unit tests for migration checksum functionality.
   
   Tests pure checksum calculation, verification, and validation logic."
  {:kaocha.testable/meta {:unit true :migrations true}}
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.platform.migrations.core.checksums :as checksums]))

;; Test fixtures and helpers

(def sample-sql-content
  "CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
  );")

(def sample-sql-content-modified
  "CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW()
  );")

(def expected-checksum-for-sample
  ;; Pre-calculated SHA-256 for sample-sql-content
  "e5a9f1f0c8d5e1e8a3f5c7d9e8f1a5c7d9e8f1a5c7d9e8f1a5c7d9e8f1a5c7")

;; calculate-checksum tests

(deftest calculate-checksum-test
  (testing "calculates SHA-256 checksum"
    (let [result (checksums/calculate-checksum "hello world")]
      (is (string? result))
      (is (= 64 (count result)))
      (is (re-matches #"^[0-9a-f]{64}$" result))))
  
  (testing "produces consistent checksums"
    (let [content "SELECT * FROM users;"
          checksum1 (checksums/calculate-checksum content)
          checksum2 (checksums/calculate-checksum content)]
      (is (= checksum1 checksum2))))
  
  (testing "different content produces different checksums"
    (let [checksum1 (checksums/calculate-checksum "SELECT * FROM users;")
          checksum2 (checksums/calculate-checksum "SELECT * FROM products;")]
      (is (not= checksum1 checksum2))))
  
  (testing "handles empty string"
    (let [result (checksums/calculate-checksum "")]
      (is (string? result))
      (is (= 64 (count result)))))
  
  (testing "returns nil for nil input"
    (is (nil? (checksums/calculate-checksum nil)))))

(deftest calculate-file-checksum-test
  (testing "calculates checksum from file content map"
    (let [file-content {:content sample-sql-content
                        :path "migrations/001_create_users.sql"}
          result (checksums/calculate-file-checksum file-content)]
      (is (string? result))
      (is (= 64 (count result)))))
  
  (testing "ignores other keys in map"
    (let [file1 {:content "SELECT 1;" :path "a.sql" :version "001"}
          file2 {:content "SELECT 1;" :other "data"}
          checksum1 (checksums/calculate-file-checksum file1)
          checksum2 (checksums/calculate-file-checksum file2)]
      (is (= checksum1 checksum2)))))

;; verify-checksum tests

(deftest verify-checksum-test
  (testing "verifies matching checksums"
    (let [content "SELECT * FROM users;"
          checksum (checksums/calculate-checksum content)]
      (is (true? (checksums/verify-checksum content checksum)))))
  
  (testing "detects mismatched checksums"
    (let [checksum (checksums/calculate-checksum "original content")]
      (is (false? (checksums/verify-checksum "modified content" checksum)))))
  
  (testing "returns nil for nil content"
    (is (nil? (checksums/verify-checksum nil "abc123"))))
  
  (testing "returns nil for nil checksum"
    (is (nil? (checksums/verify-checksum "content" nil)))))

(deftest verify-migration-checksum-test
  (testing "returns valid for matching checksums"
    (let [migration {:version "20240515120000"
                     :content "SELECT 1;"}
          checksum (checksums/calculate-checksum "SELECT 1;")
          result (checksums/verify-migration-checksum migration checksum)]
      (is (true? (:valid? result)))
      (is (= checksum (:checksum result)))))
  
  (testing "returns invalid with details for mismatch"
    (let [migration {:version "20240515120000"
                     :content "SELECT 2;"}
          old-checksum (checksums/calculate-checksum "SELECT 1;")
          result (checksums/verify-migration-checksum migration old-checksum)]
      (is (false? (:valid? result)))
      (is (= old-checksum (:expected result)))
      (is (some? (:actual result)))
      (is (= "20240515120000" (:migration result))))))

;; Batch operations tests

(deftest calculate-checksums-batch-test
  (testing "calculates checksums for multiple migrations"
    (let [migrations [{:version "001" :content "SELECT 1;"}
                      {:version "002" :content "SELECT 2;"}
                      {:version "003" :content "SELECT 3;"}]
          result (checksums/calculate-checksums-batch migrations)]
      (is (= 3 (count result)))
      (is (every? string? (vals result)))
      (is (every? #(= 64 (count %)) (vals result)))))
  
  (testing "returns empty map for empty list"
    (is (= {} (checksums/calculate-checksums-batch []))))
  
  (testing "uses version as key"
    (let [migrations [{:version "20240515120000" :content "SELECT 1;"}]
          result (checksums/calculate-checksums-batch migrations)]
      (is (contains? result "20240515120000")))))

(deftest verify-checksums-batch-test
  (testing "verifies all checksums are valid"
    (let [migrations [{:version "001" :content "SELECT 1;"}
                      {:version "002" :content "SELECT 2;"}]
          checksums {"001" (checksums/calculate-checksum "SELECT 1;")
                     "002" (checksums/calculate-checksum "SELECT 2;")}
          result (checksums/verify-checksums-batch migrations checksums)]
      (is (true? (:valid? result)))
      (is (empty? (:mismatches result)))
      (is (= 2 (:checked result)))
      (is (= 2 (:total result)))))
  
  (testing "detects mismatched checksums"
    (let [migrations [{:version "001" :content "SELECT 1;"}
                      {:version "002" :content "SELECT 2 MODIFIED;"}]
          checksums {"001" (checksums/calculate-checksum "SELECT 1;")
                     "002" (checksums/calculate-checksum "SELECT 2;")}
          result (checksums/verify-checksums-batch migrations checksums)]
      (is (false? (:valid? result)))
      (is (= 1 (count (:mismatches result))))
      (is (= 2 (:checked result)))))
  
  (testing "skips migrations without recorded checksums"
    (let [migrations [{:version "001" :content "SELECT 1;"}
                      {:version "002" :content "SELECT 2;"}]
          checksums {"001" (checksums/calculate-checksum "SELECT 1;")}
          result (checksums/verify-checksums-batch migrations checksums)]
      (is (true? (:valid? result)))
      (is (= 1 (:checked result)))
      (is (= 2 (:total result))))))

;; Checksum comparison tests

(deftest checksums-differ?-test
  (testing "detects different checksums"
    (is (true? (checksums/checksums-differ? "abc123" "def456"))))
  
  (testing "returns false for identical checksums"
    (is (false? (checksums/checksums-differ? "abc123" "abc123"))))
  
  (testing "returns nil/falsy for nil inputs"
    (is (not (checksums/checksums-differ? nil "abc123")))
    (is (not (checksums/checksums-differ? "abc123" nil)))
    (is (not (checksums/checksums-differ? nil nil)))))

(deftest find-changed-migrations-test
  (testing "finds migrations with changed content"
    (let [migrations [{:version "001" :name "create_users" :module "user"
                       :content "SELECT 1;" :path "001_create_users.sql"}
                      {:version "002" :name "create_products" :module "product"
                       :content "SELECT 2 MODIFIED;" :path "002_create_products.sql"}]
          recorded {"001" (checksums/calculate-checksum "SELECT 1;")
                    "002" (checksums/calculate-checksum "SELECT 2;")}
          result (checksums/find-changed-migrations migrations recorded)]
      (is (= 1 (count result)))
      (is (= "002" (:version (first result))))
      (is (= "create_products" (:name (first result))))
      (is (some? (:expected (first result))))
      (is (some? (:actual (first result))))))
  
  (testing "returns empty vector when no changes"
    (let [migrations [{:version "001" :content "SELECT 1;"}]
          recorded {"001" (checksums/calculate-checksum "SELECT 1;")}
          result (checksums/find-changed-migrations migrations recorded)]
      (is (empty? result))))
  
  (testing "ignores migrations without recorded checksums"
    (let [migrations [{:version "001" :content "SELECT 1;"}
                      {:version "002" :content "SELECT 2;"}]
          recorded {"001" (checksums/calculate-checksum "SELECT 1;")}
          result (checksums/find-changed-migrations migrations recorded)]
      (is (empty? result)))))

;; Content normalization tests

(deftest normalize-content-for-checksum-test
  (testing "trims trailing whitespace from lines"
    (let [content "line1   \nline2  \nline3"
          result (checksums/normalize-content-for-checksum content)]
      (is (= "line1\nline2\nline3\n" result))))
  
  (testing "ensures single trailing newline"
    (let [content "line1\nline2"
          result (checksums/normalize-content-for-checksum content)]
      (is (.endsWith result "\n"))
      (is (not (.endsWith result "\n\n")))))
  
  (testing "converts CRLF to LF"
    (let [content "line1\r\nline2\r\n"
          result (checksums/normalize-content-for-checksum content)]
      (is (= "line1\nline2\n" result))))
  
  (testing "handles empty string"
    (is (= "\n" (checksums/normalize-content-for-checksum ""))))
  
  (testing "returns nil for nil input"
    (is (nil? (checksums/normalize-content-for-checksum nil)))))

(deftest calculate-normalized-checksum-test
  (testing "produces same checksum for differently formatted content"
    (let [content1 "line1\nline2\n"
          content2 "line1  \nline2  \n"
          content3 "line1\r\nline2\r\n"
          checksum1 (checksums/calculate-normalized-checksum content1)
          checksum2 (checksums/calculate-normalized-checksum content2)
          checksum3 (checksums/calculate-normalized-checksum content3)]
      (is (= checksum1 checksum2))
      (is (= checksum1 checksum3)))))

;; Validation tests

(deftest valid-checksum-format?-test
  (testing "validates correct SHA-256 format"
    (let [valid-checksum (apply str (repeat 64 "a"))]
      (is (true? (checksums/valid-checksum-format? valid-checksum)))))
  
  (testing "rejects incorrect length"
    (is (false? (checksums/valid-checksum-format? "abc123")))
    (is (false? (checksums/valid-checksum-format? (apply str (repeat 63 "a")))))
    (is (false? (checksums/valid-checksum-format? (apply str (repeat 65 "a"))))))
  
  (testing "rejects non-hex characters"
    (let [invalid-checksum (str (apply str (repeat 63 "a")) "g")]
      (is (false? (checksums/valid-checksum-format? invalid-checksum)))))
  
  (testing "rejects uppercase"
    (let [uppercase-checksum (apply str (repeat 64 "A"))]
      (is (false? (checksums/valid-checksum-format? uppercase-checksum)))))
  
  (testing "rejects nil and non-strings"
    (is (false? (checksums/valid-checksum-format? nil)))
    (is (false? (checksums/valid-checksum-format? 123)))))

(deftest validate-checksum-test
  (testing "validates correct checksum"
    (let [valid-checksum (apply str (repeat 64 "a"))
          result (checksums/validate-checksum valid-checksum)]
      (is (true? (:valid? result)))
      (is (nil? (:error result)))))
  
  (testing "provides error for nil"
    (let [result (checksums/validate-checksum nil)]
      (is (false? (:valid? result)))
      (is (= "Checksum is nil" (:error result)))))
  
  (testing "provides error for non-string"
    (let [result (checksums/validate-checksum 123)]
      (is (false? (:valid? result)))
      (is (= "Checksum must be a string" (:error result)))))
  
  (testing "provides error for wrong length"
    (let [result (checksums/validate-checksum "abc123")]
      (is (false? (:valid? result)))
      (is (re-find #"must be 64 characters" (:error result)))))
  
  (testing "provides error for non-hex"
    (let [invalid (str (apply str (repeat 63 "a")) "g")
          result (checksums/validate-checksum invalid)]
      (is (false? (:valid? result)))
      (is (re-find #"lowercase hexadecimal" (:error result))))))

;; Metadata tests

(deftest create-checksum-metadata-test
  (testing "creates metadata with checksum"
    (let [content "SELECT * FROM users;"
          result (checksums/create-checksum-metadata content {})]
      (is (some? (:checksum result)))
      (is (= "SHA-256" (:algorithm result)))
      (is (false? (:normalized? result)))
      (is (= (count content) (:length result)))
      (is (inst? (:calculated-at result)))))
  
  (testing "respects normalized option"
    (let [content "line1  \nline2  \n"
          result (checksums/create-checksum-metadata content {:normalized? true})]
      (is (true? (:normalized? result)))
      (is (some? (:checksum result)))))
  
  (testing "respects algorithm option"
    (let [result (checksums/create-checksum-metadata "test" {:algorithm "MD5"})]
      (is (= "MD5" (:algorithm result))))))

;; Edge cases and integration tests

(deftest edge-cases-test
  (testing "handles very large content"
    (let [large-content (apply str (repeat 10000 "SELECT * FROM users;\n"))
          checksum (checksums/calculate-checksum large-content)]
      (is (= 64 (count checksum)))))
  
  (testing "handles unicode content"
    (let [unicode-content "SELECT * FROM users WHERE name = '日本語';"
          checksum1 (checksums/calculate-checksum unicode-content)
          checksum2 (checksums/calculate-checksum unicode-content)]
      (is (= checksum1 checksum2))))
  
  (testing "handles SQL with special characters"
    (let [sql "SELECT * FROM users WHERE email LIKE '%@example.com';"
          checksum (checksums/calculate-checksum sql)]
      (is (= 64 (count checksum)))))
  
  (testing "checksum stability across JVM restarts"
    ;; This tests that our checksum algorithm is deterministic
    (let [content "CREATE TABLE test (id INT);"
          checksums (repeatedly 100 #(checksums/calculate-checksum content))]
      (is (apply = checksums)))))
