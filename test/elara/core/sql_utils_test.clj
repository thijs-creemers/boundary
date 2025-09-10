(ns elara.core.sql-utils-test
  (:require [clojure.test :refer :all]
            [elara.core.sql-utils :as sql-utils]
            [elara.schema :refer [User]]))

(deftest test-generate-create-ddl
  (testing "DDL generation for table creation"
    (let [ddl (sql-utils/generate-create-ddl User)]
      (is (= ddl
             ["CREATE TABLE \"users\" (\"id\" INT PRIMARY KEY, \"email\" VARCHAR(255) NOT NULL, \"active\" BOOLEAN NOT NULL, \"prefs\" JSON DEFAULT ())"])))))

(deftest test-generate-find-by-id-query
  (testing "Query generation for finding by ID"
    (let [query (sql-utils/generate-find-by-id-query :users := :id 1)]
      (is (= query
             {:select [:*] :from [:users] :where [:= :id 1]})))))

(deftest test-generate-update-query
  (testing "Query generation for updating records"
    (let [query (sql-utils/generate-update-query :users := :id 1 {:email "test@example.com"})]
      (is (= query
             {:update :users
              :set {:email "test@example.com"}
              :where [:= :id 1]})))))

(deftest test-sanitize-field-name
  (testing "Sanitizing field names"
    (is (= (sql-utils/sanitize-field-name :active?) :active))
    (is (= (sql-utils/sanitize-field-name :email) :email))))

(deftest test-desanitize-field-name
  (testing "Desanitizing field names"
    (is (= (sql-utils/desanitize-field-name :active) :active?))
    (is (= (sql-utils/desanitize-field-name :email) :email?))))

(deftest test-map-db-to-schema
  (testing "Mapping database fields to schema fields"
    (let [db-data {:id 1 :email "test@example.com" :active true}
          schema  User]
      (is (= (sql-utils/map-db-to-schema db-data schema)
             {:id 1 :email "test@example.com" :active? true})))))

(deftest test-schema-to-table-name
  (testing "Converting schema to table name"
    (let [schema1 {:table :users :fields [:map [:id :int]]}
          schema2 {:table :products :fields [:map [:id :int]]}
          schema3 {:table :order_items :fields [:map [:id :int]]}]
      (is (= "users" (sql-utils/schema-to-table-name schema1)))
      (is (= "products" (sql-utils/schema-to-table-name schema2)))
      (is (= "order_items" (sql-utils/schema-to-table-name schema3))))))