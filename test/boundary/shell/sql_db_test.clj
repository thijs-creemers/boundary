(ns boundary.shell.sql-db-test
  (:require [clojure.test :refer :all]
            [boundary.shell.sql-db :as sql-db]
            [next.jdbc :as jdbc]))

(def test-db-spec {:dbtype "h2:mem" :dbname "testdb" :user "sa" :password ""})

(def User
  [:map
   {:closed true}
   [:id {:meta {:db/constraints #{:primary-key}}} :int]
   [:email :string]
   [:active? :boolean]])

(deftest test-transact-single-record
  (jdbc/with-transaction [tx test-db-spec]
                         (sql-db/create-table! tx User)
                         (let [result (sql-db/transact! tx User {:email "test@example.com" :active? true})]
                           (is (= 1 (count result)))
                           (is (= "test@example.com" (:email (first result)))))))

(deftest test-transact-multiple-records
  (jdbc/with-transaction [tx test-db-spec]
                         (sql-db/create-table! tx User)
                         (let [result (sql-db/transact! tx User [{:email "user1@example.com" :active? true}
                                                                 {:email "user2@example.com" :active? false}])]
                           (is (= 2 (count result)))
                           (is (= "user1@example.com" (:email (first result))))
                           (is (= "user2@example.com" (:email (second result)))))))

(deftest test-fetch-records
  (jdbc/with-transaction [tx test-db-spec]
                         (sql-db/create-table! tx User)
                         (sql-db/transact! tx User [{:email "user1@example.com" :active? true}
                                                    {:email "user2@example.com" :active? false}])
                         (let [result (sql-db/fetch tx User {:active? true})]
                           (is (= 1 (count result)))
                           (is (= "user1@example.com" (:email (first result)))))))

(deftest test-error-handling
  (jdbc/with-transaction [tx test-db-spec]
                         (sql-db/create-table! tx User)
                         (try
                           (sql-db/transact! tx User {:id 1 :email "duplicate@example.com" :active? true})
                           (sql-db/transact! tx User {:id 1 :email "duplicate@example.com" :active? false})
                           (is false "Expected an exception for duplicate key")
                           (catch Exception e
                             (is (= :duplicate-key (:type (ex-data e))))))))


(deftest test-fetch-error-handling
  (let [mock-db-spec {:dbtype "h2:mem" :dbname "testdb"}
        mock-schema [:map {:closed true} [:id :int] [:name :string]]]
    ;; Mock the `jdbc/execute!` function to throw an exception
    (with-redefs [jdbc/execute! (fn [_ _ _]
                                  (throw (Exception. "Simulated database error")))]
      (try
        (sql-db/fetch mock-db-spec mock-schema {:id 1})
        (is false "Expected an exception to be thrown")
        (catch Exception e
          (let [error-data (ex-data e)]
            (is (= :unknown-error (:type error-data)))
            (is (= "fetch" (:operation error-data)))
            (is (= "Simulated database error" (.getMessage (:original-error error-data))))))))))