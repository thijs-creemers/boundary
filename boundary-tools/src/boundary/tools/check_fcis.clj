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
  "Patterns that must never appear in core namespace :require vectors."
  [#"\.shell\."
   #"^clojure\.tools\.logging$"
   #"^clojure\.java\.io$"
   #"^next\.jdbc"
   #"^clj-http"])

(def ^:private forbidden-fq-patterns
  "Fully-qualified symbol prefixes that must never appear in core namespace bodies.
   These catch calls like clojure.java.io/file even without a :require."
  [#"clojure\.tools\.logging/"
   #"clojure\.java\.io/"
   #"next\.jdbc/"
   #"clj-http\.\w+/"])

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

(defn- scan-fq-calls
  "Scan file content for fully-qualified forbidden calls.
   Comments and string contents are stripped first so that docstrings,
   example text, and trailing comments are not flagged.
   Returns a seq of {:file :line :symbol} maps."
  [file content]
  (let [cleaned (strip-comments-and-strings content)
        lines   (str/split-lines cleaned)]
    (->> lines
         (map-indexed
          (fn [idx line]
            (some (fn [pat]
                    (when-let [m (re-find pat line)]
                      {:file   (str file)
                       :line   (inc idx)
                       :symbol (str/replace m #"/$" "")}))
                  forbidden-fq-patterns)))
         (remove nil?))))

(defn- check-file
  "Check a single core/ .clj file for forbidden requires and
   fully-qualified forbidden calls in the body.
   Returns a seq of violation maps, or empty seq if clean."
  [file]
  (let [content  (slurp file)
        ns-form  (read-ns-form file)
        ns-name  (str (second ns-form))
        requires (extract-requires ns-form)
        require-violations (->> requires
                                (filter #(forbidden-require? (str %)))
                                (map (fn [req]
                                       {:file (str file)
                                        :ns   ns-name
                                        :req  (str req)
                                        :kind :require})))
        fq-violations (->> (scan-fq-calls file content)
                           (map (fn [{:keys [line symbol]}]
                                  {:file (str file)
                                   :ns   ns-name
                                   :req  symbol
                                   :line line
                                   :kind :fq-call})))]
    (concat require-violations fq-violations)))

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
          (if (= kind :fq-call)
            (do (println (str "  VIOLATION: " file ":" line))
                (println (str "    namespace " ns " calls " (ansi/red req))))
            (do (println (str "  VIOLATION: " file))
                (println (str "    namespace " ns " requires " (ansi/red req))))))
        (println)
        (println (str (count violations) " violation(s) found. Core namespaces must not import shell, I/O, logging, or DB code."))
        (System/exit 1))
      (do
        (println (ansi/green "FC/IS check passed.") (str (count files) " core file(s) scanned, 0 violations."))
        (System/exit 0)))))
