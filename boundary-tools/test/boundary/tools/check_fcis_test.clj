(ns boundary.tools.check-fcis-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.tools.check-fcis :as fcis]))

(deftest core-source-paths-includes-libs
  (testing "FC/IS scanner includes libs/*/src/boundary/*/core files"
    (let [scanned (map str (fcis/core-source-paths))]
      (is (some #(re-find #"libs/.+/src/boundary/.+/core/" %) scanned)
          "expected at least one libs/<lib>/src/boundary/<lib>/core/ file"))))

(deftest core-source-paths-includes-test-support
  (testing "FC/IS scanner includes src/boundary/test_support/core"
    (let [scanned (map str (fcis/core-source-paths))]
      (is (some #(re-find #"test_support/core" %) scanned)
          "expected src/boundary/test_support/core.clj to be scanned"))))
