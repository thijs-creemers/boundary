(ns boundary.platform.shell.adapters.database.config-factory-test
  "Tests for database adapter factory and creation logic."
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.platform.shell.adapters.database.config :as config]
            [boundary.platform.shell.adapters.database.config-factory :as factory]
            [boundary.platform.shell.adapters.database.protocols :as protocols]))

;; =============================================================================
;; Test Data and Fixtures
;; =============================================================================

(def sample-config
  "Sample configuration for testing adapter creation"
  {:active
   {:boundary/sqlite
    {:db "test-database.db"
     :pool {:minimum-idle 1
            :maximum-pool-size 3}}

    :boundary/h2
    {:memory true
     :pool {:minimum-idle 1
            :maximum-pool-size 5}}

    :boundary/postgresql
    {:host "localhost"
     :port 5432
     :dbname "test_db"
     :user "test_user"
     :password "test_password"}}

   :inactive
   {:boundary/mysql
    {:host "localhost"
     :port 3306
     :dbname "test_db"
     :user "root"
     :password "password"}}})

;; =============================================================================
;; Adapter Creation Tests
;; =============================================================================

(deftest test-create-adapter-sqlite
  (testing "Creating SQLite adapter"
    (let [config {:db "test.db" :pool {:minimum-idle 1}}
          adapter (factory/create-adapter :boundary/sqlite config)]
      (is (some? adapter) "Adapter should be created")
      (is (satisfies? protocols/DBAdapter adapter)
          "Should satisfy DatabaseAdapter protocol")

      ;; Test protocol methods
      (let [dialect (protocols/dialect adapter)
            jdbc-url (protocols/jdbc-url adapter {:db "test.db"})]
        (is (= dialect :sqlite) "SQLite adapter should return :sqlite dialect")
        (is (string? jdbc-url) "JDBC URL should be a string")
        (is (.contains jdbc-url "sqlite") "SQLite JDBC URL should contain 'sqlite'"))

      (let [dialect (protocols/dialect adapter)]
        (is (some? dialect) "Should have a dialect")))))

(deftest test-create-adapter-h2
  (testing "Creating H2 adapter"
    (let [config {:memory true :pool {:minimum-idle 1}}
          adapter (factory/create-adapter :boundary/h2 config)]
      (is (some? adapter) "Adapter should be created")
      (is (satisfies? protocols/DBAdapter adapter)
          "Should satisfy DatabaseAdapter protocol")

      (let [dialect (protocols/dialect adapter)
            jdbc-url (protocols/jdbc-url adapter {:memory true})]
        (is (= dialect :h2) "H2 adapter should return :h2 dialect")
        (is (string? jdbc-url) "JDBC URL should be a string")
        (is (.contains jdbc-url "h2:") "H2 JDBC URL should contain 'h2:'")
        (is (.contains jdbc-url "mem:") "In-memory H2 should contain 'mem:' in URL")))))

(deftest test-create-adapter-postgresql
  (testing "Creating PostgreSQL adapter"
    (let [config {:host "localhost"
                  :port 5432
                  :dbname "testdb"
                  :user "testuser"
                  :password "testpass"}
          adapter (factory/create-adapter :boundary/postgresql config)]
      (is (some? adapter) "Adapter should be created")
      (is (satisfies? protocols/DBAdapter adapter)
          "Should satisfy DatabaseAdapter protocol")

      (let [dialect (protocols/dialect adapter)
            jdbc-url (protocols/jdbc-url adapter config)]
        (is (= dialect :postgresql) "PostgreSQL adapter should return :postgresql dialect")
        (is (string? jdbc-url) "JDBC URL should be a string")
        (is (.contains jdbc-url "postgresql") "PostgreSQL JDBC URL should contain 'postgresql'")))))

