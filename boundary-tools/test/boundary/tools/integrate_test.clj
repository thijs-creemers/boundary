(ns boundary.tools.integrate-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.tools.integrate :as integrate]))

;; =============================================================================
;; patch-deps-edn
;; =============================================================================

(deftest patch-deps-edn-test
  (let [sample-deps (str ":paths [\"src\"\n"
                         "           \"libs/core/src\" \"libs/core/test\"\n"
                         "           \"libs/admin/src\" \"libs/admin/test\"]")]

    (testing "adds source and test paths for new module"
      (let [[result changes] (integrate/patch-deps-edn sample-deps "product")]
        (is (seq changes))
        (is (re-find #"\"libs/product/src\"" result))
        (is (re-find #"\"libs/product/test\"" result))))

    (testing "is idempotent — no changes if already present"
      (let [deps-with-product (str sample-deps "\n           \"libs/product/src\" \"libs/product/test\"")
            [_result changes] (integrate/patch-deps-edn deps-with-product "product")]
        (is (empty? changes))))))

;; =============================================================================
;; patch-tests-edn
;; =============================================================================

(deftest patch-tests-edn-test
  (let [sample-tests (str ":tests [{:id :core\n"
                          "           :test-paths [\"libs/core/test\"]\n"
                          "           :ns-patterns [\"boundary.core.*-test\"]}]")]

    (testing "adds new test suite"
      (let [[result changes] (integrate/patch-tests-edn sample-tests "product")]
        (is (seq changes))
        (is (re-find #":id :product" result))
        (is (re-find #"\"libs/product/test\"" result))))

    (testing "is idempotent — no changes if already present"
      (let [tests-with-product (str sample-tests
                                    "\n          {:id :product\n"
                                    "           :test-paths [\"libs/product/test\"]\n"
                                    "           :ns-patterns [\"boundary.product.*-test\"]}")
            [_result changes] (integrate/patch-tests-edn tests-with-product "product")]
        (is (empty? changes))))))

;; =============================================================================
;; patch-wiring
;; =============================================================================

(deftest patch-wiring-test
  (let [sample-wiring (str "(ns boundary.platform.shell.system.wiring\n"
                           "  (:require [boundary.admin.shell.module-wiring]\n"
                           "            [boundary.user.shell.module-wiring]))")]

    (testing "adds new module-wiring require"
      (let [[result changes] (integrate/patch-wiring sample-wiring "product")]
        (is (seq changes))
        (is (re-find #"boundary\.product\.shell\.module-wiring" result))))

    (testing "is idempotent — no changes if already wired"
      (let [wiring-with-product (str sample-wiring
                                     "\n            [boundary.product.shell.module-wiring]")
            [_result changes] (integrate/patch-wiring wiring-with-product "product")]
        (is (empty? changes))))))

;; =============================================================================
;; generate-config-snippet
;; =============================================================================

(deftest generate-config-snippet-test
  (testing "generates basic config snippet"
    (let [snippet (integrate/generate-config-snippet "product" false)]
      (is (re-find #":boundary/product" snippet))
      (is (re-find #":enabled\? true" snippet))
      (is (not (re-find #":base-path" snippet)))))

  (testing "includes base-path for modules with routes"
    (let [snippet (integrate/generate-config-snippet "product" true)]
      (is (re-find #":base-path \"/api/product\"" snippet)))))
