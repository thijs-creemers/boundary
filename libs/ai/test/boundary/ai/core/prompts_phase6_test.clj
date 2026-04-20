(ns boundary.ai.core.prompts-phase6-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.ai.core.prompts :as prompts]))

(deftest ^:unit review-messages-structure
  (testing "builds review messages with system and user roles"
    (let [msgs (prompts/review-messages "boundary.user.core.validation"
                                        "(ns boundary.user.core.validation)\n(defn validate [x] x)")]
      (is (= 2 (count msgs)))
      (is (= :system (:role (first msgs))))
      (is (= :user (:role (second msgs))))
      (is (clojure.string/includes? (:content (second msgs)) "boundary.user.core.validation")))))

(deftest ^:unit test-ideas-messages-structure
  (testing "builds test-ideas messages"
    (let [msgs (prompts/test-ideas-messages "boundary.user.core.validation"
                                            "(ns boundary.user.core.validation)\n(defn validate [x] x)"
                                            nil)]
      (is (= 2 (count msgs)))
      (is (clojure.string/includes? (:content (second msgs)) "test")))))

(deftest ^:unit refactor-fcis-messages-structure
  (testing "builds refactor-fcis messages with violation info"
    (let [msgs (prompts/refactor-fcis-messages
                "boundary.product.core.validation"
                "(ns boundary.product.core.validation\n  (:require [boundary.product.shell.persistence :as p]))"
                [{:from "boundary.product.core.validation"
                  :to "boundary.product.shell.persistence"}])]
      (is (= 2 (count msgs)))
      (is (clojure.string/includes? (:content (second msgs)) "FC/IS")))))
