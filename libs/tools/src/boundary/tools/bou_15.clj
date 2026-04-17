#!/usr/bin/env bb
;; libs/tools/src/boundary/tools/bou_15.clj
;;
;; Reports remaining usage of deprecated BOU-15 transitional wrappers so they
;; can be removed once downstream callers are migrated.

(ns boundary.tools.bou-15
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.tools.ansi :as ansi]
            [boundary.tools.parsing :as parsing]))

(def ^:private deprecated-apis
  [{:namespace "boundary.tenant.core.invite"
    :symbol "prepare-invite"
    :replacement "prepare-invite*"}
   {:namespace "boundary.tenant.core.membership"
    :symbol "prepare-invitation"
    :replacement "prepare-invitation*"}
   {:namespace "boundary.tenant.core.membership"
    :symbol "prepare-active-membership"
    :replacement "prepare-active-membership*"}
   {:namespace "boundary.search.core.index"
    :symbol "build-document"
    :replacement "build-document*"}
   {:namespace "boundary.platform.core.http.problem-details"
    :symbol "request->context"
    :replacement "request->context*"}
   {:namespace "boundary.platform.core.http.problem-details"
    :symbol "cli-context"
    :replacement "cli-context*"}
   {:namespace "boundary.platform.core.pagination.versioning"
    :symbol "validate-version"
    :replacement "validate-version*"}
   {:namespace "boundary.calendar.core.recurrence"
    :symbol "next-occurrence"
    :replacement "next-occurrence*"}
   {:namespace "boundary.storage.core.validation"
    :symbol "generate-unique-filename"
    :replacement "generate-unique-filename*"}
   {:namespace "boundary.user.core.ui"
    :symbol "format-relative-time"
    :replacement "format-relative-time*"}
   {:namespace "boundary.user.core.ui"
    :symbol "format-date-relative"
    :replacement "format-date-relative*"}
   {:namespace "boundary.reports.core.report"
    :symbol "format-cell"
    :replacement "format-cell*"}
   {:namespace "boundary.reports.core.report"
    :symbol "map-columns"
    :replacement "map-columns*"}
   {:namespace "boundary.reports.core.report"
    :symbol "build-table-rows"
    :replacement "build-table-rows*"}
   {:namespace "boundary.reports.core.report"
    :symbol "build-sections-hiccup"
    :replacement "build-sections-hiccup*"}
   {:namespace "boundary.core.utils.type-conversion"
    :symbol "generate-uuid"
    :replacement "explicit shell-generated UUID"}
   {:namespace "boundary.core.utils.type-conversion"
    :symbol "current-instant"
    :replacement "explicit shell-generated Instant"}])

(defn source-files
  "Find repo Clojure source and test files relevant to deprecated BOU-15 API usage."
  []
  (let [root       (io/file (System/getProperty "user.dir"))
        libs-dir   (io/file root "libs")
        top-src    (io/file root "src")
        top-test   (io/file root "test")
        trees      (concat
                    (when (.exists libs-dir)
                      (->> (.listFiles libs-dir)
                           (filter #(.isDirectory %))
                           (mapcat (fn [lib-dir]
                                     (keep #(when (.exists %) %)
                                           [(io/file lib-dir "src")
                                            (io/file lib-dir "test")])))))
                    (keep #(when (.exists %) %)
                          [top-src top-test]))]
    (->> trees
         (mapcat file-seq)
         (filter #(and (.isFile %)
                       (str/ends-with? (.getName %) ".clj"))))))

(defn- normalize-require-spec
  [spec]
  (cond
    (symbol? spec) {:ns (str spec) :alias nil :refers []}
    (vector? spec) (let [ns-sym  (first spec)
                         options (rest spec)
                         pairs   (partition 2 options)
                         alias   (some (fn [[k v]] (when (= :as k) (str v))) pairs)
                         refers  (some (fn [[k v]]
                                         (when (and (= :refer k) (vector? v))
                                           (mapv str v)))
                                       pairs)]
                     {:ns     (str ns-sym)
                      :alias  alias
                      :refers (or refers [])})
    :else nil))

