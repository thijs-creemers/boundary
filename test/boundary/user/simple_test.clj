(ns boundary.user.simple-test
  "Simple tests to verify test infrastructure works"
  (:require [boundary.user.core.user :as user-core]
            [clojure.test :refer [deftest testing is]]
            [support.validation-helpers :as vh])
  (:import [java.util UUID]))

;; Tag this namespace for Kaocha metadata-based filters
(alter-meta! *ns* assoc :user true)

(deftest test-validate-user-creation-request
  (testing "Valid user creation request"
    (let [user-data {:email "test@example.com"
                     :name "Test User"
                     :role :user
                     :tenant-id (UUID/randomUUID)
                     :password "test-password-123"}
          result (user-core/validate-user-creation-request user-data vh/test-validation-config)]
      (is (:valid? result))
      (is (= user-data (:data result))))))

(deftest test-can-delete-user
  (testing "Regular user can be deleted"
    (let [user {:email "test@example.com" :role :user}
          result (user-core/can-delete-user? user)]
      (is (:allowed? result))))

  (testing "System user cannot be deleted"
    (let [system-user {:email "system@example.com" :role :user}
          result (user-core/can-delete-user? system-user)]
      (is (not (:allowed? result)))
      (is (= :system-user (:reason result))))))

(deftest test-basic-functions-exist
  (testing "Core functions exist and can be called"
    (is (fn? user-core/validate-user-creation-request))
    (is (fn? user-core/can-delete-user?))
    (is (fn? user-core/prepare-user-for-soft-deletion))))
