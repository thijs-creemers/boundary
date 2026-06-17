(ns boundary.mcp.core.tools-test
  (:require [boundary.mcp.core.tools :as tools]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit catalog-has-five-read-tools
  (is (= 5 (count tools/catalog)))
  (is (= #{"explain-error" "lint" "validate-schema" "describe-module" "sql-preview"}
         tools/tool-names))
  (testing "every tool has wire fields and is a :read capability"
    (is (every? #(and (:name %) (:description %) (:inputSchema %)) tools/catalog))
    (is (every? #(= :read (:capability %)) tools/catalog)))
  (testing "inputSchema is JSON-Schema-shaped with required fields"
    (is (every? #(= "object" (get-in % [:inputSchema :type])) tools/catalog))
    (is (every? #(seq (get-in % [:inputSchema :required])) tools/catalog))))

(deftest ^:unit capability-lookup
  (is (= :read (tools/capability "lint")))
  (is (nil? (tools/capability "nope"))))
