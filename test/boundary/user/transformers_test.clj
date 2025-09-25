(ns boundary.user.transformers-test
  (:require
   [clojure.test :refer :all]
   [boundary.user.schema :as schema]
   [malli.core :as m]))

(def sample-user-entity
  {:id (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
   :email "user@example.com"
   :name "Test User"
   :role :admin
   :active true
   :login-count 42
   :last-login "2023-12-25T14:30:00Z"
   :tenant-id (java.util.UUID/fromString "987fcdeb-51a2-43d7-b123-456789abcdef")
   :avatar-url "https://example.com/avatar.jpg"
   :created-at "2023-01-01T12:00:00Z"
   :updated-at "2023-12-20T10:15:30Z"
   :date-format :us
   :time-format :12h})

(deftest user-request-transformer-test
  (testing "transforms camelCase to kebab-case keys"
    (let [input {:email "test@example.com"
                 :name "Test User"
                 :tenantId "123e4567-e89b-12d3-a456-426614174000"
                 :sendWelcome true}
          result (m/decode schema/CreateUserRequest input schema/user-request-transformer)]
      (is (= "test@example.com" (:email result)))
      (is (= "Test User" (:name result)))
      (is (contains? result :tenant-id))
      (is (not (contains? result :tenantId)))
      (is (contains? result :send-welcome))
      (is (not (contains? result :sendWelcome)))))

  (testing "transforms string enums to keywords"
    (let [input {:email "test@example.com"
                 :name "Test User"
                 :role "admin"
                 :tenantId "123e4567-e89b-12d3-a456-426614174000"}
          result (m/decode schema/CreateUserRequest input schema/user-request-transformer)]
      (is (= :admin (:role result)))))

  (testing "transforms boolean strings"
    (let [input {:email "test@example.com"
                 :name "Test User"
                 :role "user"
                 :active "true"
                 :sendWelcome "false"
                 :tenantId "123e4567-e89b-12d3-a456-426614174000"}
          result (m/decode schema/CreateUserRequest input schema/user-request-transformer)]
      (is (= true (:active result)))
      (is (= false (:send-welcome result)))))

  (testing "handles various boolean string formats"
    (let [test-cases [["true" true] ["false" false] ["1" true] ["0" false]]
          base-input {:email "test@example.com"
                      :name "Test User"
                      :role "user"
                      :tenantId "123e4567-e89b-12d3-a456-426614174000"}]
      (doseq [[bool-str expected] test-cases]
        (let [input (assoc base-input :active bool-str)
              result (m/decode schema/CreateUserRequest input schema/user-request-transformer)]
          (is (= expected (:active result))
              (str "Failed for boolean string: " bool-str))))))

  (testing "leaves unrecognized boolean strings unchanged"
    (let [input {:email "test@example.com"
                 :name "Test User"
                 :role "user"
                 :active "maybe"
                 :tenantId "123e4567-e89b-12d3-a456-426614174000"}
          result (m/decode schema/CreateUserRequest input schema/user-request-transformer)]
      (is (= "maybe" (:active result)))))

  (testing "strips extra keys"
    (let [input {:email "test@example.com"
                 :name "Test User"
                 :role "user"
                 :unknown-field "should be removed"
                 :tenantId "123e4567-e89b-12d3-a456-426614174000"}
          result (m/decode schema/CreateUserRequest input schema/user-request-transformer)]
      (is (not (contains? result :unknown-field)))))

  (testing "is idempotent"
    (let [input {:email "test@example.com"
                 :name "Test User"
                 :role :user
                 :active true
                 :tenant-id (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174000")}
          first-transform (m/decode schema/CreateUserRequest input schema/user-request-transformer)
          second-transform (m/decode schema/CreateUserRequest first-transform schema/user-request-transformer)]
      (is (= first-transform second-transform)))))

(deftest user-response-transformer-test
  (testing "transforms kebab-case to camelCase keys"
    (let [result (schema/user-entity->response sample-user-entity)]
      (is (contains? result :tenantId))
      (is (not (contains? result :tenant-id)))
      (is (contains? result :createdAt))
      (is (not (contains? result :created-at)))
      (is (contains? result :updatedAt))
      (is (not (contains? result :updated-at)))
      (is (contains? result :lastLogin))
      (is (not (contains? result :last-login)))
      (is (contains? result :loginCount))
      (is (not (contains? result :login-count)))
      (is (contains? result :avatarUrl))
      (is (not (contains? result :avatar-url)))))

  (testing "converts UUIDs to strings"
    (let [result (schema/user-entity->response sample-user-entity)]
      (is (string? (:id result)))
      (is (= "123e4567-e89b-12d3-a456-426614174000" (:id result)))
      (is (string? (:tenantId result)))
      (is (= "987fcdeb-51a2-43d7-b123-456789abcdef" (:tenantId result)))))

  (testing "converts instants to ISO strings"
    (let [result (schema/user-entity->response sample-user-entity)]
      (is (string? (:createdAt result)))
      (is (= "2023-01-01T12:00:00Z" (:createdAt result)))
      (is (string? (:updatedAt result)))
      (is (= "2023-12-20T10:15:30Z" (:updatedAt result)))
      (is (string? (:lastLogin result)))
      (is (= "2023-12-25T14:30:00Z" (:lastLogin result)))))

  (testing "converts enum keywords to strings"
    (let [result (schema/user-entity->response sample-user-entity)]
      (is (string? (:role result)))
      (is (= "admin" (:role result)))))

  (testing "handles nil optional fields properly"
    (let [user-with-nils (assoc sample-user-entity
                                :updated-at nil
                                :last-login nil
                                :avatar-url nil)
          result (schema/user-entity->response user-with-nils)]
      (is (nil? (:updatedAt result)))
      (is (nil? (:lastLogin result)))
      (is (nil? (:avatarUrl result)))))

  (testing "preserves primitive values"
    (let [result (schema/user-entity->response sample-user-entity)]
      (is (= "user@example.com" (:email result)))
      (is (= "Test User" (:name result)))
      (is (= true (:active result)))
      (is (= 42 (:loginCount result))))))

(deftest user-profile-response-transformer-test
  (testing "transforms user profile with preferences"
    (let [user-profile (merge sample-user-entity
                              {:notifications {:email true :push false :sms true}
                               :theme :dark
                               :language "en"
                               :timezone "America/New_York"})
          result (schema/user-profile-entity->response user-profile)]
      (is (contains? result :notifications))
      (is (= {:email true :push false :sms true} (:notifications result)))
      (is (string? (:theme result)))
      (is (= "dark" (:theme result)))
      (is (= "en" (:language result)))
      (is (= "America/New_York" (:timezone result))))))

(deftest login-response-transformer-test
  (testing "transforms login response with user and token"
    (let [login-response {:token "abc123def456"
                          :user sample-user-entity
                          :expiresAt "2024-01-01T12:00:00Z"}
          transformed-user (schema/user-entity->response (:user login-response))
          result (assoc login-response :user transformed-user)]
      (is (= "abc123def456" (:token result)))
      (is (map? (:user result)))
      (is (= "123e4567-e89b-12d3-a456-426614174000" (get-in result [:user :id])))
      (is (string? (:expiresAt result)))
      (is (= "2024-01-01T12:00:00Z" (:expiresAt result))))))

(deftest paginated-users-response-test
  (testing "transforms paginated users response"
    (let [users [sample-user-entity (assoc sample-user-entity :id (java.util.UUID/randomUUID))]
          pagination-meta {:total 2 :limit 10 :offset 0 :page 1 :pages 1}
          result (schema/users->paginated-response users pagination-meta)]
      (is (= 2 (count (:data result))))
      (is (every? string? (map :id (:data result))))
      (is (= pagination-meta (:meta result))))))