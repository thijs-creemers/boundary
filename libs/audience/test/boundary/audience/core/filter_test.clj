(ns boundary.audience.core.filter-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.audience.core.filter :as f]))

(deftest demographics-filter-sql
  (testing ":demographics generates HoneySQL equality clause"
    (let [result (f/filter->sql {:type :demographics :field :plan :op :eq :value "premium"})]
      (is (= [:= :plan "premium"] result))))

  (testing ":demographics :in generates HoneySQL IN clause"
    (let [result (f/filter->sql {:type :demographics :field :role :op :in :value ["admin" "user"]})]
      (is (= [:in :role ["admin" "user"]] result)))))

(deftest location-filter-sql
  (testing ":location generates HoneySQL clause"
    (let [result (f/filter->sql {:type :location :field :country :op :in :value ["NL" "BE"]})]
      (is (= [:in :country ["NL" "BE"]] result)))))

(deftest account-tenure-filter-sql
  (testing ":account-tenure generates date comparison"
    (let [result (f/filter->sql {:type :account-tenure :op :gte :value 90})]
      (is (some? result)))))

(deftest last-active-filter-sql
  (testing ":last-active :within-days generates date window"
    (let [result (f/filter->sql {:type :last-active :op :within-days :value 30})]
      (is (some? result)))))

(deftest role-filter-sql
  (testing ":role generates equality clause"
    (let [result (f/filter->sql {:type :role :field :role :op :eq :value "admin"})]
      (is (= [:= :role "admin"] result)))))

(deftest behavior-filter-returns-nil-sql
  (testing ":behavior filter->sql returns nil (not DB-evaluable)"
    (is (nil? (f/filter->sql {:type :behavior :op :fn :value (constantly true)})))))

(deftest behavior-filter-predicate
  (testing ":behavior filter->predicate returns the fn from :value"
    (let [pred-fn (fn [user] (> (:login-count user) 5))
          pred (f/filter->predicate {:type :behavior :op :fn :value pred-fn})]
      (is (true? (pred {:login-count 10})))
      (is (false? (pred {:login-count 2}))))))

(deftest feature-usage-filter-sql-returns-nil
  (testing ":feature-usage filter->sql returns nil"
    (is (nil? (f/filter->sql {:type :feature-usage :field :feature-id :op :used-within :value 14})))))

(deftest feature-usage-filter-predicate
  (testing ":feature-usage builds predicate from declarative params"
    (let [pred (f/filter->predicate {:type :feature-usage :field :feature-id :op :used-within :value 14})]
      (is (fn? pred)))))

(deftest custom-filter-type-registration
  (testing "apps can register custom filter types via defmethod"
    (defmethod f/filter->sql :subscription-tier [filt]
      [:= :subscriptions.tier (:value filt)])
    (let [result (f/filter->sql {:type :subscription-tier :value "gold"})]
      (is (= [:= :subscriptions.tier "gold"] result)))
    (remove-method f/filter->sql :subscription-tier)))

(deftest sql-op-mapping
  (testing "all comparison operators map correctly"
    (is (= [:= :x 1]   (f/filter->sql {:type :demographics :field :x :op :eq  :value 1})))
    (is (= [:<> :x 1]  (f/filter->sql {:type :demographics :field :x :op :neq :value 1})))
    (is (= [:> :x 1]   (f/filter->sql {:type :demographics :field :x :op :gt  :value 1})))
    (is (= [:>= :x 1]  (f/filter->sql {:type :demographics :field :x :op :gte :value 1})))
    (is (= [:< :x 1]   (f/filter->sql {:type :demographics :field :x :op :lt  :value 1})))
    (is (= [:<= :x 1]  (f/filter->sql {:type :demographics :field :x :op :lte :value 1})))
    (is (= [:like :x "%foo%"] (f/filter->sql {:type :demographics :field :x :op :contains :value "foo"})))))
