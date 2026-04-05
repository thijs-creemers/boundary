(ns boundary.test-support.shell.reset-test
  "Contract test for the H2 truncate helper used by the Playwright e2e suite.

   Uses the same direct H2 bootstrap pattern as
   boundary.user.shell.audit-repository-test: an in-memory HikariCP
   datasource in PostgreSQL compatibility mode, with minimal tables
   created inline."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.test-support.shell.reset :as sut]
            [boundary.platform.shell.adapters.database.h2.core :as h2]
            [boundary.observability.errors.shell.adapters.no-op :as noop-errors]
            [boundary.observability.logging.shell.adapters.no-op :as noop-logging]
            [boundary.observability.metrics.shell.adapters.no-op :as noop-metrics]
            [boundary.tenant.shell.persistence :as tenant-persistence]
            [boundary.tenant.shell.service :as tenant-service]
            [boundary.user.shell.persistence :as user-persistence]
            [boundary.user.shell.service :as user-service]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.util UUID)))

(def ^:dynamic *datasource* nil)

(defn- create-tables! [ds]
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS tenants (
                        id VARCHAR(36) PRIMARY KEY,
                        slug VARCHAR(100) NOT NULL,
                        name VARCHAR(255) NOT NULL,
                        status VARCHAR(50) NOT NULL)"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS auth_users (
                        id VARCHAR(36) PRIMARY KEY,
                        email VARCHAR(255) NOT NULL)"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS users (
                        id VARCHAR(36) PRIMARY KEY,
                        email VARCHAR(255) NOT NULL,
                        name VARCHAR(255))"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS user_sessions (
                        id VARCHAR(36) PRIMARY KEY,
                        user_id VARCHAR(36))"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS user_audit_log (
                        id VARCHAR(36) PRIMARY KEY,
                        action TEXT)"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS tenant_memberships (
                        id VARCHAR(36) PRIMARY KEY,
                        tenant_id VARCHAR(36),
                        user_id VARCHAR(36))"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS tenant_member_invites (
                        id VARCHAR(36) PRIMARY KEY,
                        tenant_id VARCHAR(36),
                        email VARCHAR(255))"]))

(defn- with-h2 [f]
  (let [^HikariDataSource ds
        (connection/->pool
         HikariDataSource
         {:jdbcUrl (str "jdbc:h2:mem:test_support_reset_" (UUID/randomUUID)
                        ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
          :username "sa"
          :password ""})]
    (try
      (create-tables! ds)
      (binding [*datasource* ds]
        (f))
      (finally
        (.close ds)))))

(use-fixtures :each with-h2)

(defn- count-rows [ds table]
  (let [row (jdbc/execute-one! ds [(str "SELECT COUNT(*) AS c FROM " table)])]
    ;; H2 returns a qualified keyword like :PUBLIC/c (case depends on settings).
    ;; Grab the single value regardless of key name.
    (long (first (vals row)))))

;; ---------------------------------------------------------------------------
;; Contract test: seed-baseline! against production services
;; ---------------------------------------------------------------------------
;;
;; This fixture spins up a fresh HikariCP datasource + H2 DB, then lets the
;; real user and tenant persistence layers create their own tables via their
;; production initialize-*-schema! helpers. seed-baseline! is then exercised
;; against the real production services (UserService, TenantService) so that
;; any schema drift between the seed spec and the production write path shows
;; up as a test failure.

(defn- build-services [ds]
  (let [db-ctx {:datasource ds :adapter (h2/new-adapter)}
        _ (user-persistence/initialize-user-schema! db-ctx)
        _ (tenant-persistence/initialize-tenant-schema! db-ctx)
        user-repo (user-persistence/create-user-repository db-ctx)
        session-repo (user-persistence/create-session-repository db-ctx)
        audit-repo (user-persistence/create-audit-repository db-ctx
                                                             {:default-limit 20 :max-limit 100})
        user-svc (user-service/create-user-service user-repo session-repo audit-repo {} nil)
        logger (noop-logging/create-logging-component {})
        metrics (noop-metrics/create-metrics-emitter {})
        errors (noop-errors/create-error-reporting-component)
        tenant-repo (tenant-persistence/create-tenant-repository db-ctx logger errors)
        tenant-svc (tenant-service/create-tenant-service tenant-repo {} logger metrics errors)]
    {:user-service user-svc
     :tenant-service tenant-svc}))

(deftest ^:contract truncate-all!-removes-all-rows-test
  (testing "truncate-all! empties a populated tenants table"
    (jdbc/execute! *datasource*
                   ["INSERT INTO tenants (id, slug, name, status)
                     VALUES (?, ?, ?, ?)"
                    (str (UUID/randomUUID)) "acme" "Acme" "active"])
    (is (= 1 (count-rows *datasource* "tenants"))
        "precondition: row was inserted")

    (sut/truncate-all! *datasource*)

    (is (zero? (count-rows *datasource* "tenants"))
        "truncate-all! removed the row from tenants")))

(defn- with-fresh-h2
  "Create a pristine H2 datasource, run `f` with it, then close.
   Unlike `with-h2`, this does NOT pre-create the minimal ad-hoc tables,
   so the production `initialize-*-schema!` helpers can create the real
   tables without conflicting with pre-existing stubs."
  [f]
  (let [^HikariDataSource ds
        (connection/->pool
         HikariDataSource
         {:jdbcUrl (str "jdbc:h2:mem:test_support_seed_" (UUID/randomUUID)
                        ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
          :username "sa"
          :password ""})]
    (try
      (f ds)
      (finally
        (.close ds)))))

(deftest ^:contract seed-baseline!-creates-entities-test
  (testing "seed-baseline! persists baseline entities via production services"
    (with-fresh-h2
      (fn [ds]
        (let [services (build-services ds)
              _ (sut/truncate-all! ds)
              {:keys [tenant admin user] :as result} (sut/seed-baseline! services)]
          (testing "tenant created with an id"
            (is (some? (:id tenant)) "tenant has an :id")
            (is (= "acme" (:slug tenant))))
          (testing "admin user created with plaintext password re-attached"
            (is (= "admin@acme.test" (:email admin)))
            (is (= :admin (:role admin)))
            (is (string? (:password admin)) ":password re-attached for test consumers")
            (is (not (contains? admin :password-hash)) ":password-hash must not be exposed"))
          (testing "regular user created with plaintext password re-attached"
            (is (= "user@acme.test" (:email user)))
            (is (= :user (:role user)))
            (is (string? (:password user)) ":password re-attached for test consumers")
            (is (not (contains? user :password-hash)) ":password-hash must not be exposed"))
          (testing "exactly two user rows were written by the production service"
            (is (= 2 (count-rows ds "users"))))
          (testing "return map has tenant/admin/user keys"
            (is (= #{:tenant :admin :user} (set (keys result))))))))))
