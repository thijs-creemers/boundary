(ns agents-gen-test
  (:require [clojure.test :refer [deftest is testing]]
            [agents-gen :as gen]))

(deftest splice-region-replaces-between-markers
  (let [doc "a\n<!-- gen:x -->\nOLD\n<!-- /gen:x -->\nb\n"]
    (is (= "a\n<!-- gen:x -->\nNEW\n<!-- /gen:x -->\nb\n"
           (gen/splice-region doc "x" "NEW")))))

(deftest splice-region-is-idempotent
  (let [doc "a\n<!-- gen:x -->\nOLD\n<!-- /gen:x -->\nb\n"
        once (gen/splice-region doc "x" "NEW")]
    (is (= once (gen/splice-region once "x" "NEW")))))

(deftest splice-region-throws-on-missing-marker
  (is (thrown? clojure.lang.ExceptionInfo
               (gen/splice-region "no markers here" "x" "NEW"))))
