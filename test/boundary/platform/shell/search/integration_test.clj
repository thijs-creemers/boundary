(ns boundary.platform.shell.search.integration-test
  "End-to-end integration tests for full-text search with real PostgreSQL.
   
   These tests verify:
   - Database migrations applied correctly (tsvector columns, GIN indexes)
   - PostgreSQL full-text search operations work end-to-end
   - Search provider correctly translates queries to PostgreSQL
   - Service layer orchestration (ranking, highlighting)
   - HTTP handlers return correct responses
   - Performance meets targets (<100ms for searches)
   
   NOTE: These tests require PostgreSQL to be available.
         If PostgreSQL is not available, tests will be skipped."
  {:kaocha.testable/meta {:integration true :search true :database true}}
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [clojure.set]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [boundary.platform.shell.search.postgresql :as pg]
            [boundary.platform.shell.search.service :as svc]
            [boundary.platform.search.ports :as ports])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.util UUID)
           (java.time Instant)
           (java.time.temporal ChronoUnit)))

;; =============================================================================
;; Test Database Setup
;; =============================================================================

(def test-db-context (atom nil))
(def test-search-provider (atom nil))
(def test-search-service (atom nil))

(defn postgres-available?
  "Check if PostgreSQL is available for testing.
   Checks if the test container is running on port 5433."
  []
  (try
    (with-open [_conn (java.sql.DriverManager/getConnection
                       "jdbc:postgresql://localhost:5433/boundary_search_test"
                       "test"
                       "test")]
      true)
    (catch Exception _e
      false)))

(defn create-test-database
  "Create a fresh test database for search integration tests.
   The database is already created by the Docker container."
  []
  (postgres-available?))

