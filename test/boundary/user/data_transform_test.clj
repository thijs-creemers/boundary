(ns boundary.user.data-transform-test
  (:require
    [clojure.test :refer :all]
    [boundary.user.schema :as schema]
    [java-time.api :as time]))

(def sample-user-entity
  {:id (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
   :email "user@example.com"
   :name "Test User"
   :role :admin
   :active true
   :login-count 42
   :last-login (time/instant "2023-12-25T14:30:00Z")
   :tenant-id (java.util.UUID/fromString "987fcdeb-51a2-43d7-b123-456789abcdef")
   :avatar-url "https://example.com/avatar.jpg"
   :created-at (time/instant "2023-01-01T12:00:00Z")
   :updated-at (time/instant "2023-12-20T10:15:30Z")
   :date-format :us
   :time-format :12h})

(def sample-user-preferences
  {:notifications {:email true :push false :sms true}
   :theme :dark
   :language "en"
   :timezone "America/New_York"
   :date-format :iso
   :time-format :24h})

(deftest user-entity->response-test
  (testing "transforms user entity to API response format"
    (let [result (schema/user-entity->response sample-user-entity)]
      (is (string? (:id result)))
      (is (= "123e4567-e89b-12d3-a456-426614174000" (:id result)))
      (is (= "user@example.com" (:email result)))
      (is (= "Test User" (:name result)))
      (is (string? (:role result)))
      (is (= "admin" (:role result)))
      (is (= true (:active result)))
      (is (= 42 (:loginCount result)))
      (is (string? (:lastLogin result)))
      (is (= "2023-12-25T14:30:00Z" (:lastLogin result)))
      (is (string? (:tenantId result)))
      (is (= "987fcdeb-51a2-43d7-b123-456789abcdef" (:tenantId result)))
      (is (= "https://example.com/avatar.jpg" (:avatarUrl result)))
      (is (string? (:createdAt result)))
      (is (= "2023-01-01T12:00:00Z" (:createdAt result)))
      (is (string? (:updatedAt result)))
      (is (= "2023-12-20T10:15:30Z" (:updatedAt result)))))

  (testing "uses camelCase keys instead of kebab-case"
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

  (testing "handles nil optional fields"
    (let [user-with-nils (assoc sample-user-entity 
                                :updated-at nil 
                                :last-login nil 
                                :avatar-url nil
                                :login-count nil)
          result (schema/user-entity->response user-with-nils)]
      (is (nil? (:updatedAt result)))
      (is (nil? (:lastLogin result)))
      (is (nil? (:avatarUrl result)))
      (is (nil? (:loginCount result)))))

  (testing "minimal user entity with only required fields"
    (let [minimal-user {:id (java.util.UUID/randomUUID)
                        :email "minimal@example.com"
                        :name "Minimal User"
                        :role :user
                        :active true
                        :tenant-id (java.util.UUID/randomUUID)
                        :created-at (time/instant)}
          result (schema/user-entity->response minimal-user)]
      (is (string? (:id result)))
      (is (= "minimal@example.com" (:email result)))
      (is (= "Minimal User" (:name result)))
      (is (= "user" (:role result)))
      (is (= true (:active result)))
      (is (string? (:tenantId result)))
      (is (string? (:createdAt result))))))

(deftest user-profile-entity->response-test
  (testing "transforms user profile with preferences"
    (let [user-profile (merge sample-user-entity sample-user-preferences)
          result (schema/user-profile-entity->response user-profile)]
      (is (string? (:id result)))
      (is (= "user@example.com" (:email result)))
      (is (= "Test User" (:name result)))
      (is (map? (:notifications result)))
      (is (= {:email true :push false :sms true} (:notifications result)))
      (is (string? (:theme result)))
      (is (= "dark" (:theme result)))
      (is (= "en" (:language result)))
      (is (= "America/New_York" (:timezone result)))
      (is (string? (:dateFormat result)))
      (is (= "iso" (:dateFormat result)))
      (is (string? (:timeFormat result)))
      (is (= "24h" (:timeFormat result)))))

  (testing "preferences override user entity date/time formats"
    (let [user-profile (merge sample-user-entity sample-user-preferences)
          result (schema/user-profile-entity->response user-profile)]
      (is (= "iso" (:dateFormat result)))
      (is (= "24h" (:timeFormat result)))))

  (testing "user profile without preferences uses user entity formats"
    (let [user-without-prefs (dissoc sample-user-entity :notifications :theme :language :timezone)
          result (schema/user-profile-entity->response user-without-prefs)]
      (is (string? (:dateFormat result)))
      (is (= "us" (:dateFormat result)))
      (is (string? (:timeFormat result)))
      (is (= "12h" (:timeFormat result))))))

(deftest users->paginated-response-test
  (testing "transforms vector of users to paginated response"
    (let [user1 sample-user-entity
          user2 (assoc sample-user-entity 
                       :id (java.util.UUID/fromString "456e7890-a12b-34c5-d678-901234567890")
                       :email "user2@example.com"
                       :name "Second User")
          users [user1 user2]
          pagination-meta {:total 2 :limit 10 :offset 0 :page 1 :pages 1}
          result (schema/users->paginated-response users pagination-meta)]
      (is (vector? (:data result)))
      (is (= 2 (count (:data result))))
      (is (every? map? (:data result)))
      (is (every? string? (map :id (:data result))))
      (is (= "user@example.com" (-> result :data first :email)))
      (is (= "user2@example.com" (-> result :data second :email)))
      (is (= pagination-meta (:meta result)))))

  (testing "pagination meta is passed through unchanged"
    (let [users [sample-user-entity]
          pagination-meta {:total 50 :limit 20 :offset 40 :page 3 :pages 3}
          result (schema/users->paginated-response users pagination-meta)]
      (is (= pagination-meta (:meta result)))))

  (testing "empty users list"
    (let [users []
          pagination-meta {:total 0 :limit 10 :offset 0 :page 1 :pages 0}
          result (schema/users->paginated-response users pagination-meta)]
      (is (vector? (:data result)))
      (is (= 0 (count (:data result))))
      (is (= pagination-meta (:meta result)))))

  (testing "single user in paginated response"
    (let [users [sample-user-entity]
          pagination-meta {:total 1 :limit 10 :offset 0 :page 1 :pages 1}
          result (schema/users->paginated-response users pagination-meta)]
      (is (= 1 (count (:data result))))
      (is (string? (-> result :data first :id)))
      (is (= "user@example.com" (-> result :data first :email))))))

(deftest data-transformation-consistency-test
  (testing "user-entity->response produces valid UserResponse schema"
    (let [result (schema/user-entity->response sample-user-entity)]
      (is (schema/m/validate schema/UserResponse result))))

  (testing "user-profile-entity->response produces valid UserProfileResponse schema"
    (let [user-profile (merge sample-user-entity sample-user-preferences)
          result (schema/user-profile-entity->response user-profile)]
      (is (schema/m/validate schema/UserProfileResponse result))))

  (testing "users->paginated-response produces valid PaginatedUsersResponse schema"
    (let [users [sample-user-entity]
          pagination-meta {:total 1 :limit 10 :offset 0 :page 1 :pages 1}
          result (schema/users->paginated-response users pagination-meta)]
      (is (schema/m/validate schema/PaginatedUsersResponse result))))

  (testing "transformation is deterministic"
    (let [result1 (schema/user-entity->response sample-user-entity)
          result2 (schema/user-entity->response sample-user-entity)]
      (is (= result1 result2))))

  (testing "transformation preserves data integrity"
    (let [result (schema/user-entity->response sample-user-entity)]
      (is (= "user@example.com" (:email result)))
      (is (= "Test User" (:name result)))
      (is (= true (:active result))))))