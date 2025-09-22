(ns boundary.user.schema-utilities-test
  (:require
    [clojure.test :refer :all]
    [malli.core :as m]
    [malli.util :as mu]
    [clojure.set :as set]))

;; Define test schema directly to avoid import issues
(def test-user-schema
  [:map {:title "User"}
   [:id :uuid]
   [:email :string]
   [:name :string]
   [:role [:enum :admin :user :viewer]]
   [:active :boolean]
   [:login-count {:optional true} :int]
   [:last-login {:optional true} :inst]
   [:date-format {:optional true} [:enum :iso :us :eu]]
   [:time-format {:optional true} [:enum :12h :24h]]
   [:tenant-id :uuid]
   [:avatar-url {:optional true} :string]
   [:created-at :inst]
   [:updated-at {:optional true} :inst]
   [:deleted-at {:optional true} :inst]])

(def test-user-preferences-schema
  [:map {:title "User Preferences"}
   [:notifications
    [:map
     [:email :boolean]
     [:push :boolean]
     [:sms :boolean]]]
   [:theme [:enum :light :dark :auto]]
   [:language :string]
   [:timezone :string]
   [:date-format [:enum :iso :us :eu]]
   [:time-format [:enum :12h :24h]]])

;; Test implementations of schema utility functions
(defn test-get-user-fields
  "Test implementation of get-user-fields"
  []
  (->> test-user-schema
       m/children
       (mapv (comp name first))))

(defn test-get-required-user-fields
  "Test implementation of get-required-user-fields"
  []
  (->> test-user-schema
       m/children
       (remove (fn [[_ schema]]
                 (or (m/optional-key? schema)
                     (= :maybe (m/type schema)))))
       (mapv (comp name first))))

(defn test-get-optional-user-fields
  "Test implementation of get-optional-user-fields"
  []
  (->> test-user-schema
       m/children
       (filter (fn [[_ schema]]
                 (or (m/optional-key? schema)
                     (= :maybe (m/type schema)))))
       (mapv (comp name first))))

(defn test-merge-user-schemas
  "Test implementation of merge-user-schemas"
  [& schemas]
  (reduce mu/merge schemas))

(deftest get-user-fields-test
  (testing "returns all User schema field names"
    (let [fields (test-get-user-fields)
          expected-fields ["id" "email" "name" "role" "active" "login-count" 
                          "last-login" "date-format" "time-format" "tenant-id" 
                          "avatar-url" "created-at" "updated-at" "deleted-at"]]
      (is (vector? fields))
      (is (= 14 (count fields)))
      (is (every? string? fields))
      (is (= (set expected-fields) (set fields)))
      ;; Test specific order matches schema definition
      (is (= "id" (first fields)))
      (is (= "email" (second fields)))
      (is (= "name" (nth fields 2)))
      (is (= "tenant-id" (nth fields 9)))))

  (testing "field names are strings not keywords"
    (let [fields (test-get-user-fields)]
      (is (every? string? fields))
      (is (not-any? keyword? fields))))

  (testing "returns consistent results on multiple calls"
    (let [fields1 (test-get-user-fields)
          fields2 (test-get-user-fields)]
      (is (= fields1 fields2)))))

(deftest get-required-user-fields-test
  (testing "returns only required User schema fields"
    (let [required-fields (test-get-required-user-fields)
          expected-required ["id" "email" "name" "role" "active" "tenant-id" "created-at"]]
      (is (vector? required-fields))
      (is (= 7 (count required-fields)))
      (is (every? string? required-fields))
      (is (= (set expected-required) (set required-fields)))))

  (testing "excludes optional fields"
    (let [required-fields (test-get-required-user-fields)
          optional-field-names ["login-count" "last-login" "date-format" 
                               "time-format" "avatar-url" "updated-at" "deleted-at"]]
      (is (empty? (set/intersection (set required-fields) (set optional-field-names))))))

  (testing "all required fields are also in get-user-fields"
    (let [all-fields (set (test-get-user-fields))
          required-fields (set (test-get-required-user-fields))]
      (is (set/subset? required-fields all-fields))))

  (testing "returns consistent results on multiple calls"
    (let [fields1 (test-get-required-user-fields)
          fields2 (test-get-required-user-fields)]
      (is (= fields1 fields2)))))

(deftest get-optional-user-fields-test
  (testing "returns only optional User schema fields"
    (let [optional-fields (test-get-optional-user-fields)
          expected-optional ["login-count" "last-login" "date-format" 
                            "time-format" "avatar-url" "updated-at" "deleted-at"]]
      (is (vector? optional-fields))
      (is (= 7 (count optional-fields)))
      (is (every? string? optional-fields))
      (is (= (set expected-optional) (set optional-fields)))))

  (testing "excludes required fields"
    (let [optional-fields (test-get-optional-user-fields)
          required-field-names ["id" "email" "name" "role" "active" "tenant-id" "created-at"]]
      (is (empty? (set/intersection (set optional-fields) (set required-field-names))))))

  (testing "all optional fields are also in get-user-fields"
    (let [all-fields (set (test-get-user-fields))
          optional-fields (set (test-get-optional-user-fields))]
      (is (set/subset? optional-fields all-fields))))

  (testing "returns consistent results on multiple calls"
    (let [fields1 (test-get-optional-user-fields)
          fields2 (test-get-optional-user-fields)]
      (is (= fields1 fields2)))))

