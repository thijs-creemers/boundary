#!/usr/bin/env bb
;; boundary-tools/src/boundary/tools/check_fcis.clj
;;
;; FC/IS boundary enforcement: ensures core/ namespaces never import
;; shell code, I/O libraries, logging, or database drivers.
;; See ADR-021 for rationale.

(ns boundary.tools.check-fcis
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.tools.ansi :as ansi]
            [boundary.tools.parsing :as parsing]))

;; ---------------------------------------------------------------------------
;; Forbidden patterns — any :require in a core/ namespace matching these
;; constitutes an FC/IS violation.
;; ---------------------------------------------------------------------------

(def ^:private forbidden-require-patterns
  "Patterns that must never appear in core namespace :require vectors.
   Covers shell namespaces, I/O, logging, database, HTTP, and caching libraries."
  [#"\.shell\."
   #"^clojure\.tools\.logging$"
   #"^clojure\.java\.io$"
   #"^clojure\.java\.shell$"
   #"^clojure\.java\.jdbc"
   #"^next\.jdbc"
   #"^clj-http"
   #"^org\.httpkit"
   #"^ring\."
   #"^hikari-cp\."
   #"^taoensso\.carmine"])

(def ^:private forbidden-import-packages
  "Java class patterns that must never appear in core namespace :import vectors.
   Targets I/O, database connection, and network access classes.
   Value types like java.sql.Timestamp are allowed (pure type coercion)."
  [#"^java\.sql\.DriverManager$"
   #"^java\.sql\.Connection$"
   #"^java\.sql\.Statement$"
   #"^java\.sql\.PreparedStatement$"
   #"^java\.sql\.CallableStatement$"
   #"^java\.sql\.ResultSet$"
   #"^javax\.sql\."
   #"^java\.net\.http\."
   #"^java\.io\.File$"
   #"^java\.io\.FileInputStream$"
   #"^java\.io\.FileOutputStream$"
   #"^java\.io\.BufferedWriter$"
   #"^java\.io\.BufferedReader$"])

(def ^:private forbidden-fq-patterns
  "Fully-qualified symbol prefixes that must never appear in core namespace bodies.
   Catches calls even without a :require or :import.
   Applied to stripped source (no comments/strings).
   Value types like java.sql.Timestamp are allowed (pure type coercion)."
  [#"clojure\.tools\.logging/"
   #"clojure\.java\.io/"
   #"clojure\.java\.shell/"
   #"next\.jdbc/"
   #"clj-http\.\w+/"
   #"org\.httpkit\.\w+/"
   #"ring\.\w+/"
   #"hikari-cp\.\w+/"
   #"taoensso\.carmine/"
   #"java\.io\.File\b"
   #"java\.io\.FileInputStream"
   #"java\.io\.FileOutputStream"
   #"java\.io\.BufferedWriter"
   #"java\.io\.BufferedReader"
   #"java\.sql\.DriverManager"
   #"java\.sql\.Connection\b"
   #"java\.sql\.Statement\b"
   #"java\.sql\.PreparedStatement"
   #"java\.sql\.CallableStatement"
   #"java\.sql\.ResultSet"
   #"javax\.sql\.\w+"
   #"java\.net\.http\.\w+"
   #"java\.util\.UUID/randomUUID"
   #"java\.time\.Instant/now"
   #"java\.time\.LocalDate/now"
   #"java\.time\.LocalDateTime/now"
   #"java\.time\.OffsetDateTime/now"
   #"java\.time\.ZonedDateTime/now"
   #"java\.time\.ZoneId/systemDefault"
   #"java\.lang\.System/currentTimeMillis"
   #"java\.lang\.System/getProperty"
   #"java\.lang\.ProcessHandle/current"])

(def ^:private forbidden-call-patterns
  "Bare Clojure core function calls that perform I/O and must never
   appear in core namespaces. Matched as (fn-name to ensure they are
   calls, not parts of other symbols. Applied to stripped source."
  [#"\(\s*slurp\s"
   #"\(\s*spit\s"])

(def ^:private forbidden-static-methods-by-class
  "Forbidden static runtime accessors keyed by fully-qualified class name.
   These are checked both in fully-qualified form and in imported/simple form."
  {"java.util.UUID" ["randomUUID"]
   "java.time.Instant" ["now"]
   "java.time.LocalDate" ["now"]
   "java.time.LocalDateTime" ["now"]
   "java.time.OffsetDateTime" ["now"]
   "java.time.ZonedDateTime" ["now"]
   "java.time.ZoneId" ["systemDefault"]
   "java.lang.System" ["currentTimeMillis" "getProperty"]
   "java.lang.ProcessHandle" ["current"]})

(def ^:private default-static-class-aliases
  "Java classes available without an explicit :import that still expose
   forbidden runtime accessors in core namespaces."
  {"System" "java.lang.System"
   "ProcessHandle" "java.lang.ProcessHandle"})

(def ^:private allowed-fq-violations
  "Temporary BOU-15 allowlist for known remaining runtime-dependent core calls.
   Each entry should be removed as the corresponding namespace is migrated."
  [])

(defn- forbidden-require?
  "Returns true if `ns-str` matches any forbidden require pattern."
  [ns-str]
  (some #(re-find % ns-str) forbidden-require-patterns))

;; ---------------------------------------------------------------------------
;; File scanning
;; ---------------------------------------------------------------------------

(defn- find-core-dirs
  "Recursively find all directories named 'core' under a root directory."
  [root]
  (->> (file-seq root)
       (filter #(and (.isDirectory %) (= "core" (.getName %))))))

(defn core-source-paths
  "Find all .clj files under any core/ directory that must be subject to
   FC/IS enforcement. Covers:
   - libs/*/src/boundary/<lib>/core/ (and non-standard libs like
     boundary/shared/ui/core/)
   - src/boundary/test_support/core/ (monorepo-level shared test helpers)

   Public so it can be exercised from tests."
  []
  (let [root          (io/file (System/getProperty "user.dir"))
        libs          (io/file root "libs")
        libs-files    (when (.exists libs)
                        (->> (.listFiles libs)
                             (filter #(.isDirectory %))
                             (mapcat (fn [lib-dir]
                                       (let [src-dir (io/file lib-dir "src")]
                                         (when (.exists src-dir)
                                           (find-core-dirs src-dir)))))
                             (mapcat file-seq)
                             (filter #(and (.isFile %)
                                           (str/ends-with? (.getName %) ".clj")))))
        ;; src/boundary/test_support/core.clj is the monorepo-level shared
        ;; test helper namespace. It is a single file (boundary.test-support.core),
        ;; not a directory of core sources — include it explicitly.
        test-support-file (io/file root "src" "boundary" "test_support" "core.clj")
        test-support  (when (.exists test-support-file) [test-support-file])]
    (concat libs-files test-support)))

(defn- core-clj-files
  "Backwards-compatible alias for core-source-paths."
  []
  (core-source-paths))

(defn- extract-requires
  "Extract required namespace symbols from a (ns ...) form."
  [ns-form]
  (when ns-form
    (let [require-clause (->> ns-form
                              (filter #(and (sequential? %) (= :require (first %))))
                              first)]
      (when require-clause
        (->> (rest require-clause)
             (map #(cond
                     (symbol? %) %
                     (vector? %) (first %)
                     :else nil))
             (remove nil?))))))

(defn- extract-imports
  "Extract imported class names (fully qualified) from a (ns ...) form.
   Handles both vector and list import syntax:
     (:import [java.sql DriverManager Connection])
     (:import (java.sql DriverManager))"
  [ns-form]
  (when ns-form
    (let [import-clause (->> ns-form
                             (filter #(and (sequential? %) (= :import (first %))))
                             first)]
      (when import-clause
        (->> (rest import-clause)
             (mapcat (fn [spec]
                       (cond
                         ;; [java.sql DriverManager Connection] or (java.sql DriverManager)
                         (sequential? spec)
                         (let [pkg (str (first spec))]
                           (map #(str pkg "." %) (rest spec)))
                         ;; bare class symbol: java.sql.DriverManager
                         (symbol? spec) [(str spec)]
                         :else nil)))
             (remove nil?))))))

(defn- forbidden-import?
  "Returns true if a fully-qualified class name matches any forbidden import pattern."
  [class-str]
  (some #(re-find % class-str) forbidden-import-packages))

(defn- allowed-fq-violation?
  [file req]
  (some (fn [{file-pattern :file
              req-pattern  :req}]
          (and (re-find file-pattern file)
               (re-find req-pattern req)))
        allowed-fq-violations))

(defn- imported-static-class-aliases
  "Resolve simple class names available in the file to their fully-qualified
   names for forbidden static accessor checks."
  [imports]
  (merge default-static-class-aliases
         (->> imports
              (filter #(contains? forbidden-static-methods-by-class %))
              (map (fn [class-name]
                     [(last (str/split class-name #"\."))
                      class-name]))
              (into {}))))

(defn- scan-simple-static-calls
  "Scan stripped file content for forbidden runtime access via imported or
   implicitly available simple class names such as (Instant/now) or
   (System/currentTimeMillis)."
  [file content imports]
  (let [cleaned        (parsing/strip-comments-and-strings content)
        lines          (str/split-lines cleaned)
        class-aliases  (imported-static-class-aliases imports)]
    (->> lines
         (map-indexed
          (fn [idx line]
            (mapcat (fn [[alias fq-class]]
                      (keep (fn [method-name]
                              (when (re-find (re-pattern (str "\\(\\s*"
                                                              (java.util.regex.Pattern/quote alias)
                                                              "/"
                                                              (java.util.regex.Pattern/quote method-name)
                                                              "\\b"))
                                             line)
                                {:file   (str file)
                                 :line   (inc idx)
                                 :symbol (str fq-class "/" method-name)}))
                            (get forbidden-static-methods-by-class fq-class)))
                    class-aliases)))
         (mapcat identity))))

(defn- scan-fq-calls
  "Scan stripped file content for fully-qualified forbidden calls and
   bare I/O function calls (slurp, spit).
   Reports ALL violations per line (not just the first match).
   Returns a seq of {:file :line :symbol} maps."
  [file content]
  (let [cleaned (parsing/strip-comments-and-strings content)
        lines   (str/split-lines cleaned)
        all-patterns (concat forbidden-fq-patterns forbidden-call-patterns)]
    (->> lines
         (map-indexed
          (fn [idx line]
            (keep (fn [pat]
                    (when-let [m (re-find pat line)]
                      {:file   (str file)
                       :line   (inc idx)
                       :symbol (str/replace
                                (str/replace (str/trim m) #"^[(/\s]+" "")
                                #"/$" "")}))
                  all-patterns)))
         (mapcat identity))))

(defn- check-file
  "Check a single core/ .clj file for forbidden requires, imports,
   fully-qualified forbidden calls, and bare I/O calls in the body.
   Returns a seq of violation maps, or empty seq if clean."
  [file]
  (let [content  (slurp file)
        ns-form  (parsing/read-ns-form file)
        ns-name  (str (second ns-form))
        requires (extract-requires ns-form)
        imports  (extract-imports ns-form)
        require-violations (->> requires
                                (filter #(forbidden-require? (str %)))
                                (map (fn [req]
                                       {:file (str file)
                                        :ns   ns-name
                                        :req  (str req)
                                        :kind :require})))
        import-violations  (->> imports
                                (filter forbidden-import?)
                                (map (fn [cls]
                                       {:file (str file)
                                        :ns   ns-name
                                        :req  cls
                                        :kind :import})))
        fq-violations (->> (concat (scan-fq-calls file content)
                                   (scan-simple-static-calls file content imports))
                           (remove (fn [{:keys [symbol]}]
                                     (allowed-fq-violation? (str file) symbol)))
                           (map (fn [{:keys [line symbol]}]
                                  {:file (str file)
                                   :ns   ns-name
                                   :req  symbol
                                   :line line
                                   :kind :fq-call})))]
    (concat require-violations import-violations fq-violations)))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (let [files      (core-clj-files)
        violations (mapcat check-file files)]
    (if (seq violations)
      (do
        (println (ansi/red "FC/IS violations found:"))
        (println)
        (doseq [{:keys [file ns req kind line]} violations]
          (case kind
            :fq-call
            (do (println (str "  VIOLATION: " file ":" line))
                (println (str "    namespace " ns " calls " (ansi/red req))))
            :import
            (do (println (str "  VIOLATION: " file))
                (println (str "    namespace " ns " imports " (ansi/red req))))
            :require
            (do (println (str "  VIOLATION: " file))
                (println (str "    namespace " ns " requires " (ansi/red req))))))
        (println)
        (println (str (count violations) " violation(s) found. Core namespaces must not import shell, I/O, logging, or DB code."))
        (System/exit 1))
      (do
        (println (ansi/green "FC/IS check passed.") (str (count files) " core file(s) scanned, 0 violations."))
        (System/exit 0)))))
