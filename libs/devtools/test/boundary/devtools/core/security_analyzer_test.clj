(ns boundary.devtools.core.security-analyzer-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.core.security-analyzer :as sec]))

(deftest ^:unit analyze-password-policy
  (testing "extracts password policy strength indicators"
    (let [policy {:min-length 12
                  :require-uppercase? true
                  :require-lowercase? true
                  :require-numbers? true
                  :require-special-chars? true}
          result (sec/analyze-password-policy policy)]
      (is (= :strong (:strength result)))
      (is (= 12 (:min-length result))))))

(deftest ^:unit analyze-weak-password-policy
  (testing "flags weak password policy"
    (let [policy {:min-length 4
                  :require-uppercase? false
                  :require-lowercase? false
                  :require-numbers? false
                  :require-special-chars? false}
          result (sec/analyze-password-policy policy)]
      (is (= :weak (:strength result))))))

(deftest ^:unit analyze-auth-methods
  (testing "detects active auth methods from config"
    (let [cfg {:boundary/settings {:features {:mfa {:enabled? true}}}}
          result (sec/analyze-auth-methods cfg)]
      (is (contains? (set (:methods result)) :jwt))
      (is (contains? (set (:methods result)) :session)))))

(deftest ^:unit build-security-summary
  (testing "builds complete security summary with runtime data"
    (let [cfg {:boundary/settings
               {:user-validation
                {:password-policy {:min-length 12
                                   :require-uppercase? true
                                   :require-lowercase? true
                                   :require-numbers? true
                                   :require-special-chars? false}
                 :role-restrictions {:allowed-roles #{:user :admin}}}}}
          summary (sec/build-security-summary cfg {:active-sessions 5
                                                   :recent-auth-failures [{:type :failed-login}]})]
      (is (map? summary))
      (is (:password-policy summary))
      (is (:auth-methods summary))
      (is (:csp summary))
      (is (= 5 (:active-sessions summary)))
      (is (= 1 (count (:recent-failures summary)))))))

(deftest ^:unit rate-limiting-from-runtime-data
  (testing "rate-limiting? comes from runtime data, not config"
    (let [cfg {}]
      (is (false? (:rate-limiting? (sec/build-security-summary cfg {}))))
      (is (true? (:rate-limiting? (sec/build-security-summary cfg {:rate-limiting? true})))))))
