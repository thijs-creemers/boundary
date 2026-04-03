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

(def ^:private forbidden-patterns
  "Patterns that must never appear in core namespace :require vectors."
  [#"\.shell\."
   #"^clojure\.tools\.logging$"
   #"^clojure\.java\.io$"
   #"^next\.jdbc"
   #"^clj-http"])

(defn- forbidden?
  "Returns true if `ns-str` matches any forbidden pattern."
  [ns-str]
  (some #(re-find % ns-str) forbidden-patterns))

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

(defn- core-clj-files
  "Find all .clj files under libs/*/src/boundary/*/core/."
  []
  (let [root   (io/file (System/getProperty "user.dir"))
        libs   (io/file root "libs")]
    (when (.exists libs)
      (->> (.listFiles libs)
           (filter #(.isDirectory %))
           (mapcat (fn [lib-dir]
                     (let [lib-name (.getName lib-dir)
                           core-dir (io/file lib-dir "src" "boundary" lib-name "core")]
                       (when (.exists core-dir)
                         (file-seq core-dir)))))
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))))))

(defn- check-file
  "Check a single core/ .clj file for forbidden requires.
   Returns a seq of violation maps, or empty seq if clean."
  [file]
  (let [ns-form  (read-ns-form file)
        requires (extract-requires ns-form)]
    (->> requires
         (filter #(forbidden? (str %)))
         (map (fn [req]
                {:file (str file)
                 :ns   (str (second ns-form))
                 :req  (str req)})))))

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
        (doseq [{:keys [file ns req]} violations]
          (println (str "  VIOLATION: " file))
          (println (str "    namespace " ns " requires " (ansi/red req))))
        (println)
        (println (str (count violations) " violation(s) found. Core namespaces must not import shell, I/O, logging, or DB code."))
        (System/exit 1))
      (do
        (println (ansi/green "FC/IS check passed.") (str (count files) " core file(s) scanned, 0 violations."))
        (System/exit 0)))))
