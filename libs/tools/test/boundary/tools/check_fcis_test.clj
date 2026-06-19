(ns boundary.tools.check-fcis-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [boundary.tools.check-fcis :as fcis]))

(deftest ^:unit core-source-paths-includes-libs
  (testing "FC/IS scanner includes libs/*/src/boundary/*/core files"
    (let [scanned (map str (fcis/core-source-paths))]
      (is (some #(re-find #"libs/.+/src/boundary/.+/core/" %) scanned)
          "expected at least one libs/<lib>/src/boundary/<lib>/core/ file"))))

(deftest ^:unit core-source-paths-includes-test-support
  (testing "FC/IS scanner includes src/boundary/test_support/core"
    (let [scanned (map str (fcis/core-source-paths))]
      (is (some #(re-find #"test_support/core" %) scanned)
          "expected src/boundary/test_support/core.clj to be scanned"))))

(deftest ^:unit core-source-paths-includes-app-layout
  (testing "FC/IS scanner includes src/boundary/<module>/core in an app layout (no libs/)"
    ;; A project scaffolded with `boundary new` has no libs/ tree — its modules
    ;; live under src/boundary/<module>/core/. Build a fixture and scan it via the
    ;; explicit-root arity (the default arity reads user.dir = monorepo root).
    (let [tmp      (io/file (System/getProperty "java.io.tmpdir")
                            (str "fcis-app-" (System/currentTimeMillis)))
          core-clj (io/file tmp "src" "boundary" "invoice" "core" "invoice.clj")]
      (try
        (io/make-parents core-clj)
        (spit core-clj "(ns boundary.invoice.core.invoice)\n(defn total [xs] (reduce + xs))\n")
        (let [scanned (map str (fcis/core-source-paths tmp))]
          (is (some #(re-find #"src/boundary/invoice/core/invoice\.clj" %) scanned)
              "expected the scaffolded app module's core file to be scanned"))
        (finally
          (doseq [f (reverse (file-seq tmp))] (.delete f)))))))

(deftest ^:unit scan-fq-calls-detects-runtime-nondeterminism
  (testing "FC/IS scanner flags runtime-dependent identity, time, environment, and timezone access in core"
    (let [violations (#'fcis/scan-fq-calls
                      "fake-file.clj"
                      (str "(ns example.core)\n"
                           "(java.util.UUID/randomUUID)\n"
                           "(java.time.Instant/now)\n"
                           "(java.time.LocalDate/now)\n"
                           "(java.time.ZoneId/systemDefault)\n"
                           "(java.lang.System/currentTimeMillis)\n"
                           "(java.lang.System/getProperty \"environment\")\n"
                           "(java.lang.ProcessHandle/current)\n"))
          symbols (set (map :symbol violations))]
      (is (contains? symbols "java.util.UUID/randomUUID"))
      (is (contains? symbols "java.time.Instant/now"))
      (is (contains? symbols "java.time.LocalDate/now"))
      (is (contains? symbols "java.time.ZoneId/systemDefault"))
      (is (contains? symbols "java.lang.System/currentTimeMillis"))
      (is (contains? symbols "java.lang.System/getProperty"))
      (is (contains? symbols "java.lang.ProcessHandle/current")))))

(deftest ^:unit scan-simple-static-calls-detects-imported-runtime-nondeterminism
  (testing "FC/IS scanner flags imported and implicit simple-class runtime access in core"
    (let [imports ["java.time.Instant"
                   "java.time.LocalDate"
                   "java.time.ZoneId"
                   "java.util.UUID"]
          violations (#'fcis/scan-simple-static-calls
                      "fake-file.clj"
                      (str "(ns example.core\n"
                           "  (:import (java.time Instant LocalDate ZoneId)\n"
                           "           (java.util UUID)))\n"
                           "(Instant/now)\n"
                           "(LocalDate/now)\n"
                           "(ZoneId/systemDefault)\n"
                           "(UUID/randomUUID)\n"
                           "(System/currentTimeMillis)\n"
                           "(ProcessHandle/current)\n")
                      imports)
          symbols (set (map :symbol violations))]
      (is (contains? symbols "java.time.Instant/now"))
      (is (contains? symbols "java.time.LocalDate/now"))
      (is (contains? symbols "java.time.ZoneId/systemDefault"))
      (is (contains? symbols "java.util.UUID/randomUUID"))
      (is (contains? symbols "java.lang.System/currentTimeMillis"))
      (is (contains? symbols "java.lang.ProcessHandle/current")))))
