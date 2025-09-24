(ns boundary.user.cli-transformer-test
  (:require
    [clojure.test :refer :all]
    [boundary.user.schema :as schema]
    [malli.core :as m]
    [malli.transform :as mt]))

(deftest cli-transformer-boolean-test
  (testing "transforms various boolean string formats"
    (let [test-cases [["true" true]
                      ["false" false]
                      ["1" true]
                      ["0" false]
                      ["yes" true]
                      ["no" false]
                      ["on" true]
                      ["off" false]
                      ["TRUE" true]
                      ["FALSE" false]]]
      (doseq [[input expected] test-cases]
        (let [result (m/decode :boolean input schema/cli-transformer)]
          (is (= expected result) 
              (str "Failed for boolean input: " input))))))

  (testing "leaves unrecognized boolean strings unchanged"
    (let [unrecognized ["maybe" "perhaps" "unknown" "2" "-1"]]
      (doseq [input unrecognized]
        (let [result (m/decode :boolean input schema/cli-transformer)]
          (is (= input result)
              (str "Should leave unchanged: " input))))))

  (testing "passes through actual booleans"
    (is (= true (m/decode :boolean true schema/cli-transformer)))
    (is (= false (m/decode :boolean false schema/cli-transformer))))

  (testing "leaves non-string/boolean values unchanged"
    (is (= 42 (m/decode :boolean 42 schema/cli-transformer)))
    (is (= nil (m/decode :boolean nil schema/cli-transformer)))))

(deftest cli-transformer-int-test
  (testing "transforms valid integer strings"
    (let [test-cases [["0" 0]
                      ["42" 42]
                      ["-15" -15]
                      ["999" 999]]]
      (doseq [[input expected] test-cases]
        (let [result (m/decode :int input schema/cli-transformer)]
          (is (= expected result)
              (str "Failed for int input: " input))))))

  (testing "leaves invalid integer strings unchanged"
    (let [invalid ["42.5" "abc" "123x" "x123" "" " 42 "]]
      (doseq [input invalid]
        (let [result (m/decode :int input schema/cli-transformer)]
          (is (= input result)
              (str "Should leave unchanged: " input))))))

  (testing "passes through actual integers"
    (is (= 42 (m/decode :int 42 schema/cli-transformer)))
    (is (= -15 (m/decode :int -15 schema/cli-transformer))))

  (testing "leaves non-string/int values unchanged"
    (is (= true (m/decode :int true schema/cli-transformer)))
    (is (= nil (m/decode :int nil schema/cli-transformer)))))

(deftest cli-transformer-uuid-test
  (testing "transforms valid UUID strings"
    (let [uuid-str "123e4567-e89b-12d3-a456-426614174000"
          result (m/decode :uuid uuid-str schema/cli-transformer)]
      (is (uuid? result))
      (is (= uuid-str (str result)))))

  (testing "leaves invalid UUID strings unchanged"
    (let [invalid ["not-a-uuid" "123-456" "" "123e4567-e89b-12d3-a456-42661417400" ; too short
                   "123e4567-e89b-12d3-a456-426614174000x" ; extra char
                   "123e4567e89b12d3a456426614174000"]] ; missing dashes
      (doseq [input invalid]
        (let [result (m/decode :uuid input schema/cli-transformer)]
          (is (= input result)
              (str "Should leave unchanged: " input))))))

  (testing "passes through actual UUIDs"
    (let [uuid (java.util.UUID/randomUUID)
          result (m/decode :uuid uuid schema/cli-transformer)]
      (is (= uuid result))))

  (testing "leaves non-string/uuid values unchanged"
    (is (= 42 (m/decode :uuid 42 schema/cli-transformer)))
    (is (= nil (m/decode :uuid nil schema/cli-transformer)))))

(deftest cli-transformer-enum-test
  (testing "transforms strings to keywords"
    (let [test-cases [["admin" :admin]
                      ["user" :user]
                      ["viewer" :viewer]
                      ["light" :light]
                      ["dark" :dark]]]
      (doseq [[input expected] test-cases]
        (let [result (m/decode :enum input schema/cli-transformer)]
          (is (= expected result)
              (str "Failed for enum input: " input))))))

  (testing "passes through existing keywords"
    (is (= :admin (m/decode :enum :admin schema/cli-transformer)))
    (is (= :user (m/decode :enum :user schema/cli-transformer))))

  (testing "leaves non-string/keyword values unchanged"
    (is (= 42 (m/decode :enum 42 schema/cli-transformer)))
    (is (= nil (m/decode :enum nil schema/cli-transformer)))))

