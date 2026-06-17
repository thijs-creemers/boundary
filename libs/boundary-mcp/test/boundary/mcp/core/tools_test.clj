(ns boundary.mcp.core.tools-test
  (:require [boundary.mcp.core.tools :as tools]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit catalog-has-tier0-and-tier1-tools
  (is (= 9 (count tools/catalog)))
  (is (= #{"explain-error" "lint" "validate-schema" "describe-module" "sql-preview"
           "scaffold-module" "add-field" "gen-tests" "gen-migration"}
         tools/tool-names))
  (testing "Tier 0 tools are :read; Tier 1 generate tools are :generate"
    (is (= #{"explain-error" "lint" "validate-schema" "describe-module" "sql-preview"}
           (set (keep #(when (= :read (:capability %)) (:name %)) tools/catalog))))
    (is (= #{"scaffold-module" "add-field" "gen-tests" "gen-migration"}
           (set (keep #(when (= :generate (:capability %)) (:name %)) tools/catalog)))))
  (testing "every tool has wire fields and a known capability"
    (is (every? #(and (:name %) (:description %) (:inputSchema %)) tools/catalog))
    (is (every? #(#{:read :generate} (:capability %)) tools/catalog)))
  (testing "inputSchema is JSON-Schema-shaped with required fields"
    (is (every? #(= "object" (get-in % [:inputSchema :type])) tools/catalog))
    (is (every? #(seq (get-in % [:inputSchema :required])) tools/catalog))))

(deftest ^:unit capability-lookup
  (is (= :read (tools/capability "lint")))
  (is (= :generate (tools/capability "scaffold-module")))
  (is (nil? (tools/capability "nope"))))
