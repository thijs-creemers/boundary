(ns boundary.shared.core.validation-test
  "Unit tests for boundary.shared.core.validation namespace."
  (:require [clojure.test :refer :all]
            [malli.core :as m]
            [malli.transform :as mt]
            [boundary.shared.core.validation :as validation]))

;; Test schemas for validation testing
(def TestUserSchema
  [:map
   [:id :uuid]
   [:name :string]
   [:age :int]
   [:active :boolean]
   [:role [:enum :admin :user]]])

(def TestCreateUserSchema
  [:map
   [:name :string]
   [:age [:or :int :string]]
   [:active [:or :boolean :string]]
   [:role [:enum :admin :user]]])

(deftest validate-with-transform-test
  (testing "Generic validation with transformation"
    (testing "Valid data passes validation"
      (let [valid-data {:name "John" :age "25" :active "true" :role :admin}
            result (validation/validate-with-transform 
                   TestCreateUserSchema 
                   valid-data 
                   mt/string-transformer)]
        (is (validation/validation-passed? result))
        (is (= "John" (get-in result [:data :name])))
        (is (= 25 (get-in result [:data :age])))
        (is (= true (get-in result [:data :active])))))
    
    (testing "Invalid data fails validation"
      (let [invalid-data {:name 123 :age "not-a-number" :role :invalid}
            result (validation/validate-with-transform 
                   TestCreateUserSchema 
                   invalid-data 
                   mt/string-transformer)]
        (is (not (validation/validation-passed? result)))
        (is (some? (validation/get-validation-errors result)))
        (is (nil? (validation/get-validated-data result)))))
    
    (testing "Transformation applied before validation"
      (let [string-data {:name "Jane" :age "30" :active "false" :role :user}
            result (validation/validate-with-transform 
                   TestCreateUserSchema 
                   string-data 
                   mt/string-transformer)]
        (is (validation/validation-passed? result))
        (is (= 30 (get-in result [:data :age])))
        (is (= false (get-in result [:data :active])))))
    
    (testing "Missing required fields"
      (let [incomplete-data {:name "John"}
            result (validation/validate-with-transform 
                   TestCreateUserSchema 
                   incomplete-data 
                   mt/string-transformer)]
        (is (not (validation/validation-passed? result)))
        (is (some? (validation/get-validation-errors result)))))
    
    (testing "No transformer (identity transformation)"
      (let [valid-data {:name "John" :age 25 :active true :role :admin}
            result (validation/validate-with-transform 
                   TestCreateUserSchema 
                   valid-data 
                   (mt/transformer))]
        (is (validation/validation-passed? result))
        (is (= valid-data (validation/get-validated-data result)))))))

(deftest validate-cli-args-test
  (testing "CLI argument validation"
    (testing "String CLI args converted to proper types"
      (let [cli-args {:name "John" :age "25" :active "true" :role "admin"}
            cli-transformer (mt/transformer
                            mt/string-transformer
                            {:name :cli-transform
                             :transformers
                             {:enum {:compile (fn [_schema _options] 
                                               (fn [value] 
                                                 (if (string? value) 
                                                   (keyword value) 
                                                   value)))}}})
            result (validation/validate-cli-args 
                   TestCreateUserSchema 
                   cli-args 
                   cli-transformer)]
        (is (validation/validation-passed? result))
        (is (= "John" (get-in result [:data :name])))
        (is (= 25 (get-in result [:data :age])))
        (is (= true (get-in result [:data :active])))
        (is (= :admin (get-in result [:data :role])))))
    
    (testing "Invalid CLI args fail validation"
      (let [invalid-cli-args {:name "" :age "not-a-number" :role "invalid-role"}
            result (validation/validate-cli-args 
                   TestCreateUserSchema 
                   invalid-cli-args 
                   mt/string-transformer)]
        (is (not (validation/validation-passed? result)))
        (is (some? (validation/get-validation-errors result)))))
    
    (testing "Empty CLI args"
      (let [empty-args {}
            result (validation/validate-cli-args 
                   TestCreateUserSchema 
                   empty-args 
                   mt/string-transformer)]
        (is (not (validation/validation-passed? result)))
        (is (some? (validation/get-validation-errors result)))))))