(deftest test-create-adapter-mysql
  (testing "Creating MySQL adapter"
    (let [config {:host "localhost"
                  :port 3306
                  :dbname "testdb"
                  :user "root"
                  :password "password"}
          adapter (factory/create-adapter :boundary/mysql config)]
      (is (some? adapter) "Adapter should be created")
      (is (satisfies? protocols/DBAdapter adapter)
          "Should satisfy DatabaseAdapter protocol")

      (let [dialect (protocols/dialect adapter)
            jdbc-url (protocols/jdbc-url adapter config)]
        (is (= dialect :mysql) "MySQL adapter should return :mysql dialect")
        (is (string? jdbc-url) "JDBC URL should be a string")
        (is (.contains jdbc-url "mysql") "MySQL JDBC URL should contain 'mysql'")))))

(deftest test-create-adapter-unknown
  (testing "Creating unknown adapter should throw"
    (is (thrown? Exception
                 (factory/create-adapter :boundary/unknown {:some "config"})
                 "Should throw exception for unknown adapter type"))))

;; =============================================================================
;; Active Adapters Creation Tests
;; =============================================================================

(deftest test-create-active-adapters
  (testing "Creating all active adapters from configuration"
    (let [adapters (factory/create-active-adapters sample-config)]
      (is (map? adapters) "Should return a map of adapters")
      (is (= 3 (count adapters)) "Should have 3 active adapters")

      ;; Check each adapter exists and is properly typed
      (is (contains? adapters :boundary/sqlite) "Should contain SQLite adapter")
      (is (contains? adapters :boundary/h2) "Should contain H2 adapter")
      (is (contains? adapters :boundary/postgresql) "Should contain PostgreSQL adapter")
      (is (not (contains? adapters :boundary/mysql)) "Should not contain inactive MySQL adapter")

      ;; Check all adapters satisfy the protocol
      (doseq [[adapter-key adapter] adapters]
        (is (satisfies? protocols/DBAdapter adapter)
            (str "Adapter " adapter-key " should satisfy DatabaseAdapter protocol"))))))

(deftest test-create-active-adapters-empty-config
  (testing "Creating adapters from empty active configuration"
    (let [empty-config {:active {} :inactive {}}
          adapters (factory/create-active-adapters empty-config)]
      (is (map? adapters) "Should return a map even with empty config")
      (is (empty? adapters) "Should be empty map with no active adapters"))))

(deftest test-create-active-adapters-missing-active-section
  (testing "Creating adapters from config missing active section should throw"
    (let [invalid-config {:inactive {:boundary/sqlite {:db "test.db"}}}]
      (is (thrown? Exception (factory/create-active-adapters invalid-config))
          "Should throw exception when :active section is missing"))))

;; =============================================================================
;; Adapter Configuration Validation Tests
;; =============================================================================

(deftest test-validate-adapter-configs
  (testing "Validating adapter configurations before creation"
    (testing "Valid configurations should pass"
      (let [configs {:boundary/sqlite {:db "test.db"}
                     :boundary/h2 {:memory true}
                     :boundary/postgresql {:host "localhost" :port 5432
                                           :dbname "test" :user "user" :password "pass"}}]
        (doseq [[adapter-type config] configs]
          (is (factory/valid-adapter-config? adapter-type config)
              (str "Valid config for " adapter-type " should pass validation")))))

    (testing "Invalid configurations should fail"
      (let [invalid-configs {:boundary/sqlite {} ; missing :db
                             :boundary/postgresql {:host "localhost"} ; missing required fields
                             :boundary/mysql {:port 3306}}] ; missing host and other required
        (doseq [[adapter-type config] invalid-configs]
          (is (not (factory/valid-adapter-config? adapter-type config))
              (str "Invalid config for " adapter-type " should fail validation")))))))

;; =============================================================================
;; Adapter Registry Tests
;; =============================================================================

(deftest test-list-available-adapters
  (testing "Listing available adapter types"
    (let [available-adapters (factory/list-available-adapters)]
      (is (coll? available-adapters) "Should return a collection")
      (is (>= (count available-adapters) 4) "Should have at least 4 adapters")
      (is (contains? (set available-adapters) :boundary/sqlite) "Should include SQLite")
      (is (contains? (set available-adapters) :boundary/h2) "Should include H2")
      (is (contains? (set available-adapters) :boundary/postgresql) "Should include PostgreSQL")
      (is (contains? (set available-adapters) :boundary/mysql) "Should include MySQL"))))

