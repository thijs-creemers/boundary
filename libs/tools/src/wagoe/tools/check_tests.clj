#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_tests.clj
;;
;; Detects placeholder and tautological test assertions that pass green but
;; provide no real coverage: (is true), (is (= true true)), predicates on
;; string literals like (is (some? "...")), and (is (not nil/false)).

(ns wagoe.tools.check-tests
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [edamame.core :as e]
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
                                       ;; for extent-scoped exemption filtering
                                       :offset  (.start matcher)
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

(defn- parse-forms
  "`raw` as data, read by edamame — the reader, not a lexer imitating one.

   Four review rounds of lexical scanning each ended on a reader-syntax corner
   the lexer missed: `#_\"note\"`, `#_#(…)`, a quote separated from its form by
   whitespace, metadata semantics. Reading settles all of them at once: `#_`
   is elided before we ever look, quoted forms arrive as data, and `^…` lands
   in (meta …). Verified against every test file in this repository — 380
   files, zero parse failures. Unknown reader tags pass their value through."
  [raw features]
  (e/parse-string-all raw
                      {:all          true
                       :auto-resolve (fn [a] (if (= a :current) (symbol "this.ns") (symbol (str a))))
                       :readers      (fn [_tag] identity)
                       :features     features
                       :read-cond    :allow}))

(def ^:private feature-branches
  "Both reader-conditional branches get scanned, and findings union.

   libs/tools runs its tests under Babashka (AGENTS.md's test surfaces), so a
   `#?(:bb (deftest …))` branch is the one that executes there — parsing with
   :clj alone made such a test invisible to the gate (BOU-365 review). Scanning
   both sides is surface-agnostic: a branch that runs anywhere is checked, and
   a placeholder in a branch that runs nowhere is still a placeholder."
  [#{:clj} #{:bb}])

(defn- deftest-forms
  "Every `(deftest …)`/`(t/deftest …)` form in `forms`, at any depth, not
   descending into `quote` or `comment` — a draft in a comment form and a
   deftest-shaped list in quoted data are never defined."
  [forms]
  (let [out (volatile! [])]
    (letfn [(walk [x]
              (when (coll? x)
                (let [head (when (seq? x) (first x))]
                  (cond
                    (and (symbol? head) (contains? #{"quote" "comment"} (name head)))
                    nil
                    (and (symbol? head) (= "deftest" (name head)))
                    (do (vswap! out conj x) (run! walk (rest x)))
                    :else (run! walk (seq x))))))]
      (run! walk forms))
    @out))

(defn- exempt?
  "Whether the deftest's *name symbol* — the second element, nothing later —
   carries the exact top-level `:wagoe/allow-placeholder` metadata key.
   Edamame merges `^…` into (meta …); scanning further into the body let a
   marked symbol anywhere in the first forms exempt the whole test
   (BOU-365 review, round 5)."
  [deftest-form]
  (let [nm (second deftest-form)]
    (boolean (and (symbol? nm) (:wagoe/allow-placeholder (meta nm))))))

(defn- evaluated-heads
  "Names of every operator position reachable at runtime under `form` —
   `quote`d and `comment`ed subtrees excluded."
  [form]
  (let [out (volatile! #{})]
    (letfn [(walk [x]
              (when (coll? x)
                (let [head (when (seq? x) (first x))]
                  (when (symbol? head)
                    (if (contains? #{"quote" "comment"} (name head))
                      nil
                      (do (vswap! out conj (name head))
                          (run! walk (rest x)))))
                  (when-not (and (seq? x) (symbol? (first x)))
                    (run! walk (seq x))))))]
      (walk form))
    @out))

(defn- tautological-assertion?
  "Whether the expression a single `is`/`are` actually asserts is an
   identical-argument equality. Only the asserted expression counts — a
   nested `(= x x)` in a message or under `false?` is someone's data, not a
   vacuous assertion. Collection arguments are excluded: `(= (rand) (rand))`
   is equal as forms and different as values. NaN needs no case — `=` on two
   read `##NaN`s is already false."
  [form]
  (let [head (and (seq? form) (symbol? (first form)) (name (first form)))
        asserted (case head
                   "is"  (second form)
                   "are" (first (drop 2 form))   ; (are [bindings] template …)
                   nil)]
    (boolean
     (and (seq? asserted)
          (symbol? (first asserted))
          (= "=" (name (first asserted)))
          (= 3 (count asserted))
          (not (coll? (second asserted)))
          (= (second asserted) (nth asserted 2))))))

(defn- assertion-sites
  "Every evaluated `is`/`are` form under `form`, for the tautology check."
  [form]
  (let [out (volatile! [])]
    (letfn [(walk [x]
              (when (coll? x)
                (let [head (when (seq? x) (first x))]
                  (cond
                    (and (symbol? head) (contains? #{"quote" "comment"} (name head))) nil
                    :else (do (when (and (symbol? head)
                                         (contains? #{"is" "are"} (name head)))
                                (vswap! out conj x))
                              (run! walk (seq x)))))))]
      (walk form))
    @out))

(defn scan-content-structural
  "Form-level findings the shape regexes cannot see (BOU-365): a deftest that
   executes no assertion (`is`/`are`/named helper), and an `is`/`are` whose
   asserted expression is an identical-argument equality."
  [file raw]
  (distinct
   (for [features feature-branches
         form (deftest-forms (parse-forms raw features))
         :when (not (exempt? form))
         :let [line (or (:row (meta form)) 0)]
         finding
         (concat
          ;; evaluated-heads yields bare names — (name 'a/b) is "b".
          (when (empty? (set/intersection
                         (evaluated-heads form)
                         (set/union assertion-heads assertion-helper-names)))
            [{:file (str file) :line line
              :content "deftest without any assertion (is/are/known helper)"}])
          (for [site (assertion-sites form)
                :when (tautological-assertion? site)]
            {:file (str file) :line (or (:row (meta site)) line)
             :content (str "tautology: " (pr-str (case (name (first site))
                                                   "is" (second site)
                                                   (first (drop 2 site)))))}))]
     finding)))

(defn exempted-extents
  "{:row :col :end-row :end-col} of every metadata-exempted deftest.

   The hatch exempts the test, so the shape regexes honour it too — and by
   source extent, not line: an exempt stub and a live placeholder sharing a
   line must not shield each other (BOU-365 review, round 4)."
  [raw]
  (distinct
   (for [features feature-branches
         form (deftest-forms (parse-forms raw features))
         :when (exempt? form)]
     (select-keys (meta form) [:row :col :end-row :end-col]))))

(defn- offset->row-col
  [^String raw offset]
  (let [before (subs raw 0 (min offset (count raw)))
        row    (inc (count (filter #(= \newline %) before)))
        col    (inc (- (count before) (inc (or (str/last-index-of before "\n") -1))))]
    [row col]))

(defn- within?
  [{:keys [row col end-row end-col]} [r c]]
  (and (or (> r row) (and (= r row) (>= c col)))
       (or (< r end-row) (and (= r end-row) (<= c end-col)))))

(defn- scan-file
  "Scan a file on disk for placeholder assertions.

   One row per (file, line): the shape regexes and the structural scan both
   see `(is (= true true))`, and two findings for one assertion reads as two
   problems (BOU-365 review, round 4)."
  [file]
  (let [raw     (slurp file)
        exempt  (exempted-extents raw)
        keep?   (fn [{:keys [offset]}]
                  (or (nil? offset)
                      (not-any? #(within? % (offset->row-col raw offset)) exempt)))]
    (->> (concat (filter keep? (scan-content file raw))
                 (scan-content-structural file raw))
         (map #(dissoc % :offset))
         (reduce (fn [{:keys [seen acc]} {:keys [file line] :as f}]
                   (if (seen [file line])
                     {:seen seen :acc acc}
                     {:seen (conj seen [file line]) :acc (conj acc f)}))
                 {:seen #{} :acc []})
         :acc)))

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