(deftest validate-request-test
  (testing "API request validation"
    (testing "Valid request data"
      (let [request-data {:name "John" :age 25 :active true :role :admin}
            result (validation/validate-request 
                   TestCreateUserSchema 
                   request-data 
                   (mt/transformer))]
        (is (validation/validation-passed? result))
        (is (= request-data (validation/get-validated-data result)))))
    
    (testing "Request with extra fields stripped"
      (let [request-data {:name "John" :age 25 :active true :role :admin :extra "field"}
            result (validation/validate-request 
                   TestCreateUserSchema 
                   request-data 
                   mt/strip-extra-keys-transformer)]
        (is (validation/validation-passed? result))
        (let [validated-data (validation/get-validated-data result)]
          (is (= "John" (:name validated-data)))
          (is (not (contains? validated-data :extra))))))
    
    (testing "Invalid request data"
      (let [invalid-request {:name 123 :age "invalid" :role :invalid-role}
            result (validation/validate-request 
                   TestCreateUserSchema 
                   invalid-request 
                   (mt/transformer))]
        (is (not (validation/validation-passed? result)))
        (is (some? (validation/get-validation-errors result)))))))

(deftest validation-result-utilities-test
  (testing "Validation result utility functions"
    (let [success-result {:valid? true :data {:name "John" :age 25}}
          failure-result {:valid? false :errors [{:path [:name] :message "required"}]}]
      
      (testing "validation-passed?"
        (is (validation/validation-passed? success-result))
        (is (not (validation/validation-passed? failure-result))))
      
      (testing "get-validation-errors"
        (is (nil? (validation/get-validation-errors success-result)))
        (is (some? (validation/get-validation-errors failure-result)))
        (is (vector? (validation/get-validation-errors failure-result))))
      
      (testing "get-validated-data"
        (is (= {:name "John" :age 25} (validation/get-validated-data success-result)))
        (is (nil? (validation/get-validated-data failure-result)))))))

(deftest edge-cases-test
  (testing "Edge cases and error conditions"
    (testing "Nil data"
      (let [result (validation/validate-with-transform 
                   TestCreateUserSchema 
                   nil 
                   mt/string-transformer)]
        (is (not (validation/validation-passed? result)))))
    
    (testing "Empty map"
      (let [result (validation/validate-with-transform 
                   TestCreateUserSchema 
                   {} 
                   mt/string-transformer)]
        (is (not (validation/validation-passed? result)))))
    
    (testing "Malformed validation result"
      (let [malformed-result {:invalid "result"}]
        (is (not (validation/validation-passed? malformed-result)))
        (is (nil? (validation/get-validation-errors malformed-result)))
        (is (nil? (validation/get-validated-data malformed-result)))))))

(deftest integration-test
  (testing "Integration scenarios"
    (testing "Multi-step validation workflow"
      (let [raw-cli-input {:name "Admin User" :age "35" :active "1" :role "admin"}
            ;; Step 1: CLI validation
            cli-transformer (mt/transformer
                            mt/string-transformer
                            {:transformers
                             {:boolean {:compile (fn [_schema _options]
                                                  (fn [value]
                                                    (cond
                                                      (= value "1") true
                                                      (= value "0") false
                                                      :else value)))}
                              :enum {:compile (fn [_schema _options]
                                             (fn [value]
                                               (if (string? value)
                                                 (keyword value)
                                                 value)))}}})
            cli-result (validation/validate-cli-args 
                       TestCreateUserSchema 
                       raw-cli-input 
                       cli-transformer)]
        
        (is (validation/validation-passed? cli-result))
        (let [validated-data (validation/get-validated-data cli-result)]
          (is (= "Admin User" (:name validated-data)))
          (is (= 35 (:age validated-data)))
          (is (= true (:active validated-data)))
          (is (= :admin (:role validated-data))))))))