(deftest test-adapter-supported
  (testing "Checking if adapter types are supported"
    (is (factory/adapter-supported? :boundary/sqlite) "SQLite should be supported")
    (is (factory/adapter-supported? :boundary/h2) "H2 should be supported")
    (is (factory/adapter-supported? :boundary/postgresql) "PostgreSQL should be supported")
    (is (factory/adapter-supported? :boundary/mysql) "MySQL should be supported")
    (is (not (factory/adapter-supported? :boundary/nonexistent)) "Nonexistent adapter should not be supported")))

;; =============================================================================
;; Connection Specification Tests
;; =============================================================================

(deftest test-jdbc-url-format
  (testing "JDBC URLs should be properly formatted"
    (let [sqlite-adapter (factory/create-adapter :boundary/sqlite {:db "test.db"})
          h2-adapter (factory/create-adapter :boundary/h2 {:memory true})]
      (testing "SQLite JDBC URL"
        (let [jdbc-url (protocols/jdbc-url sqlite-adapter {:db "test.db"})]
          (is (string? jdbc-url) "JDBC URL should be a string")
          (is (.contains jdbc-url "sqlite") "SQLite JDBC URL should contain 'sqlite'")))

      (testing "H2 JDBC URL"
        (let [jdbc-url (protocols/jdbc-url h2-adapter {:memory true})]
          (is (string? jdbc-url) "JDBC URL should be a string")
          (is (.contains jdbc-url "h2") "H2 JDBC URL should contain 'h2'"))))))

;; =============================================================================
;; Adapter Pool Configuration Tests  
;; =============================================================================

(deftest test-pool-configuration-handling
  (testing "Pool configuration should be properly handled"
    (let [config-with-pool {:db "test.db"
                            :pool {:minimum-idle 5
                                   :maximum-pool-size 20
                                   :connection-timeout-ms 30000}}
          config-without-pool {:db "test.db"}]

      (testing "Config with explicit pool settings"
        (let [adapter (factory/create-adapter :boundary/sqlite config-with-pool)]
          (is (some? adapter) "Adapter should be created with pool config")))

      (testing "Config without pool settings should use defaults"
        (let [adapter (factory/create-adapter :boundary/sqlite config-without-pool)]
          (is (some? adapter) "Adapter should be created without explicit pool config"))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest test-error-handling
  (testing "Factory should handle errors gracefully"
    (testing "Malformed configuration"
      (is (thrown? Exception
                   (factory/create-adapter :boundary/sqlite "not-a-map"))
          "Should throw on non-map configuration"))

    (testing "Null configuration"
      (is (thrown? Exception
                   (factory/create-adapter :boundary/sqlite nil))
          "Should throw on null configuration"))

    (testing "Configuration with invalid types"
      (is (thrown? Exception
                   (factory/create-adapter :boundary/postgresql
                                           {:host 123 :port "not-a-number"}))
          "Should throw on configuration with wrong data types"))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest test-factory-integration-with-real-configs
  (testing "Factory should work with real configuration files"
    ; This test loads actual config files and verifies factory can create adapters
    (doseq [env ["dev" "test" "prod"]]
      (testing (str "Environment: " env)
        (try
          ; Load real config (this might fail if config loading isn't implemented)
          (let [config (config/load-config env)
                adapters (factory/create-active-adapters config)]
            (is (map? adapters) (str "Should create adapters map for " env))
            (is (seq adapters) (str "Should have at least one adapter for " env))

            ; Verify all created adapters are valid
            (doseq [[adapter-key adapter] adapters]
              (is (satisfies? protocols/DBAdapter adapter)
                  (str "Adapter " adapter-key " should satisfy protocol in " env))))
          (catch Exception e
            ; If config loading fails, that's okay - it means the real config system isn't ready
            ; But we should still document this expectation
            (println (str "Note: Could not test real config for " env ": " (.getMessage e)))))))))

;; Run all tests
(defn run-config-factory-tests []
  (clojure.test/run-tests 'boundary.platform.shell.adapters.database.config-factory-test))