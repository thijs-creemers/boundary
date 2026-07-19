(ns boundary.tools.check-tests-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [boundary.tools.check-tests :as ct]))

(defn- scan-meta [src]
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "check-tests-meta-" (System/currentTimeMillis) "-" (hash src) ".clj"))]
    (try
      (spit f src)
      (#'ct/scan-file-meta f)
      (finally (.delete f)))))

(deftest ^:unit flags-metadata-after-deftest-name
  (testing "metadata on the line after the test name is flagged"
    (let [ms (scan-meta "(ns x)\n(deftest my-test\n  ^:unit\n  (is true))\n")]
      (is (= 1 (count ms)))
      (is (= "my-test" (:name (first ms))))))
  (testing "metadata after the name on the same line is also flagged"
    (is (= 1 (count (scan-meta "(ns x)\n(deftest my-test ^:integration (is true))\n"))))))

(deftest ^:unit ignores-correctly-placed-metadata
  (testing "metadata before the name is correct — not flagged"
    (is (empty? (scan-meta "(ns x)\n(deftest ^:unit my-test\n  (is true))\n"))))
  (testing "stacked metadata before the name is correct"
    (is (empty? (scan-meta "(ns x)\n(deftest ^:unit ^:security my-test\n  (is true))\n"))))
  (testing "deftest with no metadata is not flagged"
    (is (empty? (scan-meta "(ns x)\n(deftest my-test\n  (is true))\n")))))

(deftest ^:unit ignores-commented-and-string-occurrences
  (testing "a commented-out misplaced deftest is ignored (stripped source)"
    (is (empty? (scan-meta "(ns x)\n;; (deftest old-test\n;;   ^:unit\n;;   (is true))\n"))))
  (testing "^: inside a string is not mistaken for metadata"
    (is (empty? (scan-meta "(ns x)\n(deftest my-test\n  (is (= \"^:unit\" (str x))))\n")))))