(defn- extract-require-aliases
  "Map alias or fully-qualified namespace strings to the required namespace."
  [ns-form]
  (let [require-clause (->> ns-form
                            (filter #(and (sequential? %) (= :require (first %))))
                            first)]
    (when require-clause
      (->> (rest require-clause)
           (map normalize-require-spec)
           (remove nil?)
           (mapcat (fn [{:keys [ns alias]}]
                     (cond-> [[ns ns]]
                       alias (conj [alias ns]))))
           (into {})))))

(defn- extract-referred-symbols
  "Map directly :refer'd symbol strings to the namespace they come from."
  [ns-form]
  (let [require-clause (->> ns-form
                            (filter #(and (sequential? %) (= :require (first %))))
                            first)]
    (when require-clause
      (->> (rest require-clause)
           (map normalize-require-spec)
           (remove nil?)
           (mapcat (fn [{:keys [ns refers]}]
                     (map (fn [sym] [sym ns]) refers)))
           (into {})))))

(defn- offset->line-number
  [content offset]
  (inc (count (filter #(= \newline %) (subs content 0 (min offset (count content)))))))

(defn- file-category
  [file]
  (let [path (str file)]
    (cond
      (re-find #"/(?:libs/[^/]+/)?src/" path) :production
      (re-find #"/(?:libs/[^/]+/)?test/" path) :test
      :else :other)))

(defn- ignored-definition-file?
  [file namespace]
  (let [path (str file)
        namespace-path (str/replace namespace "." "/")]
    (str/includes? path (str "/" namespace-path ".clj"))))

(defn- find-qualified-call-sites
  [file api]
  (let [raw               (slurp file)
        cleaned           (parsing/strip-comments-and-strings raw)
        ns-form           (parsing/read-ns-form file)
        aliases           (extract-require-aliases ns-form)
        referred-syms     (extract-referred-symbols ns-form)
        namespace-aliases (->> aliases
                               (filter (fn [[_ ns-name]]
                                         (= ns-name (:namespace api))))
                               (map first)
                               distinct)
        referred-matches  (->> referred-syms
                               (filter (fn [[sym-name ns-name]]
                                         (and (= ns-name (:namespace api))
                                              (= sym-name (:symbol api)))))
                               (map first)
                               distinct)
        find-matches      (fn [pattern]
                            (let [matcher (re-matcher pattern cleaned)]
                              (loop [matches []]
                                (if (.find matcher)
                                  (recur (conj matches
                                               {:file        (str file)
                                                :line        (offset->line-number raw (.start matcher))
                                                :category    (file-category file)
                                                :namespace   (:namespace api)
                                                :symbol      (:symbol api)
                                                :replacement (:replacement api)}))
                                  matches))))]
    (distinct
     (concat
      (when (seq namespace-aliases)
        (->> namespace-aliases
             (mapcat (fn [alias]
                       (find-matches
                        (re-pattern (str "\\(\\s*"
                                         (java.util.regex.Pattern/quote alias)
                                         "/"
                                         (java.util.regex.Pattern/quote (:symbol api))
                                         "(?!\\*)\\b")))))
             distinct))
      (when (seq referred-matches)
        (->> referred-matches
             (mapcat (fn [sym]
                       (find-matches
                        (re-pattern (str "\\(\\s*"
                                         (java.util.regex.Pattern/quote sym)
                                         "(?!\\*)\\b")))))
             distinct))
      (find-matches
       (re-pattern (str "\\(\\s*"
                        (java.util.regex.Pattern/quote (:namespace api))
                        "/"
                        (java.util.regex.Pattern/quote (:symbol api))
                        "(?!\\*)\\b")))))))

(defn scan-deprecated-usage
  "Scan repo files for qualified calls to deprecated BOU-15 transitional APIs."
  [files]
  (->> files
       (mapcat (fn [file]
                 (mapcat (fn [api]
                           (if (ignored-definition-file? file (:namespace api))
                             []
                             (find-qualified-call-sites file api)))
                         deprecated-apis)))
       distinct
       (sort-by (juxt :category :namespace :symbol :file :line))))

(defn- print-usage-section
  [title matches]
  (println title)
  (if (seq matches)
    (doseq [{:keys [file line namespace symbol replacement]} matches]
      (println (format "  %s:%s  %s/%s -> %s"
                       file line namespace symbol replacement)))
    (println "  none"))
  (println))

(defn -main [& _args]
  (let [files             (source-files)
        matches           (scan-deprecated-usage files)
        production-usage  (filter #(= :production (:category %)) matches)
        test-usage        (filter #(= :test (:category %)) matches)]
    (println "BOU-15 deprecated wrapper usage report")
    (println)
    (print-usage-section "Production usage:" production-usage)
    (print-usage-section "Test usage:" test-usage)
    (println (format "Scanned %s file(s), found %s usage site(s)."
                     (count files)
                     (count matches)))
    (if (seq production-usage)
      (do
        (println (ansi/red "Production code still calls deprecated BOU-15 wrappers."))
        (System/exit 1))
      (do
        (println (ansi/green "No production usage of deprecated BOU-15 wrappers found."))
        (System/exit 0)))))
