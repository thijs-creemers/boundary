(ns boundary.user.schema-validation-test
  (:require
    [clojure.test :refer :all]
    [boundary.user.schema :as schema]
    [malli.core :as m]
    [java-time.api :as time]))

(def valid-user
  {:id (java.util.UUID/randomUUID)
   :email "test@example.com"
   :name "Test User"
   :role :user
   :active true
   :tenant-id (java.util.UUID/randomUUID)
   :created-at (time/instant)})

(def valid-user-preferences
  {:notifications {:email true :push false :sms true}
   :theme :light
   :language "en"
   :timezone "UTC"
   :date-format :iso
   :time-format :24h})

(deftest validate-user-test
  (testing "validates complete valid user"
    (is (true? (schema/validate-user valid-user))))

  (testing "fails on missing required field"
    (is (false? (schema/validate-user (dissoc valid-user :email)))))

  (testing "fails on type mismatch"
    (is (false? (schema/validate-user (assoc valid-user :active "true")))))

  (testing "passes with optional fields absent"
    (is (true? (schema/validate-user (dissoc valid-user :login-count)))))

  (testing "passes with optional fields present"
    (is (true? (schema/validate-user (assoc valid-user 
                                           :login-count 5
                                           :last-login (time/instant)
                                           :date-format :us
                                           :time-format :12h
                                           :avatar-url "https://example.com/avatar.jpg"
                                           :updated-at (time/instant)
                                           :deleted-at nil))))))

(deftest explain-user-test
  (testing "provides detailed validation errors"
    (let [invalid-user (dissoc valid-user :email)
          explanation (schema/explain-user invalid-user)]
      (is (some? explanation))
      (is (vector? (:errors explanation))))))

(deftest validate-create-user-request-test
  (testing "validates and transforms valid request"
    (let [request {:email "new@example.com"
                   :name "New User"
                   :role "admin"
                   :active true
                   :tenantId (str (java.util.UUID/randomUUID))
                   :sendWelcome true}
          result (schema/validate-create-user-request request)]
      (is (:valid? result))
      (is (= :admin (:role (:data result))))
      (is (= true (:send-welcome (:data result))))
      (is (contains? (:data result) :tenant-id))
      (is (not (contains? (:data result) :tenantId)))))

  (testing "fails on missing required field"
    (let [request {:name "Test User"
                   :role "user"
                   :active true
                   :tenantId (str (java.util.UUID/randomUUID))}
          result (schema/validate-create-user-request request)]
      (is (false? (:valid? result)))
      (is (some? (:errors result)))))

  (testing "transforms string boolean values"
    (let [request {:email "test@example.com"
                   :name "Test User"
                   :role "user"
                   :active "true"
                   :tenantId (str (java.util.UUID/randomUUID))
                   :sendWelcome "false"}
          result (schema/validate-create-user-request request)]
      (is (:valid? result))
      (is (= true (:active (:data result))))
      (is (= false (:send-welcome (:data result)))))))

(deftest validate-update-user-request-test
  (testing "validates partial update with only name"
    (let [request {:name "Updated Name"}
          result (schema/validate-update-user-request request)]
      (is (:valid? result))
      (is (= "Updated Name" (:name (:data result))))))

  (testing "validates all optional fields"
    (let [request {:name "Updated Name"
                   :role "admin"
                   :active false}
          result (schema/validate-update-user-request request)]
      (is (:valid? result))
      (is (= :admin (:role (:data result))))
      (is (= false (:active (:data result))))))

  (testing "passes with empty request"
    (let [request {}
          result (schema/validate-update-user-request request)]
      (is (:valid? result))))

  (testing "strips extra keys"
    (let [request {:name "Updated Name" :unknown-field "should be removed"}
          result (schema/validate-update-user-request request)]
      (is (:valid? result))
      (is (not (contains? (:data result) :unknown-field))))))

(deftest validate-login-request-test
  (testing "validates basic login request"
    (let [request {:email "user@example.com" :password "secret123"}
          result (schema/validate-login-request request)]
      (is (:valid? result))
      (is (= "user@example.com" (:email (:data result))))
      (is (= "secret123" (:password (:data result))))))

  (testing "transforms string boolean remember field"
    (let [request {:email "user@example.com" 
                   :password "secret123" 
                   :remember "true"}
          result (schema/validate-login-request request)]
      (is (:valid? result))
      (is (= true (:remember (:data result))))))

  (testing "fails on missing required field"
    (let [request {:email "user@example.com"}
          result (schema/validate-login-request request)]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

(deftest validate-cli-args-test
  (testing "validates CLI create user args with transformations"
    (let [args {:email "cli@example.com"
                :name "CLI User"
                :role "user"
                :active "true"
                :send-welcome "1"
                :tenant-id (str (java.util.UUID/randomUUID))}
          result (schema/validate-cli-args schema/CreateUserCLIArgs args)]
      (is (:valid? result))
      (is (= :user (:role (:data result))))
      (is (= true (:active (:data result))))
      (is (= true (:send-welcome (:data result))))
      (is (uuid? (:tenant-id (:data result))))))

  (testing "handles mixed valid and invalid transformations"
    (let [args {:email "cli@example.com"
                :name "CLI User"
                :role "user"
                :active "maybe"
                :send-welcome "yes"
                :tenant-id "not-a-uuid"}
          result (schema/validate-cli-args schema/CreateUserCLIArgs args)]
      (is (:valid? result))
      (is (= true (:send-welcome (:data result))))
      (is (= "maybe" (:active (:data result))))
      (is (= "not-a-uuid" (:tenant-id (:data result)))))))