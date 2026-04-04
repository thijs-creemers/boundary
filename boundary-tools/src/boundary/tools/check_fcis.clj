#!/usr/bin/env bb
;; boundary-tools/src/boundary/tools/check_fcis.clj
;;
;; FC/IS boundary enforcement: ensures core/ namespaces never import
;; shell code, I/O libraries, logging, or database drivers.
;; See ADR-021 for rationale.

(ns boundary.tools.check-fcis
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.tools.ansi :as ansi]))

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
   #"java\.sql\.DriverManager"
   #"java\.sql\.Connection\b"
   #"java\.sql\.Statement\b"
   #"java\.sql\.PreparedStatement"
   #"java\.sql\.CallableStatement"
   #"java\.sql\.ResultSet"
   #"javax\.sql\.\w+"
   #"java\.net\.http\.\w+"])

(def ^:private forbidden-call-patterns
  "Bare Clojure core function calls that perform I/O and must never
   appear in core namespaces. Matched as (fn-name to ensure they are
   calls, not parts of other symbols. Applied to stripped source."
  [#"\(\s*slurp\s"
   #"\(\s*spit\s"])

(defn- forbidden-require?
  "Returns true if `ns-str` matches any forbidden require pattern."
  [ns-str]
  (some #(re-find % ns-str) forbidden-require-patterns))

;; ---------------------------------------------------------------------------
;; Source stripping — remove comments and string interiors so FQ-call
;; scanning only matches executable code, not docstrings or comments.
;; ---------------------------------------------------------------------------

(defn- strip-comments-and-strings
  "Replace comment text and string contents with spaces (preserving line
   structure) so that regex matches only apply to executable code."
  [content]
  (-> content
      (str/replace #"\"(?:[^\"\\]|\\.)*\""
                   (fn [m] (str/replace m #"[^\n]" " ")))
      (str/replace #";[^\n]*" (fn [m] (apply str (repeat (count m) \space))))))

;; ---------------------------------------------------------------------------
;; ns form parsing
;; ---------------------------------------------------------------------------

(defn- extract-ns-form-text
  "Extract the raw text of the (ns ...) form from file content using
   balanced-paren counting. Avoids read-string on the full file which
   fails on auto-resolved keywords like ::jdbc/opts."
  [content]
  (let [idx (.indexOf ^String content "(ns ")]
    (when (>= idx 0)
      (loop [i idx depth 0]
        (when (< i (count content))
          (let [c (.charAt ^String content i)]
            (cond
              (= c \() (recur (inc i) (inc depth))
              (= c \))
              (if (= depth 1)
                (subs content idx (inc i))
                (recur (inc i) (dec depth)))
              (= c \")
              (let [end (loop [j (inc i)]
                          (if (>= j (count content)) j
                              (let [ch (.charAt ^String content j)]
                                (cond
                                  (= ch \\) (recur (+ j 2))
                                  (= ch \") (inc j)
                                  :else     (recur (inc j))))))]
                (recur end depth))
              (= c \;)
              (let [nl (.indexOf ^String content "\n" (int i))]
                (recur (if (neg? nl) (count content) (inc nl)) depth))
              :else (recur (inc i) depth))))))))

(defn- read-ns-form
  "Read the (ns ...) form from a Clojure file. Returns nil if not found.
   Extracts only the ns form text before read-string, so files with
   auto-resolved keywords in function bodies are handled correctly."
  [file]
  (try
    (let [content (slurp file)
          ns-text (extract-ns-form-text content)]
      (when ns-text
        (read-string ns-text)))
    (catch Exception _
      nil)))

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

;; ---------------------------------------------------------------------------
;; File scanning
;; ---------------------------------------------------------------------------

(defn- find-core-dirs
  "Recursively find all directories named 'core' under a root directory."
  [root]
  (->> (file-seq root)
       (filter #(and (.isDirectory %) (= "core" (.getName %))))))

(defn- core-clj-files
  "Find all .clj files under any core/ directory in libs/*/src/.
   Covers both standard paths (boundary/<lib>/core/) and non-standard
   ones like boundary/shared/ui/core/."
  []
  (let [root (io/file (System/getProperty "user.dir"))
        libs (io/file root "libs")]
    (when (.exists libs)
      (->> (.listFiles libs)
           (filter #(.isDirectory %))
           (mapcat (fn [lib-dir]
                     (let [src-dir (io/file lib-dir "src")]
                       (when (.exists src-dir)
                         (find-core-dirs src-dir)))))
           (mapcat file-seq)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))))))

(defn- forbidden-import?
  "Returns true if a fully-qualified class name matches any forbidden import pattern."
  [class-str]
  (some #(re-find % class-str) forbidden-import-packages))

(defn- scan-fq-calls
  "Scan stripped file content for fully-qualified forbidden calls and
   bare I/O function calls (slurp, spit).
   Returns a seq of {:file :line :symbol} maps."
  [file content]
  (let [cleaned (strip-comments-and-strings content)
        lines   (str/split-lines cleaned)
        all-patterns (concat forbidden-fq-patterns forbidden-call-patterns)]
    (->> lines
         (map-indexed
          (fn [idx line]
            (some (fn [pat]
                    (when-let [m (re-find pat line)]
                      {:file   (str file)
                       :line   (inc idx)
                       :symbol (-> m str/trim (str/replace #"[(/\s]" "") (str/replace #"/$" ""))}))
                  all-patterns)))
         (remove nil?))))

(defn- check-file
  "Check a single core/ .clj file for forbidden requires, imports,
   fully-qualified forbidden calls, and bare I/O calls in the body.
   Returns a seq of violation maps, or empty seq if clean."
  [file]
  (let [content  (slurp file)
        ns-form  (read-ns-form file)
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
        fq-violations (->> (scan-fq-calls file content)
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
