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

;; =============================================================================
;; Review findings on the first cut (BOU-365)
;; =============================================================================

(deftest ^:unit string-argument-placeholders-still-match-after-stripping
  ;; The lexer rewrite briefly kept string delimiters, which silently turned
  ;; off the whitespace-argument patterns for (is (some? "x")) and
  ;; (is (string? "x")).
  (is (= 1 (count (ct/scan-content "s.clj" "(deftest x (is (some? \"lit\")))"))))
  (is (= 1 (count (ct/scan-content "s.clj" "(deftest x (is (string? \"lit\")))")))))

(deftest ^:unit discarded-and-commented-drafts-are-not-placeholders
  (is (empty? (ct/scan-content-structural
               "d.clj" "(comment\n  (deftest draft (todo)))")))
  (is (empty? (ct/scan-content-structural
               "d.clj" "#_(deftest draft (todo))")))
  (testing "a live deftest next to a discarded one is still checked"
    (is (= 1 (count (ct/scan-content-structural
                     "d.clj" "#_(deftest draft (todo))\n(deftest live (setup))"))))))

(deftest ^:unit an-alias-qualified-deftest-is-still-a-deftest
  (is (= 1 (count (ct/scan-content-structural
                   "q.clj" "(t/deftest empty-one (setup))")))))

(deftest ^:unit quoted-equality-data-is-not-a-tautology
  (is (empty? (ct/scan-content-structural
               "g.clj" "(deftest gen (is (= '(= x x) actual)))")))
  (is (empty? (ct/scan-content-structural
               "g.clj" "(deftest gen (is (= (quote (= x x)) actual)))")))
  (testing "an executed identical-token equality is still flagged"
    (is (= 1 (count (ct/scan-content-structural
                     "g.clj" "(deftest t (is (= x x)))"))))))

(deftest ^:unit the-marker-exempts-only-from-metadata-position
  (is (seq (ct/scan-content-structural
            "m.clj" "(deftest sneaky (log :wagoe/allow-placeholder))"))
      "the keyword as body data is not an exemption")
  (is (empty? (ct/scan-content-structural
               "m.clj" "(deftest ^:wagoe/allow-placeholder stub (todo))")))
  (is (empty? (ct/scan-content-structural
               "m.clj" "(deftest ^{:wagoe/allow-placeholder true} stub (todo))"))))

;; =============================================================================
;; Review round 3 (BOU-365)
;; =============================================================================

(deftest ^:unit the-escape-hatch-covers-the-regex-findings-too
  ;; (deftest ^:wagoe/allow-placeholder stub (is true)) was exempt from the
  ;; structural scan and still failed the shape regexes — one hatch, both scans.
  (let [src "(deftest ^:wagoe/allow-placeholder stub (is true))\n(deftest live (is true))"
        exempt (ct/exempted-line-ranges src)]
    (is (= [[1 1]] exempt))
    (testing "the live placeholder on the next line is still caught"
      (is (some #(= 2 (:line %)) (ct/scan-content "x.clj" src))))))

(deftest ^:unit a-discarded-assertion-does-not-count-as-one
  (is (= 1 (count (ct/scan-content-structural
                   "x.clj" "(deftest x #_(is (= 1 2)) (setup))")))
      "the only assertion is discarded — the test runs nothing")
  (is (empty? (ct/scan-content-structural
               "x.clj" "(deftest x #_(is (= 1 2)) (is (pos? (f))))"))
      "a live assertion next to a discarded one still counts"))

(deftest ^:unit a-draft-inside-a-discarded-container-is-not-scanned
  (is (empty? (ct/scan-content-structural
               "x.clj" "#_(do (deftest draft (setup)))"))))

(deftest ^:unit deeply-nested-quoted-equality-is-not-a-tautology
  (is (empty? (ct/scan-content-structural
               "x.clj" "(deftest gen (is (= '(foo (= x x)) actual)))"))))

(deftest ^:unit metadata-parsing-is-structural
  (testing "a marker after a nested metadata map is seen"
    (is (empty? (ct/scan-content-structural
                 "x.clj"
                 "(deftest ^{:kaocha.testable/meta {:unit true}} ^:unit ^:wagoe/allow-placeholder stub (todo))"))))
  (testing "a misspelled marker is not an exemption"
    (is (seq (ct/scan-content-structural
              "x.clj" "(deftest ^:wagoe/allow-placeholder-typo stub (todo))")))))
