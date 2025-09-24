(ns boundary.user.schema-registry-test
  (:require
    [clojure.test :refer :all]
    [boundary.user.schema :as schema]
    [malli.core :as m]))

(deftest schema-registry-structure-test
  (testing "registry has expected top-level categories"
    (is (map? schema/schema-registry))
    (is (contains? schema/schema-registry :domain-entities))
    (is (contains? schema/schema-registry :api-requests))
    (is (contains? schema/schema-registry :api-responses))
    (is (contains? schema/schema-registry :cli-arguments)))
  
  (testing "domain-entities category contains expected schemas"
    (let [domain-entities (:domain-entities schema/schema-registry)]
      (is (map? domain-entities))
      (is (contains? domain-entities :user))
      (is (contains? domain-entities :user-preferences))
      (is (contains? domain-entities :user-session))
      (is (contains? domain-entities :user-profile))))
  
  (testing "api-requests category contains expected schemas"
    (let [api-requests (:api-requests schema/schema-registry)]
      (is (map? api-requests))
      (is (contains? api-requests :create-user))
      (is (contains? api-requests :update-user))
      (is (contains? api-requests :change-password))
      (is (contains? api-requests :reset-password))
      (is (contains? api-requests :complete-password-reset))
      (is (contains? api-requests :login))))
  
  (testing "api-responses category contains expected schemas"
    (let [api-responses (:api-responses schema/schema-registry)]
      (is (map? api-responses))
      (is (contains? api-responses :user))
      (is (contains? api-responses :user-profile))
      (is (contains? api-responses :login))
      (is (contains? api-responses :paginated-users))))
  
  (testing "cli-arguments category contains expected schemas"
    (let [cli-arguments (:cli-arguments schema/schema-registry)]
      (is (map? cli-arguments))
      (is (contains? cli-arguments :create-user))
      (is (contains? cli-arguments :update-user))
      (is (contains? cli-arguments :list-users))
      (is (contains? cli-arguments :login)))))

(deftest schema-registry-content-test
  (testing "domain entities are valid malli schemas"
    (let [domain-entities (:domain-entities schema/schema-registry)]
      (doseq [[key schema-def] domain-entities]
        (is (m/schema? schema-def) 
            (str "Domain entity schema " key " should be a valid malli schema"))
        (is (some? (m/form schema-def))
            (str "Domain entity schema " key " should have a valid form")))))
  
  (testing "api request schemas are valid malli schemas"
    (let [api-requests (:api-requests schema/schema-registry)]
      (doseq [[key schema-def] api-requests]
        (is (m/schema? schema-def)
            (str "API request schema " key " should be a valid malli schema"))
        (is (some? (m/form schema-def))
            (str "API request schema " key " should have a valid form")))))
  
  (testing "api response schemas are valid malli schemas"
    (let [api-responses (:api-responses schema/schema-registry)]
      (doseq [[key schema-def] api-responses]
        (is (m/schema? schema-def)
            (str "API response schema " key " should be a valid malli schema"))
        (is (some? (m/form schema-def))
            (str "API response schema " key " should have a valid form")))))
  
  (testing "cli argument schemas are valid malli schemas"
    (let [cli-arguments (:cli-arguments schema/schema-registry)]
      (doseq [[key schema-def] cli-arguments]
        (is (m/schema? schema-def)
            (str "CLI argument schema " key " should be a valid malli schema"))
        (is (some? (m/form schema-def))
            (str "CLI argument schema " key " should have a valid form"))))))

(deftest get-schema-function-test
  (testing "retrieves schemas by category and name"
    (is (= schema/User (schema/get-schema :domain-entities :user)))
    (is (= schema/UserPreferences (schema/get-schema :domain-entities :user-preferences)))
    (is (= schema/CreateUserRequest (schema/get-schema :api-requests :create-user)))
    (is (= schema/UserResponse (schema/get-schema :api-responses :user)))
    (is (= schema/CreateUserCLIArgs (schema/get-schema :cli-arguments :create-user))))
  
  (testing "returns nil for non-existent category"
    (is (nil? (schema/get-schema :non-existent :user)))
    (is (nil? (schema/get-schema :fake-category :anything))))
  
  (testing "returns nil for non-existent schema in valid category"
    (is (nil? (schema/get-schema :domain-entities :non-existent)))
    (is (nil? (schema/get-schema :api-requests :fake-request))))
  
  (testing "retrieved schemas are usable for validation"
    (let [user-schema (schema/get-schema :domain-entities :user)
          create-request-schema (schema/get-schema :api-requests :create-user)]
      
      ;; Generate test data and validate
      (let [user-data (schema/generate-user)
            create-request-data (schema/generate-create-user-request)]
        (is (m/validate user-schema user-data))
        (is (m/validate create-request-schema create-request-data))))))

