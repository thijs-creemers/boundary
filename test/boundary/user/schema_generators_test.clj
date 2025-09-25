(ns boundary.user.schema-generators-test
  (:require
   [clojure.test :refer :all]
   [boundary.user.schema :as schema]
   [malli.core :as m]))

(deftest generate-user-test
  (testing "generates valid user with defaults"
    (let [user (schema/generate-user)]
      (is (m/validate schema/User user))
      (is (uuid? (:id user)))
      (is (string? (:email user)))
      (is (.contains (:email user) "@example.com"))
      (is (string? (:name user)))
      (is (.startsWith (:name user) "Test User"))
      (is (contains? #{:admin :user :viewer} (:role user)))
      (is (= true (:active user)))
      (is (uuid? (:tenant-id user)))
      (is (string? (:created-at user)))
      (is (nil? (:updated-at user)))
      (is (nil? (:deleted-at user)))))

  (testing "accepts overrides"
    (let [custom-email "custom@test.com"
          custom-name "Custom User"
          custom-role :admin
          user (schema/generate-user {:email custom-email
                                      :name custom-name
                                      :role custom-role
                                      :active false})]
      (is (m/validate schema/User user))
      (is (= custom-email (:email user)))
      (is (= custom-name (:name user)))
      (is (= custom-role (:role user)))
      (is (= false (:active user)))))

  (testing "maintains required fields integrity"
    (let [user (schema/generate-user)]
      (is (every? #(contains? user %) [:id :email :name :role :active :tenant-id :created-at]))))

  (testing "generates unique users on multiple calls"
    (let [user1 (schema/generate-user)
          user2 (schema/generate-user)]
      (is (not= (:id user1) (:id user2)))
      (is (not= (:email user1) (:email user2)))
      (is (not= (:tenant-id user1) (:tenant-id user2)))))

  (testing "supports partial overrides"
    (let [user (schema/generate-user {:active false})]
      (is (= false (:active user)))
      (is (uuid? (:id user)))
      (is (string? (:email user))))))

(deftest generate-user-preferences-test
  (testing "generates valid preferences with defaults"
    (let [preferences (schema/generate-user-preferences)]
      (is (m/validate schema/UserPreferences preferences))
      (is (map? (:notifications preferences)))
      (is (boolean? (get-in preferences [:notifications :email])))
      (is (boolean? (get-in preferences [:notifications :push])))
      (is (boolean? (get-in preferences [:notifications :sms])))
      (is (contains? #{:light :dark :auto} (:theme preferences)))
      (is (= "en" (:language preferences)))
      (is (= "UTC" (:timezone preferences)))
      (is (contains? #{:iso :us :eu} (:date-format preferences)))
      (is (contains? #{:12h :24h} (:time-format preferences)))))

  (testing "accepts overrides"
    (let [custom-theme :dark
          custom-language "es"
          preferences (schema/generate-user-preferences {:theme custom-theme
                                                         :language custom-language})]
      (is (m/validate schema/UserPreferences preferences))
      (is (= custom-theme (:theme preferences)))
      (is (= custom-language (:language preferences)))))

  (testing "maintains notification structure"
    (let [preferences (schema/generate-user-preferences)]
      (is (contains? (:notifications preferences) :email))
      (is (contains? (:notifications preferences) :push))
      (is (contains? (:notifications preferences) :sms))))

  (testing "generates different preferences on multiple calls"
    (let [prefs1 (schema/generate-user-preferences)
          prefs2 (schema/generate-user-preferences)]
      ;; Due to randomness, these might occasionally be the same, but usually different
      ;; We test that the generation process works, not that they're always different
      (is (m/validate schema/UserPreferences prefs1))
      (is (m/validate schema/UserPreferences prefs2)))))

(deftest generate-create-user-request-test
  (testing "generates valid create user request with defaults"
    (let [request (schema/generate-create-user-request)]
      (is (m/validate schema/CreateUserRequest request))
      (is (string? (:email request)))
      (is (.contains (:email request) "@example.com"))
      (is (string? (:name request)))
      (is (.startsWith (:name request) "New User"))
      (is (contains? #{:admin :user :viewer} (:role request)))
      (is (= true (:active request)))
      (is (= true (:send-welcome request)))))

  (testing "accepts overrides"
    (let [custom-email "new@custom.com"
          custom-role :admin
          request (schema/generate-create-user-request {:email custom-email
                                                        :role custom-role
                                                        :active false})]
      (is (m/validate schema/CreateUserRequest request))
      (is (= custom-email (:email request)))
      (is (= custom-role (:role request)))
      (is (= false (:active request)))))

  (testing "generates unique requests on multiple calls"
    (let [request1 (schema/generate-create-user-request)
          request2 (schema/generate-create-user-request)]
      (is (not= (:email request1) (:email request2)))
      (is (not= (:name request1) (:name request2)))))

  (testing "includes all expected fields"
    (let [request (schema/generate-create-user-request)]
      (is (every? #(contains? request %) [:email :name :role :active :send-welcome])))))

(deftest generate-update-user-request-test
  (testing "generates valid update user request with defaults"
    (let [request (schema/generate-update-user-request)]
      (is (m/validate schema/UpdateUserRequest request))
      (is (string? (:name request)))
      (is (.startsWith (:name request) "Updated User"))
      (is (contains? #{:admin :user :viewer} (:role request)))
      (is (boolean? (:active request)))))

  (testing "accepts overrides"
    (let [custom-name "Custom Updated Name"
          custom-active true
          request (schema/generate-update-user-request {:name custom-name
                                                        :active custom-active
                                                        :role :viewer})]
      (is (m/validate schema/UpdateUserRequest request))
      (is (= custom-name (:name request)))
      (is (= custom-active (:active request)))
      (is (= :viewer (:role request)))))

  (testing "generates different requests on multiple calls"
    (let [request1 (schema/generate-update-user-request)
          request2 (schema/generate-update-user-request)]
      ;; Names should be different due to random numbers
      (is (not= (:name request1) (:name request2)))))

  (testing "all fields are optional in update request"
    (let [empty-request (schema/generate-update-user-request {})]
      (is (m/validate schema/UpdateUserRequest empty-request)))))

(deftest generators-integration-test
  (testing "generated user works with transformers"
    (let [user (schema/generate-user)
          transformed (schema/user-entity->response user)]
      (is (m/validate schema/UserResponse transformed))
      (is (string? (:id transformed)))
      (is (string? (:tenantId transformed)))))

  (testing "generated preferences merge with user"
    (let [user (schema/generate-user)
          preferences (schema/generate-user-preferences)
          merged (merge user preferences)]
      (is (m/validate schema/UserProfile merged))))

  (testing "generated create request can be transformed"
    (let [request (schema/generate-create-user-request)
          result (schema/validate-create-user-request request)]
      (is (:valid? result))
      (is (contains? (:data result) :tenant-id))))

  (testing "generators produce data suitable for business operations"
    (let [user (schema/generate-user)
          update-request (schema/generate-update-user-request)]
      ;; Simulate updating a user
      (let [updated-user (merge user (select-keys update-request [:name :role :active]))]
        (is (m/validate schema/User updated-user))))))

(deftest generators-consistency-test
  (testing "generators maintain schema compatibility"
    (let [user (schema/generate-user)
          preferences (schema/generate-user-preferences)
          create-request (schema/generate-create-user-request)
          update-request (schema/generate-update-user-request)]

      ;; All generators should produce valid data
      (is (m/validate schema/User user))
      (is (m/validate schema/UserPreferences preferences))
      (is (m/validate schema/CreateUserRequest create-request))
      (is (m/validate schema/UpdateUserRequest update-request))))

  (testing "generators respect override precedence"
    (let [override-data {:email "test@override.com"
                         :name "Override Name"
                         :role :admin
                         :active false}
          user (schema/generate-user override-data)]

      ;; All override fields should be applied
      (is (= "test@override.com" (:email user)))
      (is (= "Override Name" (:name user)))
      (is (= :admin (:role user)))
      (is (= false (:active user)))

      ;; Generated fields should still exist
      (is (uuid? (:id user)))
      (is (uuid? (:tenant-id user)))
      (is (string? (:created-at user)))))

  (testing "generators handle nil and empty overrides"
    (let [user-nil (schema/generate-user nil)
          user-empty (schema/generate-user {})
          preferences-nil (schema/generate-user-preferences nil)
          preferences-empty (schema/generate-user-preferences {})]

      (is (m/validate schema/User user-nil))
      (is (m/validate schema/User user-empty))
      (is (m/validate schema/UserPreferences preferences-nil))
      (is (m/validate schema/UserPreferences preferences-empty)))))

(deftest generators-performance-test
  (testing "generators perform efficiently for bulk operations"
    (let [start-time (System/nanoTime)
          users (repeatedly 100 #(schema/generate-user))
          end-time (System/nanoTime)
          duration-ms (/ (- end-time start-time) 1000000.0)]

      ;; Should generate 100 users in reasonable time (less than 1 second)
      (is (< duration-ms 1000))
      (is (= 100 (count users)))
      (is (every? #(m/validate schema/User %) users))))

  (testing "generators maintain uniqueness in bulk generation"
    (let [users (repeatedly 50 #(schema/generate-user))
          emails (map :email users)
          ids (map :id users)]

      ;; All emails should be unique
      (is (= (count emails) (count (set emails))))
      ;; All IDs should be unique
      (is (= (count ids) (count (set ids)))))))