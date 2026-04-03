#!/usr/bin/env bb
;; boundary-tools/src/boundary/tools/check_deps.clj
;;
;; Dependency direction linting: verifies that runtime (:require ...) matches
;; declared deps.edn dependencies and that no circular dependency exists
;; between Boundary libraries.

(ns boundary.tools.check-deps
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.tools.ansi :as ansi]))

;; ---------------------------------------------------------------------------
;; Parsing helpers
;; ---------------------------------------------------------------------------

(defn- lib-dirs
  "Return a seq of [lib-name, lib-dir] pairs for all libraries under libs/."
  []
  (let [root (io/file (System/getProperty "user.dir"))
        libs (io/file root "libs")]
    (when (.exists libs)
      (->> (.listFiles libs)
           (filter #(.isDirectory %))
           (map (fn [d] [(.getName d) d]))
           (sort-by first)))))

(defn- parse-deps-edn
  "Parse a deps.edn and extract declared Boundary library dependencies.
   Returns a set of library name strings."
  [deps-file]
  (when (.exists deps-file)
    (let [deps (edn/read-string (slurp deps-file))
          all-deps (merge (:deps deps) {})]
      (->> (vals all-deps)
           (filter map?)
           (keep :local/root)
           (map (fn [root-path]
                  ;; Extract lib name from paths like "../core" or "../../libs/core"
                  (last (str/split root-path #"/"))))
           (set)))))

(defn- source-files
  "Find all .clj files under a library's src/ directory."
  [lib-dir]
  (let [src-dir (io/file lib-dir "src")]
    (when (.exists src-dir)
      (->> (file-seq src-dir)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))))))

(defn- read-ns-form
  "Read the (ns ...) form from a Clojure file."
  [file]
  (try
    (let [content (slurp file)
          forms   (read-string (str "[" content "]"))]
      (first (filter #(and (list? %) (= 'ns (first %))) forms)))
    (catch Exception _ nil)))

(defn- extract-required-ns
  "Extract required namespace symbols from a ns form."
  [ns-form]
  (when ns-form
    (let [require-clause (->> ns-form
                              (filter #(and (sequential? %) (= :require (first %))))
                              first)]
      (when require-clause
        (->> (rest require-clause)
             (map #(cond (symbol? %) (str %) (vector? %) (str (first %)) :else nil))
             (remove nil?))))))

(defn- ns->boundary-lib
  "Given a namespace string like 'boundary.user.core.foo', extract the library
   name 'user'. Returns nil for non-boundary namespaces.
   Maps boundary.shared.* to 'admin' (those namespaces live in libs/admin/src/)."
  [ns-str]
  (let [parts (str/split ns-str #"\.")]
    (when (and (>= (count parts) 2) (= "boundary" (first parts)))
      (if (= "shared" (second parts))
        "admin"
        (second parts)))))

;; ---------------------------------------------------------------------------
;; Graph building
;; ---------------------------------------------------------------------------

(defn- build-declared-graph
  "Build adjacency map from deps.edn: {lib-name -> #{dep-lib-names}}."
  [lib-entries]
  (into {}
        (map (fn [[lib-name lib-dir]]
               [lib-name (parse-deps-edn (io/file lib-dir "deps.edn"))])
             lib-entries)))

(defn- build-actual-graph
  "Build adjacency map from source :requires: {lib-name -> #{dep-lib-names}}."
  [lib-entries]
  (into {}
        (map (fn [[lib-name lib-dir]]
               (let [files (source-files lib-dir)
                     dep-libs (->> files
                                   (mapcat (fn [f]
                                             (let [ns-form (read-ns-form f)]
                                               (extract-required-ns ns-form))))
                                   (keep ns->boundary-lib)
                                   (remove #(= % lib-name))
                                   (set))]
                 [lib-name dep-libs]))
             lib-entries)))

;; ---------------------------------------------------------------------------
;; Cycle detection (DFS)
;; ---------------------------------------------------------------------------

(defn- find-cycle
  "DFS cycle detection on a graph. Returns the first cycle found as a vector
   of lib names, or nil if no cycle exists."
  [graph]
  (let [visited (atom #{})
        path    (atom [])
        on-path (atom #{})]
    (letfn [(dfs [node]
              (when-not (@visited node)
                (swap! visited conj node)
                (swap! path conj node)
                (swap! on-path conj node)
                (let [result (some (fn [neighbor]
                                     (if (@on-path neighbor)
                                       (conj (vec (drop-while #(not= % neighbor) @path)) neighbor)
                                       (dfs neighbor)))
                                   (get graph node #{}))]
                  (swap! path pop)
                  (swap! on-path disj node)
                  result)))]
      (some dfs (keys graph)))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn- check-core-independence
  "Verify that the 'core' library has zero boundary library dependencies."
  [declared-graph actual-graph]
  (let [declared-deps (get declared-graph "core" #{})
        actual-deps   (get actual-graph "core" #{})]
    (concat
     (map (fn [d] {:type :core-declared-dep :dep d}) declared-deps)
     (map (fn [d] {:type :core-actual-dep :dep d}) actual-deps))))

(defn- check-undeclared-deps
  "Verify every actual require-time dep exists in the declared graph (direct or transitive)."
  [declared-graph actual-graph]
  (let [;; Compute transitive closure of declared deps
        transitive (fn transitive-deps [lib seen]
                     (let [direct (get declared-graph lib #{})]
                       (reduce (fn [acc dep]
                                 (if (contains? acc dep)
                                   acc
                                   (into (conj acc dep)
                                         (transitive-deps dep (conj acc dep)))))
                               seen
                               direct)))]
    (->> actual-graph
         (mapcat (fn [[lib actual-deps]]
                   (let [all-declared (transitive lib #{})]
                     (->> actual-deps
                          (remove #(contains? all-declared %))
                          (map (fn [dep]
                                 {:type :undeclared-dep
                                  :lib  lib
                                  :dep  dep})))))))))

;; ---------------------------------------------------------------------------
;; Known cycle allowlist
;; ---------------------------------------------------------------------------

(def ^:private allowed-cycles
  "Pre-existing source-level cycles that are acknowledged but not yet resolved.
   Each entry is a set of the two libraries involved.
   Remove entries as cycles are broken; adding new entries requires an ADR."
  #{#{"admin" "user"}})

(defn- allowed-cycle?
  "Returns true if a cycle path only involves libraries in the allowlist."
  [cycle-path]
  (let [libs (set (butlast cycle-path))]
    (contains? allowed-cycles libs)))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (let [entries        (lib-dirs)
        declared-graph (build-declared-graph entries)
        actual-graph   (build-actual-graph entries)
        core-issues        (check-core-independence declared-graph actual-graph)
        undeclared         (check-undeclared-deps declared-graph actual-graph)
        declared-cycle     (find-cycle declared-graph)
        actual-cycle       (find-cycle actual-graph)
        ;; Hard failures: core violations, declared cycles, and non-allowlisted actual cycles
        hard-failures  (concat core-issues
                               (when declared-cycle [{:type :declared-cycle :path declared-cycle}])
                               (when (and actual-cycle (not (allowed-cycle? actual-cycle)))
                                 [{:type :actual-cycle :path actual-cycle}]))
        ;; Soft warnings: undeclared deps (monorepo shared classpath allows these)
        warnings       undeclared]
    ;; Print allowlisted actual cycles as informational
    (when (and actual-cycle (allowed-cycle? actual-cycle))
      (println (ansi/yellow "Known source-level cycle (allowlisted):"))
      (println (str "  " (str/join " -> " actual-cycle)))
      (println))
    (when (seq warnings)
      (println (ansi/yellow "Undeclared dependency warnings:"))
      (doseq [v warnings]
        (println (str "  WARNING: " (:lib v) " requires " (:dep v) " but it is not in deps.edn")))
      (println (str (count warnings) " warning(s). Consider adding these to the library's deps.edn."))
      (println))
    ;; Hard failures block CI
    (if (seq hard-failures)
      (do
        (println (ansi/red "Dependency direction violations found:"))
        (println)
        (doseq [v hard-failures]
          (case (:type v)
            :core-declared-dep
            (println (str "  VIOLATION: core/deps.edn declares dependency on " (ansi/red (:dep v))))
            :core-actual-dep
            (println (str "  VIOLATION: core source requires " (ansi/red (:dep v)) " (core must be dependency-free)"))
            :declared-cycle
            (println (str "  VIOLATION: circular dependency in deps.edn: " (ansi/red (str/join " -> " (:path v)))))
            :actual-cycle
            (println (str "  VIOLATION: circular dependency in source requires: " (ansi/red (str/join " -> " (:path v)))))))
        (println)
        (println (str (count hard-failures) " violation(s) found."))
        (System/exit 1))
      (do
        (println (ansi/green "Dependency check passed.")
                 (str (count entries) " libraries scanned, 0 hard violations."))
        (System/exit 0)))))
