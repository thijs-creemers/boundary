(ns boundary.user.shell.pagination-repository-test
  "Direct repository tests for pagination (bypassing service layer)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.user.ports :as ports]
            [boundary.user.shell.persistence :as user-persistence]
            [boundary.platform.core.pagination.pagination :as pagination]
            [boundary.platform.shell.adapters.database.h2.core :as h2]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import [java.time Instant]
           [java.util UUID]
           [com.zaxxer.hikari HikariDataSource]))

(def test-db-context (atom nil))
(def test-repository (atom nil))

(defn setup-test-db []
  (let [^HikariDataSource datasource (connection/->pool
                                      com.zaxxer.hikari.HikariDataSource
                                      {:jdbcUrl "jdbc:h2:mem:pagination-repo-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                                       :username "sa"
                                       :password ""})
        adapter (h2/new-adapter)
        db-ctx {:datasource datasource :adapter adapter}]
    (reset! test-db-context db-ctx)
    
    (jdbc/execute! datasource
                   ["CREATE TABLE IF NOT EXISTS users (
                       id UUID PRIMARY KEY,
                       email VARCHAR(255) NOT NULL UNIQUE,
                       name VARCHAR(255) NOT NULL,
                       password_hash VARCHAR(255),
                       role VARCHAR(50) NOT NULL,
                       active BOOLEAN NOT NULL DEFAULT true,
                       created_at TIMESTAMP NOT NULL,
                       updated_at TIMESTAMP NOT NULL,
                       deleted_at TIMESTAMP,
                       last_login TIMESTAMP,
                       login_count INTEGER NOT NULL DEFAULT 0,
                       failed_login_count INTEGER NOT NULL DEFAULT 0,
                       lockout_until TIMESTAMP,
                       mfa_enabled BOOLEAN NOT NULL DEFAULT false,
                       mfa_secret VARCHAR(255),
                       mfa_backup_codes TEXT
                     )"])
    
    (reset! test-repository (user-persistence/create-user-repository db-ctx))))

(defn teardown-test-db []
  (when-let [db-ctx @test-db-context]
    (when-let [^HikariDataSource datasource (:datasource db-ctx)]
      (.close datasource)))
  (reset! test-db-context nil)
  (reset! test-repository nil))

(use-fixtures :once (fn [f] (setup-test-db) (try (f) (finally (teardown-test-db)))))

(defn clean-test-database! []
  (when-let [db-ctx @test-db-context]
    (jdbc/execute! (:datasource db-ctx) ["DELETE FROM users"])))

(use-fixtures :each (fn [f] (clean-test-database!) (f)))

(defn create-test-user! [user-data]
  (let [db-ctx @test-db-context
        user (merge {:id (UUID/randomUUID)
                     :email (str "user-" (UUID/randomUUID) "@example.com")
                     :name "Test User"
                     :password-hash "$2a$12$test.hash"
                     :role "user"
                     :active true
                     :created-at (Instant/now)
                     :updated-at (Instant/now)
                     :login-count 0
                     :failed-login-count 0
                     :mfa-enabled false}
                    user-data)]
    (jdbc/execute-one!
     (:datasource db-ctx)
     ["INSERT INTO users (id, email, name, password_hash, role, active,
                          created_at, updated_at, login_count, failed_login_count, mfa_enabled)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
      (:id user) (:email user) (:name user) (:password-hash user) (:role user)
      (:active user) (:created-at user) (:updated-at user)
      (:login-count user) (:failed-login-count user) (:mfa-enabled user)])
    user))

(deftest repository-pagination-test
  (testing "Repository returns paginated results"
    (let [repo @test-repository]
      ;; Create 25 test users
      (dotimes [i 25]
        (create-test-user! {:email (format "user-%03d@example.com" i)
                           :name (format "User %03d" i)}))
      
      ;; Test default pagination
      (let [result (ports/find-users repo {})
            users (:users result)
            total (:total-count result)]
        
        (is (= 20 (count users)) "Should return 20 users (default limit)")
        (is (= 25 total) "Should have total count of 25"))
      
      ;; Test custom limit
      (let [result (ports/find-users repo {:limit 10})
            users (:users result)]
        
        (is (= 10 (count users)) "Should return 10 users (custom limit"))
      
      ;; Test with offset
      (let [result (ports/find-users repo {:limit 10 :offset 10})
            users (:users result)
            total (:total-count result)]
        
        (is (= 10 (count users)) "Should return 10 users from offset 10")
        (is (= 25 total) "Total should still be 25")))))
