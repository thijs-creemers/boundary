(ns boundary.test-support.shell.reset-test
  "Contract test for the H2 truncate helper used by the Playwright e2e suite.

   Uses the same direct H2 bootstrap pattern as
   boundary.user.shell.audit-repository-test: an in-memory HikariCP
   datasource in PostgreSQL compatibility mode, with minimal tables
   created inline."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.test-support.shell.reset :as sut]
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
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS users (
                        id VARCHAR(36) PRIMARY KEY,
                        email VARCHAR(255) NOT NULL,
                        name VARCHAR(255))"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS sessions (
                        id VARCHAR(36) PRIMARY KEY,
                        user_id VARCHAR(36))"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS audit_logs (
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
