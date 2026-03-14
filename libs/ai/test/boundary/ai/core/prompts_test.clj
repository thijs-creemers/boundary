(ns boundary.ai.core.prompts-test
  (:require [boundary.ai.core.prompts :as prompts]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest build-scaffolding-system-prompt-test
  ^:unit
  (testing "system prompt contains FC/IS framework context"
    (let [prompt (prompts/build-scaffolding-system-prompt)]
      (is (string? prompt))
      (is (str/includes? prompt "FC/IS"))
      (is (str/includes? prompt "kebab-case"))
      (is (str/includes? prompt "JSON")))))

(deftest build-scaffolding-user-prompt-test
  ^:unit
  (testing "user prompt includes description"
    (let [prompt (prompts/build-scaffolding-user-prompt "a product module" [])]
      (is (str/includes? prompt "product module"))))

  (testing "user prompt includes existing modules when provided"
    (let [prompt (prompts/build-scaffolding-user-prompt "product" ["user" "order"])]
      (is (str/includes? prompt "user"))
      (is (str/includes? prompt "order"))))

  (testing "user prompt omits module context when empty"
    (let [prompt (prompts/build-scaffolding-user-prompt "product" [])]
      (is (not (str/includes? prompt "Existing modules"))))))

(deftest scaffolding-messages-test
  ^:unit
  (testing "returns two messages with correct roles"
    (let [msgs (prompts/scaffolding-messages "product module" ["user"])]
      (is (= 2 (count msgs)))
      (is (= :system (:role (first msgs))))
      (is (= :user   (:role (second msgs)))))))

(deftest error-explainer-messages-test
  ^:unit
  (testing "returns system + user messages"
    (let [msgs (prompts/error-explainer-messages "ExceptionInfo: ..." {})]
      (is (= 2 (count msgs)))
      (is (= :system (:role (first msgs))))))

  (testing "user message contains the stack trace"
    (let [trace "clojure.lang.ExceptionInfo: validation failed"
          msgs  (prompts/error-explainer-messages trace {})]
      (is (str/includes? (:content (second msgs)) trace)))))

(deftest test-generator-messages-test
  ^:unit
  (testing "includes source file path and type in user message"
    (let [msgs (prompts/test-generator-messages "libs/user/src/core/v.clj" "(ns foo)" :unit)]
      (is (str/includes? (:content (second msgs)) "libs/user/src/core/v.clj"))
      (is (str/includes? (:content (second msgs)) "unit")))))

(deftest sql-copilot-messages-test
  ^:unit
  (testing "description is included in user message"
    (let [msgs (prompts/sql-copilot-messages "find active users" nil)]
      (is (str/includes? (:content (second msgs)) "active users"))))

  (testing "schema context is appended when provided"
    (let [msgs (prompts/sql-copilot-messages "find users" "users: id, email")]
      (is (str/includes? (:content (second msgs)) "users: id, email")))))

(deftest docs-messages-test
  ^:unit
  (testing "agents type uses AGENTS.md prompt"
    (let [msgs (prompts/docs-messages "libs/user" {} :agents)]
      (is (str/includes? (:content (first msgs)) "AGENTS.md"))))

  (testing "openapi type uses OpenAPI prompt"
    (let [msgs (prompts/docs-messages "libs/user" {} :openapi)]
      (is (str/includes? (:content (first msgs)) "OpenAPI"))))

  (testing "readme type uses README prompt"
    (let [msgs (prompts/docs-messages "libs/user" {} :readme)]
      (is (str/includes? (:content (first msgs)) "README")))))
