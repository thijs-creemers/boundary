(ns wagoe.tools.check-tests-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [wagoe.tools.check-tests :as ct]))

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

;; ---------------------------------------------------------------------------
;; Pyramid tag gate (BOU-166)
;; ---------------------------------------------------------------------------

(defn- scan-tags [src]
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "check-tags-" (System/currentTimeMillis) "-" (hash src) ".clj"))]
    (try (spit f src) (#'ct/scan-file-tags f)
         (finally (.delete f)))))

(deftest ^:unit flags-deftest-without-pyramid-tag
  (testing "a deftest with no pyramid tag is flagged"
    (let [vs (scan-tags "(ns x)\n(deftest foo (is true))\n")]
      (is (= 1 (count vs)))
      (is (= 0 (:count (first vs))))
      (is (= "foo" (:name (first vs)))))))

(deftest ^:unit accepts-exactly-one-pyramid-tag
  (testing "each pyramid tag alone passes"
    (is (empty? (scan-tags "(ns x)\n(deftest ^:unit a (is true))\n")))
    (is (empty? (scan-tags "(ns x)\n(deftest ^:integration b (is true))\n")))
    (is (empty? (scan-tags "(ns x)\n(deftest ^:contract c (is true))\n"))))
  (testing "a cross-cutting tag alongside one pyramid tag is fine, any order"
    (is (empty? (scan-tags "(ns x)\n(deftest ^:unit ^:security a (is true))\n")))
    (is (empty? (scan-tags "(ns x)\n(deftest ^:security ^:unit a (is true))\n")))))

(deftest ^:unit flags-multiple-pyramid-tags
  (testing "two pyramid tags on one deftest is flagged"
    (let [vs (scan-tags "(ns x)\n(deftest ^:unit ^:integration a (is true))\n")]
      (is (= 1 (count vs)))
      (is (= 2 (:count (first vs)))))))

(deftest ^:unit tag-scan-ignores-comments-and-strings
  (testing "a commented-out or string-embedded deftest is not scanned"
    (is (empty? (scan-tags "(ns x)\n;; (deftest foo (is true))\n(deftest ^:unit real (is true))\n")))
    (is (empty? (scan-tags "(ns x)\n(def s \"(deftest foo ...)\")\n(deftest ^:unit real (is true))\n")))))

(deftest ^:unit tag-scan-handles-map-form-metadata
  (testing "a deftest with a keyword tag + a trailing kaocha map is validated (1 pyramid), not skipped"
    (is (empty? (scan-tags "(ns x)\n(deftest ^:unit ^{:kaocha.testable/meta {:unit true}} a (is true))\n"))))
  (testing "a deftest with ONLY map-form meta counts zero keyword pyramid tags -> flagged (use ^:keyword)"
    (let [vs (scan-tags "(ns x)\n(deftest ^{:kaocha.testable/meta {:unit true}} a (is true))\n")]
      (is (= 1 (count vs)))
      (is (= 0 (:count (first vs)))))))

;; =============================================================================
;; Form-level analysis (BOU-365) — the ticket's four planted placeholders
;; =============================================================================

(def ^:private planted
  (str "(deftest ^:unit does-nothing\n"
       "  (let [x (+ 1 1)] x))\n"
       "(deftest ^:unit tautology\n"
       "  (is (= 1 1)))\n"
       "(deftest ^:unit are-tautology\n"
       "  (are [x] (= x x) 1 2 3))\n"
       "(deftest ^:unit split\n"
       "  (is\n   true))\n"))

(deftest ^:unit all-four-planted-placeholders-are-reported
  ;; The seven regexes found one of these four. Form-level analysis finds the
  ;; other three: a deftest with no assertion at all, and identical-token
  ;; tautologies in `is` and `are`.
  (let [structural (ct/scan-content-structural "planted.clj" planted)
        regex      (ct/scan-content "planted.clj" planted)
        lines      (set (map :line (concat structural regex)))]
    (is (contains? lines 1) "no-assertion deftest")
    (is (contains? lines 3) "(is (= 1 1)) tautology")
    (is (contains? lines 5) "are-tautology")
    (is (contains? lines 8) "(is ... true) split placeholder — the match starts at (is")))

(deftest ^:unit assertion-helpers-are-recognised-by-name
  (testing "a deftest asserting through a named helper is not a placeholder"
    (is (empty? (ct/scan-content-structural
                 "snap.clj"
                 "(deftest snap (snapshot-io/check-snapshot! :k (f)))"))))
  (testing "the helper list is what makes that pass — an unknown helper fails"
    (is (seq (ct/scan-content-structural
              "snap.clj"
              "(deftest snap (snapshot-io/verify-somehow! :k (f)))")))))

(deftest ^:unit the-placeholder-escape-hatch-is-explicit-metadata
  (is (empty? (ct/scan-content-structural
               "stub.clj"
               "(deftest ^:wagoe/allow-placeholder stub (todo))"))))

(deftest ^:unit an-escaped-quote-inside-a-string-does-not-blank-the-assertion
  ;; The old three-pass stripper ate the \" inside the string as a character
  ;; literal, broke the quote pairing, and blanked real code after it — on
  ;; prometheus_test.clj that swallowed an (is ...) whole and made a real test
  ;; look like a placeholder (BOU-365).
  (is (empty? (ct/scan-content-structural
               "esc.clj"
               "(deftest esc\n  (is (str/includes? t \"path=\\\"a\\\\b\\\"\")))"))))
