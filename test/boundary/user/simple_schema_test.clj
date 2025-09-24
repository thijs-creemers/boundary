(ns boundary.user.simple-schema-test
  (:require
    [clojure.test :refer :all]
    [boundary.user.schema :as schema]
    [malli.core :as m]
    [clj-time.core :as time]
    [clj-time.coerce :as time-coerce]))

(def valid-user
  {:id (java.util.UUID/randomUUID)
   :email "test@example.com"
   :name "Test User"
   :role :user
   :active true
   :tenant-id (java.util.UUID/randomUUID)
   :created-at (time-coerce/to-string (time/now))})

(deftest basic-user-validation
  (testing "validates complete valid user"
    (is (schema/validate-user valid-user) "Schema validation should pass")))