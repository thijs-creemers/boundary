(ns boundary.external.shell.adapters.smtp-test
  "Integration tests for the SMTP provider adapter.
   Tests verify record creation, protocol satisfaction, and graceful error handling
   against an unreachable host — no real SMTP server required."
  (:require [boundary.external.ports :as ports]
            [boundary.external.shell.adapters.smtp :as smtp]
            [clojure.test :refer [deftest is testing]]))

(def ^:private test-config
  {:host "localhost-nonexistent.invalid"
   :port 1025
   :tls? false
   :ssl? false
   :from "no-reply@example.com"})

(deftest create-smtp-provider-test
  ^:integration
  (testing "returns a record satisfying ISmtpProvider"
    (let [provider (smtp/create-smtp-provider test-config)]
      (is (satisfies? ports/ISmtpProvider provider))
      (is (= "localhost-nonexistent.invalid" (:host provider)))
      (is (= 1025 (:port provider)))
      (is (= "no-reply@example.com" (:from provider))))))

(deftest test-connection-unreachable-test
  ^:integration
  (testing "test-connection! on unreachable host returns {:success? false}"
    (let [provider (smtp/create-smtp-provider test-config)
          result   (ports/test-connection! provider)]
      (is (false? (:success? result)))
      (is (some? (:error result)))
      (is (string? (get-in result [:error :message]))))))

(deftest send-email-unreachable-test
  ^:integration
  (testing "send-email! on unreachable host returns {:success? false}"
    (let [provider (smtp/create-smtp-provider test-config)
          email    {:to      "dest@example.com"
                    :subject "Test"
                    :body    "Hello"}
          result   (ports/send-email! provider email)]
      (is (false? (:success? result)))
      (is (some? (:error result))))))

(deftest send-email-async-test
  ^:integration
  (testing "send-email-async! returns a future"
    (let [provider (smtp/create-smtp-provider test-config)
          email    {:to "dest@example.com" :subject "Async" :body "Hi"}
          fut      (ports/send-email-async! provider email)]
      (is (future? fut))
      ;; Dereferencing should yield an error map (host unreachable)
      (let [result (deref fut 5000 :timeout)]
        (is (not= :timeout result))
        (is (false? (:success? result)))))))
