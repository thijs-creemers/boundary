#!/usr/bin/env bb
;; libs/tools/src/boundary/tools/check_tests.clj
;;
;; Detects placeholder and tautological test assertions that pass green but
;; provide no real coverage: (is true), (is (= true true)), predicates on
;; string literals like (is (some? "...")), and (is (not nil/false)).

(ns boundary.tools.check-tests
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.tools.ansi :as ansi]
            [boundary.tools.parsing :as parsing]))

;; ---------------------------------------------------------------------------
;; Placeholder patterns (multiline-aware)
;; ---------------------------------------------------------------------------

;; Skip-sentinel convention
;; ~~~~~~~~~~~~~~~~~~~~~~~~
;; Skip-sentinel assertions like (is (not (redis-available?)) "Redis not available")
;; inside the else branch of an if are tautological by design. They exist solely to
;; ensure each test function has at least one assertion for the Kaocha reporter.
;; These are an accepted exception — the checker does not flag them because they are
;; structurally different from literal placeholders: they contain a function call
;; (e.g. redis-available?) rather than a bare literal like true, nil, or false.

(def ^:private placeholder-patterns
  "Regex patterns matching placeholder assertions across line boundaries.
   Applied to stripped source (no comments/strings) to avoid false positives.
   All forms allow an optional trailing message argument.

   After stripping, string literals become whitespace, so predicates whose
   only argument is whitespace indicate tautological assertions on a string
   literal (e.g. (is (some? \"always truthy\")) -> (is (some?              ))).

   Note: Skip-sentinel assertions like (is (not (condition?)) \"Resource not available\")
   inside the else branch of an if are tautological by design. They exist solely to ensure
   each test function has at least one assertion for the Kaocha reporter. These are an
   accepted exception — the checker does not flag them because they are structurally
   different from literal placeholders.

   (is (instance? Exception e)) inside a catch block is always true but the
   checker detects the pattern structurally; review context to confirm."
  [;; (is true) / (is true "msg")
   #"(?s)\(\s*is\s+true(?=[\s)])[^)]*\)"
   ;; (is (= true true)) / (is (= true true) "msg")
   #"(?s)\(\s*is\s+\(\s*=\s+true\s+true\s*\)[^)]*\)"
   ;; (is (some? "literal")) — after stripping, arg is only whitespace
   #"(?s)\(\s*is\s+\(\s*some\?\s+\)[^)]*\)"
   ;; (is (string? "literal")) — after stripping, arg is only whitespace
   #"(?s)\(\s*is\s+\(\s*string\?\s+\)[^)]*\)"
   ;; (is (not nil)) / (is (not false)) — always true
   #"(?s)\(\s*is\s+\(\s*not\s+nil\s*\)[^)]*\)"
   #"(?s)\(\s*is\s+\(\s*not\s+false\s*\)[^)]*\)"
   ;; (is (instance? Exception <sym>)) — always true inside catch Exception
   #"(?s)\(\s*is\s+\(\s*instance\?\s+Exception\s+\w+\s*\)[^)]*\)"])

;; ---------------------------------------------------------------------------
;; File scanning
;; ---------------------------------------------------------------------------

(defn- test-clj-files
  "Find all .clj files under libs/*/test/ and test/."
  []
  (let [root      (io/file (System/getProperty "user.dir"))
        libs-dir  (io/file root "libs")
        top-test  (io/file root "test")
        lib-tests (when (.exists libs-dir)
                    (->> (.listFiles libs-dir)
                         (filter #(.isDirectory %))
                         (mapcat (fn [lib-dir]
                                   (let [test-dir (io/file lib-dir "test")]
                                     (when (.exists test-dir)
                                       (file-seq test-dir)))))))
        top-tests (when (.exists top-test)
                    (file-seq top-test))]
    (->> (concat lib-tests top-tests)
         (filter #(and (.isFile %)
                       (str/ends-with? (.getName %) ".clj"))))))

(defn- offset->line-number
  "Convert a character offset into a 1-based line number."
  [content offset]
  (inc (count (filter #(= \newline %) (subs content 0 (min offset (count content)))))))

(defn- scan-file
  "Scan a file for placeholder assertions in executable code.
   Comments and string contents are stripped first so that docstrings
   and comment text like ';; changed from (is true)' are not flagged.
   Returns seq of match maps."
  [file]
  (let [raw     (slurp file)
        cleaned (parsing/strip-comments-and-strings raw)]
    (->> placeholder-patterns
         (mapcat (fn [pat]
                   (let [matcher (re-matcher pat cleaned)]
                     (loop [matches []]
                       (if (.find matcher)
                         (recur (conj matches
                                      {:file    (str file)
                                       :line    (offset->line-number raw (.start matcher))
                                       :content (str/trim (str/replace (.group matcher) #"\s+" " "))}))
                         matches)))))
         (distinct))))

;; ---------------------------------------------------------------------------
;; Misplaced deftest metadata (BOU-184)
;; ---------------------------------------------------------------------------

(def ^:private misplaced-meta-pattern
  "A deftest whose metadata sits AFTER the test name — e.g.
     (deftest foo
       ^:unit
       ...)
   The reader attaches that metadata to the following body form (the testing
   block), not the test var, so `--focus-meta` silently skips the test. The
   correct form is (deftest ^:unit foo ...). Matched on stripped source so
   commented-out or string occurrences are ignored. `\\^[:{]` covers both the
   `^:keyword` and `^{...}` metadata forms."
  #"(?s)\(\s*deftest\s+([^\s()^]+)\s+\^[:{]")

(defn- scan-file-meta
  "Return match maps for deftest forms with metadata placed after the name."
  [file]
  (let [raw     (slurp file)
        cleaned (parsing/strip-comments-and-strings raw)
        matcher (re-matcher misplaced-meta-pattern cleaned)]
    (loop [matches []]
      (if (.find matcher)
        (recur (conj matches {:file (str file)
                              :line (offset->line-number raw (.start matcher))
                              :name (.group matcher 1)}))
        matches))))

;; ---------------------------------------------------------------------------
;; Entry points
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (let [files   (test-clj-files)
        matches (mapcat scan-file files)]
    (if (seq matches)
      (do
        (println (ansi/red "Placeholder test assertions found:"))
        (println)
        (doseq [{:keys [file line content]} matches]
          (println (str "  " file ":" line ": " content)))
        (println)
        (println (str (count matches) " placeholder(s) found. Replace with meaningful assertions."))
        (System/exit 1))
      (do
        (println (ansi/green "No placeholder tests found.") (str (count files) " test file(s) scanned."))
        (System/exit 0)))))

(defn check-deftest-metadata
  "Flag deftest forms whose metadata is placed after the name (attaching to the
   body form, so `--focus-meta` skips them). Correct form: (deftest ^:meta name).
   BOU-184."
  [& _args]
  (let [files   (test-clj-files)
        matches (mapcat scan-file-meta files)]
    (if (seq matches)
      (do
        (println (ansi/red "Misplaced deftest metadata found (attaches to the body, not the test var):"))
        (println)
        (doseq [{:keys [file line name]} matches]
          (println (str "  " file ":" line ": (deftest " name " …) — move the ^:meta before the name")))
        (println)
        (println (str (count matches) " misplaced. Write (deftest ^:meta name …) so --focus-meta selects the test."))
        (System/exit 1))
      (do
        (println (ansi/green "No misplaced deftest metadata.") (str (count files) " test file(s) scanned."))
        (System/exit 0)))))
