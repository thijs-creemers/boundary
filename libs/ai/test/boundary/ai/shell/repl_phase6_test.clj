(ns boundary.ai.shell.repl-phase6-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.ai.shell.repl :as ai]
            [boundary.ai.ports :as ports]))

(defrecord StubProvider []
  ports/IAIProvider
  (complete [_ _messages _opts]
    {:text "Stub review result" :tokens 5 :provider :stub :model "stub"})
  (complete-json [_ _messages _schema _opts]
    {:data {} :tokens 5 :provider :stub :model "stub"})
  (provider-name [_] :stub))

(use-fixtures :each
  (fn [f]
    (let [old ai/*ai-service*]
      (ai/set-service! {:provider (->StubProvider) :fallback nil})
      (try (f) (finally (ai/set-service! old))))))

(deftest ^:unit review-returns-text
  (testing "ai/review calls service and returns text"
    (let [result (ai/review "(ns test.core)\n(defn foo [] 1)")]
      (is (string? result)))))

(deftest ^:unit test-ideas-returns-text
  (testing "ai/test-ideas calls service and returns text"
    (let [result (ai/test-ideas "(ns test.core)\n(defn foo [] 1)")]
      (is (string? result)))))
