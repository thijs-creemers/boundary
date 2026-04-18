(ns boundary.devtools.core.error-formatter-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [boundary.devtools.core.error-formatter :as formatter]))

(deftest ^:unit format-enriched-error-test
  (testing "enriched error with all fields"
    (let [enriched {:code "BND-201"
                    :category :validation
                    :data {:schema :user/create}
                    :stacktrace {:user-frames [{:ns "boundary.user.core.validation"
                                                :fn "validate"
                                                :file "validation.clj"
                                                :line 42}]
                                 :framework-frames []
                                 :jvm-frames []
                                 :total-hidden 5}
                    :fix {:fix-id :apply-migration
                          :label "Apply pending migration"
                          :safe? true}
                    :dashboard-url "http://localhost:9999/dashboard/errors"
                    :docs-url "https://boundary.dev/errors/BND-201"}
          output (formatter/format-enriched-error enriched)]
      (is (str/includes? output "BND-201"))
      (is (str/includes? output "Your code"))
      (is (str/includes? output "(fix!)"))
      (is (str/includes? output "localhost:9999"))))

  (testing "enriched error without fix"
    (let [enriched {:code "BND-402"
                    :category :auth
                    :data {}
                    :stacktrace {:user-frames [] :framework-frames [] :jvm-frames [] :total-hidden 3}
                    :dashboard-url "http://localhost:9999/dashboard/errors"
                    :docs-url "https://boundary.dev/errors/BND-402"}
          output (formatter/format-enriched-error enriched)]
      (is (str/includes? output "BND-402"))
      (is (not (str/includes? output "(fix!)"))))))

(deftest ^:unit format-unclassified-error-test
  (let [ex (Exception. "something broke")
        output (formatter/format-unclassified-error ex)]
    (testing "shows exception message"
      (is (str/includes? output "something broke")))
    (testing "suggests AI analysis"
      (is (str/includes? output "explain")))))
