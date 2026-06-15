(ns agents-gen-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [agents-gen :as gen]))

(def sample-knowledge
  {:fc-is {:layers [{:from :shell :to :core :allowed true}
                    {:from :core :to :shell :allowed false :reason "violates FC/IS"}]
           :rules ["core/ must not import shell/IO/logging/DB"]
           :ports-required true
           :example "(ns {{ns}}.core.product)"}})

(deftest render-fc-is-emits-rows-and-substitutes-ns
  (let [out (gen/render-fc-is (:fc-is sample-knowledge) "myapp")]
    (is (str/includes? out "Shell → Core"))
    (is (str/includes? out "❌"))
    (is (str/includes? out "violates FC/IS"))
    (is (str/includes? out "myapp.core.product"))
    (is (not (str/includes? out "{{ns}}")))))

(deftest render-fc-is-template-keeps-project-ns-token
  (let [out (gen/render-fc-is (:fc-is sample-knowledge) "{{project-ns}}")]
    (is (str/includes? out "{{project-ns}}.core.product"))))

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

(deftest render-naming-emits-table
  (let [out (gen/render-naming [{:context :clojure :case :kebab :example ":password-hash"}
                                {:context :db :case :snake :example "password_hash"}])]
    (is (str/includes? out "| Location | Convention | Example |"))
    (is (str/includes? out "kebab"))
    (is (str/includes? out ":password-hash"))))