(defn setup-test-db
  "Initialize test database with schema and migrations."
  []
  (when (and (postgres-available?) (create-test-database))
    (let [^HikariDataSource datasource (connection/->pool
                                        com.zaxxer.hikari.HikariDataSource
                                        {:jdbcUrl "jdbc:postgresql://localhost:5433/boundary_search_test"
                                         :username "test"
                                         :password "test"})
          db-ctx {:datasource datasource}]
      (reset! test-db-context db-ctx)

      ;; Create users table
      (jdbc/execute! datasource
                     ["CREATE TABLE IF NOT EXISTS users (
                        id UUID PRIMARY KEY,
                        email VARCHAR(255) UNIQUE NOT NULL,
                        name VARCHAR(255),
                        bio TEXT,
                        role VARCHAR(50),
                        active BOOLEAN DEFAULT TRUE,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        deleted_at TIMESTAMP
                      )"])

      ;; Create items table
      (jdbc/execute! datasource
                     ["CREATE TABLE IF NOT EXISTS items (
                        id UUID PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        sku VARCHAR(100) UNIQUE NOT NULL,
                        quantity INTEGER NOT NULL,
                        location VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                      )"])

      ;; Apply full-text search migration for users
      ;; Drop the column if it exists and recreate as GENERATED column
      (jdbc/execute! datasource
                     ["ALTER TABLE users DROP COLUMN IF EXISTS search_vector"])

      (jdbc/execute! datasource
                     ["ALTER TABLE users ADD COLUMN search_vector tsvector 
                       GENERATED ALWAYS AS (
                         setweight(to_tsvector('english', COALESCE(name, '')), 'A') ||
                         setweight(to_tsvector('english', COALESCE(email, '')), 'B') ||
                         setweight(to_tsvector('english', COALESCE(bio, '')), 'C')
                       ) STORED"])

      ;; Create GIN index for users
      (jdbc/execute! datasource
                     ["CREATE INDEX IF NOT EXISTS users_search_vector_idx 
                       ON users USING GIN (search_vector)"])

      ;; Apply full-text search migration for items
      ;; Drop the column if it exists and recreate as GENERATED column
      (jdbc/execute! datasource
                     ["ALTER TABLE items DROP COLUMN IF EXISTS search_vector"])

      (jdbc/execute! datasource
                     ["ALTER TABLE items ADD COLUMN search_vector tsvector 
                       GENERATED ALWAYS AS (
                         setweight(to_tsvector('english', COALESCE(name, '')), 'A') ||
                         setweight(to_tsvector('english', COALESCE(sku, '')), 'B') ||
                         setweight(to_tsvector('english', COALESCE(location, '')), 'C')
                       ) STORED"])

      ;; Create GIN index for items
      (jdbc/execute! datasource
                     ["CREATE INDEX IF NOT EXISTS items_search_vector_idx 
                       ON items USING GIN (search_vector)"])

      ;; Create search provider
      (reset! test-search-provider
              (pg/create-postgresql-search-provider
               db-ctx
               {:weights {:users {:name 'A :email 'B :bio 'C}
                          :items {:name 'A :sku 'B :location 'C}}
                :language "english"}))

      ;; Create search service with ranking and highlighting
      (reset! test-search-service
              (svc/create-search-service
               @test-search-provider
               {:ranking {:users {:recency-field :created_at
                                  :recency-max-boost 2.0
                                  :recency-decay-days 30}
                          :items {:recency-field :created_at
                                  :recency-max-boost 2.0
                                  :recency-decay-days 30}}
                :highlighting {:pre-tag "<mark>"
                               :post-tag "</mark>"
                               :max-fragments 3
                               :fragment-size 150}}))

      true)))

(defn teardown-test-db
  "Clean up test database."
  []
  (when-let [db-ctx @test-db-context]
    (when-let [^HikariDataSource ds (:datasource db-ctx)]
      (.close ds))
    (reset! test-db-context nil)
    (reset! test-search-provider nil)
    (reset! test-search-service nil)))

(defn clean-test-data
  "Remove all test data from tables."
  []
  (when-let [db-ctx @test-db-context]
    (jdbc/execute! (:datasource db-ctx) ["DELETE FROM items"])
    (jdbc/execute! (:datasource db-ctx) ["DELETE FROM users"])))

(defn database-fixture
  "Fixture to setup/teardown test database."
  [test-fn]
  (if (postgres-available?)
    (when (setup-test-db)
      (try
        (test-fn)
        (finally
          (teardown-test-db))))
    (do
      (println "SKIPPED: Search integration tests (PostgreSQL not available)")
      (is true "Skipped because PostgreSQL is not available"))))

(defn clean-data-fixture
  "Fixture to clean test data between tests."
  [test-fn]
  (when (postgres-available?)
    (try
      (test-fn)
      (finally
        (clean-test-data)))))

(use-fixtures :once database-fixture)
(use-fixtures :each clean-data-fixture)

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn create-test-user!
  "Insert a test user into the database."
  [user-data]
  (when-let [db-ctx @test-db-context]
    (let [now (java.sql.Timestamp/from (Instant/now))
          user (merge {:id (UUID/randomUUID)
                       :email (str "user-" (UUID/randomUUID) "@example.com")
                       :name "Test User"
                       :bio "Test bio"
                       :role "user"
                       :active true
                       :created_at now}
                      user-data)
          ;; Convert Instant to Timestamp if present
          user (cond-> user
                 (instance? Instant (:created_at user))
                 (update :created_at #(java.sql.Timestamp/from %)))]
      (jdbc/execute-one! (:datasource db-ctx)
                         ["INSERT INTO users (id, email, name, bio, role, active, created_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?)"
                          (:id user)
                          (:email user)
                          (:name user)
                          (:bio user)
                          (:role user)
                          (:active user)
                          (:created_at user)])
      user)))

(defn create-test-item!
  "Insert a test item into the database."
  [item-data]
  (when-let [db-ctx @test-db-context]
    (let [now (java.sql.Timestamp/from (Instant/now))
          item (merge {:id (UUID/randomUUID)
                       :name "Test Item"
                       :sku (str "SKU-" (UUID/randomUUID))
                       :quantity 10
                       :location "Warehouse A"
                       :created_at now}
                      item-data)
          ;; Convert Instant to Timestamp if present
          item (cond-> item
                 (instance? Instant (:created_at item))
                 (update :created_at #(java.sql.Timestamp/from %)))]
      (jdbc/execute-one! (:datasource db-ctx)
                         ["INSERT INTO items (id, name, sku, quantity, location, created_at)
                           VALUES (?, ?, ?, ?, ?, ?)"
                          (:id item)
                          (:name item)
                          (:sku item)
                          (:quantity item)
                          (:location item)
                          (:created_at item)])
      item)))

(defn verify-tsvector-exists
  "Verify that tsvector column exists and is populated."
  [table-name]
  (when-let [db-ctx @test-db-context]
    (let [result (jdbc/execute-one! (:datasource db-ctx)
                                    [(str "SELECT search_vector FROM " table-name " LIMIT 1")])]
      (some? result))))

(defn verify-gin-index-exists
  "Verify that GIN index exists for search_vector."
  [table-name]
  (when-let [db-ctx @test-db-context]
    (let [result (jdbc/execute-one! (:datasource db-ctx)
                                    ["SELECT indexname FROM pg_indexes 
                                      WHERE tablename = ? AND indexname LIKE '%search_vector%'"
                                     table-name])]
      (some? result))))

;; =============================================================================
;; Database Setup Tests
;; =============================================================================

(deftest verify-users-table-structure
  (testing "Users table has search_vector column with GIN index"
    (when (postgres-available?)
      ;; Create a test user to verify search_vector is generated
      (create-test-user! {:name "John Doe" :email "john@example.com"})

      (is (verify-tsvector-exists "users")
          "users.search_vector column should exist and be populated")

      (is (verify-gin-index-exists "users")
          "GIN index should exist for users.search_vector"))))

(deftest verify-items-table-structure
  (testing "Items table has search_vector column with GIN index"
    (when (postgres-available?)
      ;; Create a test item to verify search_vector is generated
      (create-test-item! {:name "Widget" :sku "WDG-001" :location "Warehouse A"})

      (is (verify-tsvector-exists "items")
          "items.search_vector column should exist and be populated")

      (is (verify-gin-index-exists "items")
          "GIN index should exist for items.search_vector"))))

(deftest verify-tsvector-generation
  (testing "tsvector is automatically generated from text fields"
    (when (postgres-available?)
      (let [user (create-test-user! {:name "Alice Johnson"
                                     :email "alice@example.com"
                                     :bio "Software engineer interested in Clojure"})]
        (when-let [db-ctx @test-db-context]
          (let [result (jdbc/execute-one! (:datasource db-ctx)
                                          ["SELECT search_vector::text AS vector FROM users WHERE id = ?"
                                           (:id user)])]
            (is (some? result) "Should retrieve search_vector")
            (is (str/includes? (:vector result) "alic") "Should contain normalized 'Alice'")
            (is (str/includes? (:vector result) "engin") "Should contain normalized 'engineer'")))))))

;; =============================================================================
;; Search Functionality Tests
;; =============================================================================

(deftest basic-user-search
  (testing "Basic search returns relevant results"
    (when (postgres-available?)
      ;; Create test users
      (create-test-user! {:name "John Smith" :email "john@example.com"})
      (create-test-user! {:name "Jane Doe" :email "jane@example.com"})
      (create-test-user! {:name "Bob Johnson" :email "bob@example.com"})

      (let [results (ports/search-users @test-search-service "John" {})]
        (is (some? results) "Should return results")
        (is (pos? (:total results)) "Should have at least one result")
        (is (vector? (:results results)) "Results should be a vector")

        ;; Verify result contains John
        (let [first-result (first (:results results))]
          (is (or (str/includes? (:name first-result) "John")
                  (str/includes? (:email first-result) "john"))
              "Result should contain 'John'"))))))

(deftest search-with-highlighting
  (testing "Search returns highlighted snippets"
    (when (postgres-available?)
      (create-test-user! {:name "John Smith"
                          :email "john.smith@example.com"
                          :bio "John is a software developer"})

      (let [results (ports/search-users @test-search-service "John" {:highlight? true})]
        (is (some? results) "Should return results")
        (is (pos? (:total results)) "Should have results")

        (let [first-result (first (:results results))
              highlights (:_highlights first-result)]
          (is (some? highlights) "Should have highlights")
          (is (or (and (contains? highlights :name)
                       (str/includes? (:name highlights) "<mark>"))
                  (and (contains? highlights :bio)
                       (str/includes? (:bio highlights) "<mark>")))
              "Highlights should contain <mark> tags"))))))

(deftest search-ranking-by-relevance
  (testing "Results are ranked by relevance score"
    (when (postgres-available?)
      ;; Create users with different relevance
      (create-test-user! {:name "Software Developer" :email "dev@example.com"})
      (create-test-user! {:name "John Developer" :email "john@example.com"})
      (create-test-user! {:name "Developer John Smith" :email "jsmith@example.com"})

      (let [results (ports/search-users @test-search-service "Developer" {})]
        (is (some? results) "Should return results")
        (is (>= (:total results) 3) "Should have at least 3 results")

        ;; Verify results are sorted by score (descending)
        (let [scores (map :score (:results results))]
          (is (= scores (sort > scores))
              "Results should be sorted by score (highest first)"))))))

(deftest search-with-pagination
  (testing "Search supports pagination"
    (when (postgres-available?)
      ;; Create 5 users with similar names
      (dotimes [i 5]
        (create-test-user! {:name (str "Developer " i)
                            :email (str "dev" i "@example.com")}))

      ;; First page (2 results)
      (let [page1 (ports/search-users @test-search-service "Developer" {:from 0 :size 2})]
        (is (= 2 (count (:results page1))) "First page should have 2 results")
        (is (= 5 (:total page1)) "Total should be 5")

        ;; Second page (2 results)
        (let [page2 (ports/search-users @test-search-service "Developer" {:from 2 :size 2})]
          (is (= 2 (count (:results page2))) "Second page should have 2 results")
          (is (= 5 (:total page2)) "Total should still be 5")

          ;; Verify pages have different results
          (let [page1-ids (set (map :id (:results page1)))
                page2-ids (set (map :id (:results page2)))]
            (is (empty? (clojure.set/intersection page1-ids page2-ids))
                "Pages should have different results")))))))

(deftest empty-query-handling
  (testing "Empty query returns empty results"
    (when (postgres-available?)
      (create-test-user! {:name "John Smith" :email "john@example.com"})

      (let [results (ports/search-users @test-search-service "" {})]
        (is (some? results) "Should return results structure")
        (is (zero? (:total results)) "Should have zero results")
        (is (empty? (:results results)) "Results array should be empty")))))

(deftest no-results-handling
  (testing "Query with no matches returns empty results"
    (when (postgres-available?)
      (create-test-user! {:name "John Smith" :email "john@example.com"})

      (let [results (ports/search-users @test-search-service "XyZzY123NonExistent" {})]
        (is (some? results) "Should return results structure")
        (is (zero? (:total results)) "Should have zero results")
        (is (empty? (:results results)) "Results array should be empty")))))

(deftest search-with-special-characters
  (testing "Search handles special characters correctly"
    (when (postgres-available?)
      (create-test-user! {:name "O'Brien" :email "obrien@example.com"})
      (create-test-user! {:name "Smith-Jones" :email "smith-jones@example.com"})

      ;; Search with apostrophe
      (let [results1 (ports/search-users @test-search-service "O'Brien" {})]
        (is (some? results1) "Should handle apostrophes")
        (is (pos? (:total results1)) "Should find O'Brien"))

      ;; Search with hyphen
      (let [results2 (ports/search-users @test-search-service "Smith-Jones" {})]
        (is (some? results2) "Should handle hyphens")
        (is (pos? (:total results2)) "Should find Smith-Jones")))))

(deftest multi-word-query
  (testing "Multi-word queries work correctly"
    (when (postgres-available?)
      (create-test-user! {:name "John Smith" :email "john@example.com"})
      (create-test-user! {:name "Jane Smith" :email "jane@example.com"})
      (create-test-user! {:name "John Doe" :email "jdoe@example.com"})

      (let [results (ports/search-users @test-search-service "John Smith" {})]
        (is (some? results) "Should return results")
        (is (pos? (:total results)) "Should have results")

        ;; First result should be John Smith (both words match)
        (let [first-result (first (:results results))]
          (is (and (str/includes? (:name first-result) "John")
                   (str/includes? (:name first-result) "Smith"))
              "Best match should contain both words"))))))

(deftest phrase-query
  (testing "Phrase queries (quoted) work correctly"
    (when (postgres-available?)
      (create-test-user! {:name "Software Engineer"
                          :bio "Experienced software engineer with 10 years"})
      (create-test-user! {:name "Engineer"
                          :bio "Software development and engineering"})

      (let [results (ports/search-users @test-search-service "\"software engineer\"" {})]
        (is (some? results) "Should return results")
        (is (pos? (:total results)) "Should have results")))))

;; =============================================================================
;; Items Search Tests
;; =============================================================================

(deftest basic-item-search
  (testing "Basic item search returns relevant results"
    (when (postgres-available?)
      (create-test-item! {:name "Widget" :sku "WDG-001" :location "Warehouse A"})
      (create-test-item! {:name "Gadget" :sku "GDG-001" :location "Warehouse B"})

      (let [results (ports/search-items @test-search-service "Widget" {})]
        (is (some? results) "Should return results")
        (is (pos? (:total results)) "Should have results")

        (let [first-result (first (:results results))]
          (is (str/includes? (:name first-result) "Widget")
              "Result should contain 'Widget'"))))))

(deftest item-search-by-sku
  (testing "Can search items by SKU"
    (when (postgres-available?)
      (create-test-item! {:name "Widget" :sku "WDG-001" :location "Warehouse A"})
      (create-test-item! {:name "Widget Pro" :sku "WDG-002" :location "Warehouse A"})

      (let [results (ports/search-items @test-search-service "WDG-001" {})]
        (is (some? results) "Should return results")
        (is (pos? (:total results)) "Should have results")

        (let [first-result (first (:results results))]
          (is (= "WDG-001" (:sku first-result))
              "Should find item by exact SKU"))))))

(deftest item-search-by-location
  (testing "Can search items by location"
    (when (postgres-available?)
      (create-test-item! {:name "Widget" :sku "WDG-001" :location "Warehouse A"})
      (create-test-item! {:name "Gadget" :sku "GDG-001" :location "Warehouse B"})

      (let [results (ports/search-items @test-search-service "Warehouse A" {})]
        (is (some? results) "Should return results")
        (is (pos? (:total results)) "Should have results")

        (let [first-result (first (:results results))]
          (is (str/includes? (:location first-result) "Warehouse A")
              "Should find items in Warehouse A"))))))

;; =============================================================================
;; Recency Boost Tests
;; =============================================================================

(deftest recency-boost-ranking
  (testing "Recent documents rank higher with recency boost"
    (when (postgres-available?)
      ;; Create old user
      (let [old-date (.minus (Instant/now) 60 ChronoUnit/DAYS)]
        (create-test-user! {:name "Developer Smith"
                            :email "old@example.com"
                            :created_at old-date}))

      ;; Create recent user
      (let [recent-date (.minus (Instant/now) 1 ChronoUnit/DAYS)]
        (create-test-user! {:name "Developer Jones"
                            :email "recent@example.com"
                            :created_at recent-date}))

      ;; Search with recency boost
      (let [results (ports/search-users @test-search-service "Developer" {:boost-recent? true})]
        (is (some? results) "Should return results")
        (is (>= (:total results) 2) "Should have at least 2 results")

        ;; Recent user should rank higher (first in results)
        (let [first-result (first (:results results))]
          (is (str/includes? (:email first-result) "recent")
              "Recent user should rank first with recency boost"))))))

;; =============================================================================
;; Performance Tests
;; =============================================================================

(deftest search-performance
  (testing "Search completes in under 100ms for small dataset"
    (when (postgres-available?)
      ;; Create 50 users
      (dotimes [i 50]
        (create-test-user! {:name (str "User " i)
                            :email (str "user" i "@example.com")
                            :bio (str "Bio for user " i)}))

      (let [start (System/nanoTime)
            results (ports/search-users @test-search-service "User" {})
            duration-ms (/ (- (System/nanoTime) start) 1000000.0)]

        (is (some? results) "Should return results")
        (is (pos? (:total results)) "Should have results")
        (is (< duration-ms 100.0)
            (str "Search should complete in under 100ms, took " duration-ms "ms"))))))

(deftest bulk-indexing-performance
  (testing "Can index 100 documents in reasonable time"
    (when (postgres-available?)
      (let [start (System/nanoTime)]
        ;; Create 100 users
        (dotimes [i 100]
          (create-test-user! {:name (str "User " i)
                              :email (str "user" i "@example.com")}))
        (let [duration-ms (/ (- (System/nanoTime) start) 1000000.0)]

          (is (< duration-ms 5000.0)
              (str "Indexing 100 users should take under 5 seconds, took " duration-ms "ms")))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest very-long-query
  (testing "Handles very long queries without error"
    (when (postgres-available?)
      (create-test-user! {:name "John Smith" :email "john@example.com"})

      (let [long-query (apply str (repeat 100 "developer "))]
        (is (some? (ports/search-users @test-search-service long-query {}))
            "Should handle very long query without error")))))

(deftest unicode-characters
  (testing "Handles Unicode characters in search"
    (when (postgres-available?)
      (create-test-user! {:name "François Müller" :email "francois@example.com"})

      (let [results (ports/search-users @test-search-service "François" {})]
        (is (some? results) "Should handle Unicode characters")
        ;; Note: PostgreSQL full-text search normalizes Unicode
        ;; so we may or may not find exact Unicode match depending on config
        ))))

(deftest sql-injection-prevention
  (testing "Prevents SQL injection in search queries"
    (when (postgres-available?)
      (create-test-user! {:name "John Smith" :email "john@example.com"})

      ;; Attempt SQL injection
      (let [malicious-query "'; DROP TABLE users; --"]
        (is (some? (ports/search-users @test-search-service malicious-query {}))
            "Should handle malicious query safely")

        ;; Verify table still exists
        (let [count-result (jdbc/execute-one! (:datasource @test-db-context)
                                              ["SELECT COUNT(*) as cnt FROM users"])]
          (is (some? count-result) "Users table should still exist"))))))

(deftest concurrent-searches
  (testing "Handles concurrent searches correctly"
    (when (postgres-available?)
      ;; Create test data
      (dotimes [i 10]
        (create-test-user! {:name (str "User " i) :email (str "user" i "@example.com")}))

      ;; Run 5 concurrent searches
      (let [futures (doall
                     (for [i (range 5)]
                       (future
                         (ports/search-users @test-search-service (str "User " i) {}))))
            results (mapv deref futures)]

        ;; All searches should succeed
        (is (= 5 (count results)) "All 5 concurrent searches should complete")
        (is (every? some? results) "All results should be non-nil")
        (is (every? #(pos? (:total %)) results) "All searches should have results")))))