(deftest list-schemas-function-test
  (testing "lists schemas in domain-entities category"
    (let [schemas (schema/list-schemas :domain-entities)]
      (is (set? (set schemas))) ; Can be converted to set
      (is (contains? (set schemas) :user))
      (is (contains? (set schemas) :user-preferences))
      (is (contains? (set schemas) :user-session))
      (is (contains? (set schemas) :user-profile))))
  
  (testing "lists schemas in api-requests category"
    (let [schemas (schema/list-schemas :api-requests)]
      (is (contains? (set schemas) :create-user))
      (is (contains? (set schemas) :update-user))
      (is (contains? (set schemas) :login))))
  
  (testing "lists schemas in api-responses category"
    (let [schemas (schema/list-schemas :api-responses)]
      (is (contains? (set schemas) :user))
      (is (contains? (set schemas) :login))
      (is (contains? (set schemas) :paginated-users))))
  
  (testing "lists schemas in cli-arguments category"
    (let [schemas (schema/list-schemas :cli-arguments)]
      (is (contains? (set schemas) :create-user))
      (is (contains? (set schemas) :list-users))
      (is (contains? (set schemas) :login))))
  
  (testing "returns empty collection for non-existent category"
    (let [schemas (schema/list-schemas :non-existent)]
      (is (empty? schemas)))))

(deftest schema-registry-consistency-test
  (testing "all schemas in registry are accessible via get-schema"
    (doseq [[category schemas] schema/schema-registry]
      (doseq [schema-name (keys schemas)]
        (let [retrieved-schema (schema/get-schema category schema-name)]
          (is (some? retrieved-schema)
              (str "Schema " schema-name " in category " category " should be retrievable"))
          (is (= (get schemas schema-name) retrieved-schema)
              (str "Retrieved schema should match registry entry for " category "/" schema-name))))))
  
  (testing "list-schemas returns all schema names in each category"
    (doseq [[category schemas] schema/schema-registry]
      (let [listed-names (set (schema/list-schemas category))
            registry-names (set (keys schemas))]
        (is (= registry-names listed-names)
            (str "Listed schema names should match registry for category " category)))))
  
  (testing "schema references are consistent"
    ;; User profile should be a merge of User and UserPreferences
    (let [user-profile (schema/get-schema :domain-entities :user-profile)
          user-schema (schema/get-schema :domain-entities :user)
          user-preferences (schema/get-schema :domain-entities :user-preferences)]
      
      ;; Test that user profile validates data that combines user and preferences
      (let [combined-data (merge (schema/generate-user) (schema/generate-user-preferences))]
        (is (m/validate user-profile combined-data)))))
  
  (testing "registry maintains referential integrity"
    ;; All referenced schemas should exist
    (is (some? schema/User))
    (is (some? schema/UserPreferences))
    (is (some? schema/UserSession))
    (is (some? schema/UserProfile))
    (is (some? schema/CreateUserRequest))
    (is (some? schema/UpdateUserRequest))
    (is (some? schema/LoginRequest))
    (is (some? schema/UserResponse))
    (is (some? schema/LoginResponse))
    (is (some? schema/PaginatedUsersResponse))))

(deftest schema-registry-practical-usage-test
  (testing "registry supports dynamic schema selection"
    (let [request-type :create-user
          schema (schema/get-schema :api-requests request-type)
          test-data (schema/generate-create-user-request)]
      
      (is (some? schema))
      (is (m/validate schema test-data))))
  
  (testing "registry supports schema enumeration for tooling"
    (let [all-categories (keys schema/schema-registry)]
      (doseq [category all-categories]
        (let [schema-names (schema/list-schemas category)]
          (is (sequential? schema-names))
          (is (every? keyword? schema-names))))))
  
  (testing "registry schemas work with transformation pipelines"
    (let [create-schema (schema/get-schema :api-requests :create-user)
          response-schema (schema/get-schema :api-responses :user)
          test-request (schema/generate-create-user-request)]
      
      ;; Validate request
      (is (m/validate create-schema test-request))
      
      ;; Transform to user entity (simulate business logic)
      (let [user-entity (merge (schema/generate-user) 
                              (select-keys test-request [:email :name :role :active]))
            response-data (schema/user-entity->response user-entity)]
        
        ;; Validate response
        (is (m/validate response-schema response-data)))))
  
  (testing "registry supports schema versioning concept"
    ;; While not implemented yet, the structure supports future versioning
    (let [registry schema/schema-registry]
      (is (every? map? (vals registry))) ; Categories are maps
      (is (every? #(every? keyword? (keys %)) (vals registry))) ; Schema names are keywords
      
      ;; This structure could support versions like:
      ;; {:domain-entities {:v1 {:user schema/User} :v2 {:user schema/UserV2}}}
      ;; But for now we test the current flat structure works
      )))

(deftest schema-registry-error-handling-test
  (testing "gracefully handles invalid inputs to get-schema"
    (is (nil? (schema/get-schema nil :user)))
    (is (nil? (schema/get-schema :domain-entities nil)))
    (is (nil? (schema/get-schema nil nil)))
    (is (nil? (schema/get-schema "string-category" :user))) ; Wrong type
    (is (nil? (schema/get-schema :domain-entities "string-name")))) ; Wrong type
  
  (testing "gracefully handles invalid inputs to list-schemas"
    (is (empty? (schema/list-schemas nil)))
    (is (empty? (schema/list-schemas "string-category"))) ; Wrong type
    (is (empty? (schema/list-schemas :non-existent))))
  
  (testing "registry structure is immutable"
    ;; Ensure the registry can't be accidentally modified
    (let [original-registry schema/schema-registry]
      ;; This should not modify the original registry
      (assoc original-registry :new-category {})
      (is (= original-registry schema/schema-registry)))))