(deftest cli-transformer-complex-schema-test
  (testing "transforms CLI create user args with mixed types"
    (let [cli-args {:email "cli@example.com"
                    :name "CLI User"
                    :role "admin"
                    :active "true"
                    :send-welcome "1"
                    :tenant-id "123e4567-e89b-12d3-a456-426614174000"}
          result (m/decode schema/CreateUserCLIArgs cli-args schema/cli-transformer)]
      (is (= "cli@example.com" (:email result)))
      (is (= "CLI User" (:name result)))
      (is (= :admin (:role result)))
      (is (= true (:active result)))
      (is (= true (:send-welcome result)))
      (is (uuid? (:tenant-id result)))))

  (testing "handles mixed valid and invalid transformations"
    (let [cli-args {:email "cli@example.com"
                    :name "CLI User"
                    :role "admin"
                    :active "maybe"
                    :send-welcome "yes"
                    :tenant-id "not-a-uuid"}
          result (m/decode schema/CreateUserCLIArgs cli-args schema/cli-transformer)]
      (is (= :admin (:role result)))
      (is (= "maybe" (:active result)))
      (is (= true (:send-welcome result)))
      (is (= "not-a-uuid" (:tenant-id result)))))

  (testing "transforms list users CLI args with limit and offset"
    (let [cli-args {:limit "25"
                    :offset "50"
                    :sort "name"
                    :filter-role "admin"
                    :filter-active "true"
                    :filter-email "user@"
                    :tenant-id "123e4567-e89b-12d3-a456-426614174000"}
          result (m/decode schema/ListUsersCLIArgs cli-args schema/cli-transformer)]
      (is (= 25 (:limit result)))
      (is (= 50 (:offset result)))
      (is (= "name" (:sort result)))
      (is (= :admin (:filter-role result)))
      (is (= true (:filter-active result)))
      (is (= "user@" (:filter-email result)))
      (is (uuid? (:tenant-id result)))))

  (testing "handles CLI args with invalid numeric values"
    (let [cli-args {:limit "not-a-number"
                    :offset "25.5"
                    :filter-active "false"}
          result (m/decode schema/ListUsersCLIArgs cli-args schema/cli-transformer)]
      (is (= "not-a-number" (:limit result)))
      (is (= "25.5" (:offset result)))
      (is (= false (:filter-active result))))))

(deftest validate-cli-args-integration-test
  (testing "successful validation with CLI transformations"
    (let [args {:email "test@example.com"
                :name "Test User"
                :role "user"
                :active "true"
                :tenant-id "123e4567-e89b-12d3-a456-426614174000"}
          result (schema/validate-cli-args schema/CreateUserCLIArgs args)]
      (is (:valid? result))
      (is (= :user (:role (:data result))))
      (is (= true (:active (:data result))))
      (is (uuid? (:tenant-id (:data result))))))

  (testing "validation failure on missing required field"
    (let [args {:name "Test User"
                :role "user"
                :active "true"}
          result (schema/validate-cli-args schema/CreateUserCLIArgs args)]
      (is (false? (:valid? result)))
      (is (some? (:errors result)))))

  (testing "validation success with partial data for update"
    (let [args {:id "123e4567-e89b-12d3-a456-426614174000"
                :name "Updated Name"
                :active "false"}
          result (schema/validate-cli-args schema/UpdateUserCLIArgs args)]
      (is (:valid? result))
      (is (= "Updated Name" (:name (:data result))))
      (is (= false (:active (:data result))))
      (is (uuid? (:id (:data result))))))

  (testing "validation with login CLI args"
    (let [args {:email "user@example.com"
                :password "secret123"
                :save-session "yes"}
          result (schema/validate-cli-args schema/LoginCLIArgs args)]
      (is (:valid? result))
      (is (= "user@example.com" (:email (:data result))))
      (is (= "secret123" (:password (:data result))))
      (is (= true (:save-session (:data result)))))))