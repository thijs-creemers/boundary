(ns boundary.tenant.core.membership-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.tenant.core.membership :as sut])
  (:import (java.time Instant)
           (java.util UUID)))

^{:kaocha.testable/meta {:unit true :tenant true}}

(def user-id   (UUID/randomUUID))
(def tenant-id (UUID/randomUUID))
(def now       (Instant/now))

;; =============================================================================
;; prepare-invitation
;; =============================================================================

(deftest prepare-invitation-test
  (testing "creates a membership map with invited status"
    (let [m (sut/prepare-invitation user-id tenant-id :member now)]
      (is (uuid? (:id m)))
      (is (= tenant-id (:tenant-id m)))
      (is (= user-id (:user-id m)))
      (is (= :member (:role m)))
      (is (= :invited (:status m)))
      (is (= now (:invited-at m)))
      (is (nil? (:accepted-at m)))
      (is (= now (:created-at m)))
      (is (nil? (:updated-at m)))))

  (testing "each invitation gets a unique id"
    (let [m1 (sut/prepare-invitation user-id tenant-id :admin now)
          m2 (sut/prepare-invitation user-id tenant-id :admin now)]
      (is (not= (:id m1) (:id m2))))))

;; =============================================================================
;; accept-invitation
;; =============================================================================

(deftest accept-invitation-test
  (testing "transitions status to :active and sets accepted-at"
    (let [membership (sut/prepare-invitation user-id tenant-id :member now)
          later      (Instant/parse "2099-01-01T00:00:00Z")
          accepted   (sut/accept-invitation membership later)]
      (is (= :active (:status accepted)))
      (is (= later (:accepted-at accepted)))
      (is (= later (:updated-at accepted)))))

  (testing "preserves all other fields"
    (let [membership (sut/prepare-invitation user-id tenant-id :admin now)
          accepted   (sut/accept-invitation membership now)]
      (is (= (:id membership) (:id accepted)))
      (is (= (:tenant-id membership) (:tenant-id accepted)))
      (is (= (:user-id membership) (:user-id accepted)))
      (is (= :admin (:role accepted))))))

;; =============================================================================
;; suspend-membership
;; =============================================================================

(deftest suspend-membership-test
  (testing "sets status to :suspended"
    (let [m       (-> (sut/prepare-invitation user-id tenant-id :member now)
                      (sut/accept-invitation now))
          later   (Instant/parse "2099-06-01T00:00:00Z")
          result  (sut/suspend-membership m later)]
      (is (= :suspended (:status result)))
      (is (= later (:updated-at result))))))

;; =============================================================================
;; revoke-membership
;; =============================================================================

(deftest revoke-membership-test
  (testing "sets status to :revoked"
    (let [m      (sut/prepare-invitation user-id tenant-id :viewer now)
          result (sut/revoke-membership m now)]
      (is (= :revoked (:status result)))
      (is (= now (:updated-at result))))))

;; =============================================================================
;; update-role
;; =============================================================================

(deftest update-role-test
  (testing "updates role and updated-at"
    (let [m      (-> (sut/prepare-invitation user-id tenant-id :member now)
                     (sut/accept-invitation now))
          result (sut/update-role m :admin now)]
      (is (= :admin (:role result)))
      (is (= now (:updated-at result)))))

  (testing "preserves status"
    (let [m      (-> (sut/prepare-invitation user-id tenant-id :member now)
                     (sut/accept-invitation now))
          result (sut/update-role m :viewer now)]
      (is (= :active (:status result))))))

;; =============================================================================
;; active-member?
;; =============================================================================

(deftest active-member-test
  (testing "returns true for :active membership"
    (let [m (-> (sut/prepare-invitation user-id tenant-id :member now)
                (sut/accept-invitation now))]
      (is (true? (sut/active-member? m)))))

  (testing "returns false for :invited membership"
    (let [m (sut/prepare-invitation user-id tenant-id :member now)]
      (is (false? (sut/active-member? m)))))

  (testing "returns false for :suspended membership"
    (let [m (-> (sut/prepare-invitation user-id tenant-id :member now)
                (sut/accept-invitation now)
                (sut/suspend-membership now))]
      (is (false? (sut/active-member? m)))))

  (testing "returns false for :revoked membership"
    (let [m (-> (sut/prepare-invitation user-id tenant-id :member now)
                (sut/revoke-membership now))]
      (is (false? (sut/active-member? m))))))

;; =============================================================================
;; has-role?
;; =============================================================================

(deftest has-role-test
  (testing "returns true when membership role is in allowed set"
    (let [m (-> (sut/prepare-invitation user-id tenant-id :admin now)
                (sut/accept-invitation now))]
      (is (true? (sut/has-role? m #{:admin})))))

  (testing "returns false when membership role is not in allowed set"
    (let [m (-> (sut/prepare-invitation user-id tenant-id :viewer now)
                (sut/accept-invitation now))]
      (is (false? (sut/has-role? m #{:admin})))))

  (testing "works with multiple allowed roles"
    (let [m (-> (sut/prepare-invitation user-id tenant-id :member now)
                (sut/accept-invitation now))]
      (is (true? (sut/has-role? m #{:admin :member :viewer}))))))
