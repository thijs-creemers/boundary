#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_tests.clj
;;
;; Detects placeholder and tautological test assertions that pass green but
;; provide no real coverage: (is true), (is (= true true)), predicates on
;; string literals like (is (some? "...")), and (is (not nil/false)).

(ns wagoe.tools.check-tests
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.parsing :as parsing]))

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

(defn scan-content
  "Scan `raw` source for placeholder assertions in executable code, reporting
   `file` as the location. Comments and string contents are stripped first so
   that docstrings and comment text like ';; changed from (is true)' are not
   flagged. Returns seq of match maps.

   Split out of `scan-file` and made public so a test can prove this gate
   still detects a placeholder (BOU-250). `-main` exits the process, so it
   cannot serve as the seam."
  [file raw]
  (let [cleaned (parsing/strip-comments-and-strings raw)]
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
;; Form-level analysis (BOU-365)
;; ---------------------------------------------------------------------------

(def assertion-heads
  "Operators that make a deftest assert something."
  #{"is" "are"})

(def assertion-helper-names
  "Helpers that assert internally, matched on the name after any alias — so
   `snapshot-io/check-snapshot!` and a re-aliased copy both count. The named
   list the ticket asks for: without it the snapshot suite's 11 real tests
   would be the gate's first 11 false positives, and a gate that opens with
   11 wrong findings is a gate people learn to ignore (BOU-365)."
  #{"check-snapshot!"})

(defn- name-part [head] (last (str/split head #"/")))

(defn- blank-spans
  "`text` with each {:start :end} span replaced by spaces, newlines kept."
  [^String text spans]
  (let [sb (StringBuilder. text)]
    (doseq [{:keys [start end]} spans
            i (range start (min end (count text)))
            :when (not= \newline (.charAt sb i))]
      (.setCharAt sb i \space))
    (str sb)))

(defn- unevaluated-blanked
  "`text` with `#_`-discarded, quoted and `(comment …)` regions blanked.

   What remains is what actually runs — so an `(is …)` inside a discard no
   longer counts as an assertion, a draft deftest inside `#_(do …)` is not
   scanned at all, and a `(= x x)` inside quoted data is not a tautology,
   however deeply any of them nests (BOU-365 review)."
  [text]
  (blank-spans text
               (concat (parsing/unevaluated-extents text)
                       ;; Both quote spellings and comment forms: `'(…)` is
                       ;; reader syntax (unevaluated-extents), `(quote …)` and
                       ;; `(comment …)` are forms with heads.
                       (for [head ["comment" "quote"]
                             e    (parsing/form-extents text head)
                             :when (:end e)]
                         (select-keys e [:start :end])))))

(defn- exempt-by-metadata?
  "Whether the deftest carries the `:wagoe/allow-placeholder` key in its
   metadata — the chunks between the operator and the test name, nowhere
   else, spelled exactly. Walks the `^…` entries structurally: a nested
   metadata map (`^{:kaocha.testable/meta {…}}`) is one balanced chunk, so a
   marker after it is still seen; a misspelling is not an exemption; and a
   body that mentions the keyword as data is not one either."
  [^String body]
  (let [n (count body)
        after-head (loop [i 1]
                     (if (and (< i n) (Character/isWhitespace (.charAt body i)))
                       (recur (inc i))
                       (loop [j i]
                         (if (and (< j n) (not (Character/isWhitespace (.charAt body j))))
                           (recur (inc j)) j))))]
    (loop [i after-head]
      (let [i (loop [i i]
                (if (and (< i n) (Character/isWhitespace (.charAt body i)))
                  (recur (inc i)) i))]
        (when (and (< i n) (= \^ (.charAt body i)))
          (let [chunk-end (if (and (< (inc i) n) (= \{ (.charAt body (inc i))))
                            (loop [e (+ i 2), depth 1]
                              (cond (zero? depth) e
                                    (>= e n)      n
                                    :else (recur (inc e) (case (.charAt body e)
                                                           \{ (inc depth)
                                                           \} (dec depth)
                                                           depth))))
                            (loop [e (inc i)]
                              (if (and (< e n) (not (Character/isWhitespace (.charAt body e))))
                                (recur (inc e)) e)))
                chunk (subs body i chunk-end)]
            (or (some? (re-find #":wagoe/allow-placeholder(?![\w?!*+<>=-])" chunk))
                (recur chunk-end))))))))

(defn- tautologies
  "Identical-token `(= x x)` matches inside the is/are forms of `body`.
   `body` must already have unevaluated regions blanked, so quoted data —
   at any nesting depth — cannot match."
  [body]
  (for [{:keys [start end]} (concat (parsing/form-extents body "is")
                                    (parsing/form-extents body "are"))
        :when end
        [m _] (re-seq #"\(\s*=\s+([^\s()\[\]{}]+)\s+\1\s*\)" (subs body start end))]
    m))

(defn scan-content-structural
  "Form-level findings the shape regexes cannot see (BOU-365).

   - a deftest none of whose calls is an assertion or a named helper —
     a test that asserts nothing passes by definition;
   - an identical-token `(= x x)` inside its is/are forms — true whatever
     x is, which covers `(is (= 1 1))` and the `are`-tautology alike.

   `(t/deftest …)` counts; `#_`-discarded and `(comment …)`-wrapped drafts
   do not — they are never defined, so they cannot pass vacuously."
  [file raw]
  ;; Blanking unevaluated regions FIRST answers three review findings at once:
  ;; a deftest inside #_(do …) never appears as an extent, an (is …) inside a
  ;; discard is not an assertion, and quoted (= x x) data is not a tautology.
  (let [live (unevaluated-blanked (parsing/strip-comments-and-strings raw))]
    (for [{:keys [start end line]} (parsing/form-extents live "deftest")
          :when end
          :let [body (subs live start end)]
          :when (not (exempt-by-metadata? body))
          finding
          (concat
           (when (empty? (set/intersection
                          (set (map (comp name-part :head) (parsing/call-forms body)))
                          (set/union assertion-heads assertion-helper-names)))
             [{:file (str file) :line line
               :content "deftest without any assertion (is/are/known helper)"}])
           (for [m (tautologies body)]
             {:file (str file) :line line
              :content (str "tautology: " (str/trim (str/replace m #"\s+" " ")))}))]
      finding)))

(defn exempted-line-ranges
  "[start-line end-line] of every deftest exempted by metadata.

   The escape hatch exempts the *test*, so the shape regexes must honour it
   too — `(deftest ^:wagoe/allow-placeholder stub (is true))` was exempt from
   the structural scan and still failed on the regex one (BOU-365 review)."
  [raw]
  (let [live (unevaluated-blanked (parsing/strip-comments-and-strings raw))]
    (for [{:keys [start end line]} (parsing/form-extents live "deftest")
          :when (and end (exempt-by-metadata? (subs live start end)))]
      [line (+ line (count (filter #(= \newline %) (subs live start end))))])))

(defn- scan-file
  "Scan a file on disk for placeholder assertions."
  [file]
  (let [raw     (slurp file)
        exempt  (exempted-line-ranges raw)
        exempt? (fn [{:keys [line]}]
                  (some (fn [[a b]] (<= a line b)) exempt))]
    (concat (remove exempt? (scan-content file raw))
            (scan-content-structural file raw))))

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

(defn scan-content-meta
  "Return match maps for deftest forms in `raw` with metadata placed after the
   name, reporting `file` as the location.

   Public seam for the firing test (BOU-250) — `check-deftest-metadata` exits
   the process, so it cannot be called from a test."
  [file raw]
  (let [cleaned (parsing/strip-comments-and-strings raw)
        matcher (re-matcher misplaced-meta-pattern cleaned)]
    (loop [matches []]
      (if (.find matcher)
        (recur (conj matches {:file (str file)
                              :line (offset->line-number raw (.start matcher))
                              :name (.group matcher 1)}))
        matches))))

(defn- scan-file-meta
  "Scan a file on disk for misplaced deftest metadata."
  [file]
  (scan-content-meta file (slurp file)))

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

;; ---------------------------------------------------------------------------
;; Pyramid tag gate (BOU-166): every deftest carries exactly one of
;; ^:unit / ^:integration / ^:contract. Cross-cutting tags (^:security, ^:e2e,
;; …) may coexist. Not-yet-backfilled test files are exempted via
;; .wagoe/check-test-tags.edn until they are tagged.
;; ---------------------------------------------------------------------------

(def ^:private pyramid-tags
  "The mutually-exclusive test-pyramid metadata keywords."
  #{"unit" "integration" "contract"})

(def ^:private deftest-meta-pattern
  "Matches a top-level (deftest <meta ...> name), capturing the metadata region
   (group 1) and the test name (group 2). The anchor `^\\(deftest` is deliberate:
   real registered tests are top-level (column 0); an indented (deftest …) is
   inside a (comment …), let, or docstring and is not a live test.

   The metadata region accepts both keyword shorthand (`^:unit`) and map form
   (`^{:kaocha.testable/meta {:unit true}}`, one level of nesting), so a deftest
   using the map form is still scanned — it counts zero *keyword* pyramid tags
   and is therefore flagged to use the `^:keyword` shorthand the gate and
   `--focus-meta` rely on."
  #"(?m)^\(deftest((?:\s+(?:\^:[a-zA-Z][\w?*!+<>='-]*|\^\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}))*)\s+([a-zA-Z][^\s()]*)")

(defn read-tags-config
  "Read the optional .wagoe/check-test-tags.edn allowlist. Returns a set of
   repo-relative path prefixes; any test file under one is exempt from the
   pyramid-tag requirement (gradual backfill)."
  []
  (let [f (io/file (System/getProperty "user.dir") ".wagoe" "check-test-tags.edn")]
    (if (.exists f)
      (try (set (map str (:allow-untagged (edn/read-string (slurp f)))))
           (catch Exception _ #{}))
      #{})))

(defn- relative-path
  "File path relative to the repo root (user.dir), for stable prefix matching."
  [file]
  (let [root (str (System/getProperty "user.dir") "/")
        p    (str file)]
    (if (str/starts-with? p root) (subs p (count root)) p)))

(defn- exempt-file? [allow file]
  (let [rel (relative-path file)]
    (some #(str/starts-with? rel %) allow)))

(defn scan-content-tags
  "Return {:file :line :name :count} for each deftest in `raw` whose pyramid-tag
   count is not exactly 1, reporting `file` as the location.

   Public seam for the firing test (BOU-250) — `check-test-tags` exits the
   process, so it cannot be called from a test."
  [file raw]
  (let [cleaned (parsing/strip-comments-and-strings raw)
        matcher (re-matcher deftest-meta-pattern cleaned)]
    (loop [violations []]
      (if (.find matcher)
        (let [meta-region (.group matcher 1)
              n (count (filter pyramid-tags (re-seq #"(?<=\^:)[a-zA-Z][\w?*!+<>='-]*" meta-region)))]
          (recur (if (= 1 n)
                   violations
                   (conj violations {:file  (str file)
                                     :line  (offset->line-number raw (.start matcher))
                                     :name  (.group matcher 2)
                                     :count n}))))
        violations))))

(defn- scan-file-tags
  "Scan a file on disk for deftests without exactly one pyramid tag."
  [file]
  (scan-content-tags file (slurp file)))

(defn check-test-tags
  "Enforce exactly one pyramid tag (^:unit / ^:integration / ^:contract) per
   deftest. Files listed in .wagoe/check-test-tags.edn :allow-untagged are
   skipped (gradual backfill, BOU-166)."
  [& _args]
  (let [allow    (read-tags-config)
        files    (remove #(exempt-file? allow %) (test-clj-files))
        matches  (mapcat scan-file-tags files)]
    (if (seq matches)
      (do
        (println (ansi/red "Deftests missing exactly one pyramid tag (^:unit/^:integration/^:contract):"))
        (println)
        (doseq [{:keys [file line name count]} matches]
          (println (str "  " file ":" line ": (deftest " name " …) has " count " pyramid tag(s)")))
        (println)
        (println (str (clojure.core/count matches)
                      " deftest(s) need exactly one pyramid tag. Add ^:unit, ^:integration, or ^:contract."))
        (System/exit 1))
      (do
        (println (ansi/green "All deftests carry exactly one pyramid tag.")
                 (str (clojure.core/count files) " enforced test file(s) scanned."))
        (System/exit 0)))))
