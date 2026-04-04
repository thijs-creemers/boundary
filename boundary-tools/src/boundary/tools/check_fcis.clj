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
;; ns form parsing
;; ---------------------------------------------------------------------------

(defn- read-ns-form
  "Read the (ns ...) form from a Clojure file. Returns nil if not found."
  [file]
  (try
    (let [content (slurp file)
          forms   (read-string (str "[" content "]"))]
      (first (filter #(and (list? %) (= 'ns (first %))) forms)))
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
   Returns a seq of {:file :line :symbol} maps."
  [file content]
  (let [lines (str/split-lines content)]
    (->> lines
         (map-indexed
          (fn [idx line]
            ;; Skip comment lines
            (when-not (re-find #"^\s*;" line)
              (some (fn [pat]
                      (when-let [m (re-find pat line)]
                        {:file   (str file)
                         :line   (inc idx)
                         :symbol (str/replace m #"/$" "")}))
                    forbidden-fq-patterns))))
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