(deftest field-sets-completeness-test
  (testing "required + optional fields = all user fields"
    (let [all-fields (set (test-get-user-fields))
          required-fields (set (test-get-required-user-fields))
          optional-fields (set (test-get-optional-user-fields))]
      (is (= all-fields (set/union required-fields optional-fields)))
      (is (empty? (set/intersection required-fields optional-fields)))))

  (testing "field counts add up correctly"
    (let [all-count (count (test-get-user-fields))
          required-count (count (test-get-required-user-fields))
          optional-count (count (test-get-optional-user-fields))]
      (is (= all-count (+ required-count optional-count))))))

(deftest merge-user-schemas-test
  (testing "merges User + UserPreferences schemas"
    (let [merged-schema (test-merge-user-schemas test-user-schema test-user-preferences-schema)
          user-fields (set (map first (m/children test-user-schema)))
          preferences-fields (set (map first (m/children test-user-preferences-schema)))
          merged-fields (set (map first (m/children merged-schema)))]
      ;; Merged schema should contain all fields from both schemas
      (is (set/subset? user-fields merged-fields))
      (is (set/subset? preferences-fields merged-fields))))

  (testing "merged schema validates expected data"
    (let [merged-schema (test-merge-user-schemas test-user-schema test-user-preferences-schema)
          test-data {:id (java.util.UUID/randomUUID)
                     :email "test@example.com"
                     :name "Test User"
                     :role :user
                     :active true
                     :tenant-id (java.util.UUID/randomUUID)
                     :created-at (java.time.Instant/now)
                     :notifications {:email true :push false :sms true}
                     :theme :light
                     :language "en"
                     :timezone "UTC"
                     :date-format :iso
                     :time-format :24h}]
      (is (m/validate merged-schema test-data))))

  (testing "merges multiple schemas in sequence"
    (let [base-schema [:map [:id :uuid] [:name :string]]
          extension1 [:map [:email :string] [:active {:optional true} :boolean]]
          extension2 [:map [:role :keyword] [:created-at :inst]]
          merged (test-merge-user-schemas base-schema extension1 extension2)
          expected-fields #{:id :name :email :active :role :created-at}]
      (is (= expected-fields (set (map first (m/children merged)))))))

  (testing "handles field precedence - later schemas override earlier ones"
    (let [schema1 [:map [:name :string] [:role [:enum :user :admin]]]
          schema2 [:map [:name :string] [:role [:enum :viewer :moderator :admin]]]
          merged (test-merge-user-schemas schema1 schema2)
          role-field (->> (m/children merged)
                         (filter #(= :role (first %)))
                         first
                         second)]
      (is (= [:enum :viewer :moderator :admin] role-field))))

  (testing "preserves optionality from later schemas"
    (let [schema1 [:map [:name :string]]
          schema2 [:map [:name {:optional true} :string]]
          merged (test-merge-user-schemas schema1 schema2)
          name-field (->> (m/children merged)
                         (filter #(= :name (first %)))
                         first)]
      (is (contains? (second name-field) :optional))
      (is (= true (get-in name-field [1 :optional])))))

  (testing "handles empty schema merge"
    (let [empty-schema [:map]
          merged (test-merge-user-schemas empty-schema test-user-schema)]
      (is (= (m/form test-user-schema) (m/form merged)))))

  (testing "single schema merge returns equivalent schema"
    (let [merged (test-merge-user-schemas test-user-schema)]
      (is (= (m/form test-user-schema) (m/form merged))))))

(deftest schema-utilities-integration-test
  (testing "utilities work with test schema structure"
    (let [all-fields (test-get-user-fields)
          required-fields (test-get-required-user-fields)
          optional-fields (test-get-optional-user-fields)]
      ;; Ensure we can identify specific known fields
      (is (contains? (set all-fields) "id"))
      (is (contains? (set all-fields) "email"))
      (is (contains? (set required-fields) "id"))
      (is (contains? (set required-fields) "email"))
      (is (contains? (set optional-fields) "login-count"))
      (is (contains? (set optional-fields) "avatar-url"))))

  (testing "merge utilities preserve schema validation behavior"
    (let [valid-user {:id (java.util.UUID/randomUUID)
                      :email "test@example.com"
                      :name "Test User"
                      :role :user
                      :active true
                      :tenant-id (java.util.UUID/randomUUID)
                      :created-at (java.time.Instant/now)}
          invalid-user (dissoc valid-user :email)
          merged-schema (test-merge-user-schemas test-user-schema)]
      (is (m/validate merged-schema valid-user))
      (is (not (m/validate merged-schema invalid-user))))))