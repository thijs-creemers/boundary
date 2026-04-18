(ns boundary.devtools.shell.fcis-checker
  "Post-reset namespace scanner for FC/IS violations.
   Only detects BND-601: core namespace importing shell namespace via :require.
   BND-602 (core uses I/O) is detected statically by bb check:fcis."
  (:require [boundary.devtools.core.error-formatter :as formatter]
            [clojure.string :as str]))

(def ^:private framework-prefixes
  #{"boundary.platform." "boundary.observability." "boundary.devtools." "boundary.core."})

(defn core-ns?
  "Is this a user-level core namespace? (boundary.MODULE.core.*)"
  [ns-str]
  (and (str/includes? ns-str ".core.")
       (str/starts-with? ns-str "boundary.")
       (not (some #(str/starts-with? ns-str %) framework-prefixes))))

(defn shell-ns?
  "Is this a user-level shell namespace? (boundary.MODULE.shell.*)"
  [ns-str]
  (and (str/includes? ns-str ".shell.")
       (str/starts-with? ns-str "boundary.")
       (not (some #(str/starts-with? ns-str %) framework-prefixes))))

(defn- extract-module
  "Extract the module name from a namespace string."
  [ns-str]
  (let [parts (str/split ns-str #"\.")]
    (when (>= (count parts) 3)
      (nth parts 1))))

(defn- ns-requires
  "Get all required namespaces for a namespace object.
   Checks both ns-aliases (aliased requires) and the ns publics/refers
   to catch bare :require and :refer imports."
  [ns-obj]
  (distinct
   (concat
      ;; Aliased requires (e.g., [foo.bar :as bar])
    (map ns-name (vals (ns-aliases ns-obj)))
      ;; All namespaces that contributed vars to this ns via :refer or :require
    (->> (ns-refers ns-obj)
         vals
         (map #(-> % meta :ns))
         (remove nil?)
         (map ns-name)
         distinct))))

(defn find-violations
  "Scan loaded namespaces for FC/IS violations.
   Returns a vector of {:source-ns :requires-ns :module} maps."
  []
  (let [loaded-nses (all-ns)]
    (->> loaded-nses
         (filter #(core-ns? (str (ns-name %))))
         (mapcat (fn [ns-obj]
                   (let [ns-str       (str (ns-name ns-obj))
                         all-requires (map str (ns-requires ns-obj))]
                     (->> all-requires
                          (filter shell-ns?)
                          (map (fn [shell-ns]
                                 {:source-ns   ns-str
                                  :requires-ns shell-ns
                                  :module      (extract-module ns-str)}))))))
         vec)))

(defn check-fcis-violations!
  "Scan loaded namespaces and print warnings for FC/IS violations.
   Called after (go) and (reset) in user.clj."
  []
  (let [violations (find-violations)]
    (when (seq violations)
      (println)
      (doseq [v violations]
        (println (formatter/format-fcis-violation v)))
      (println (str "\n" (count violations) " FC/IS violation(s) found. "
                    "Run bb check:fcis for full static analysis.\n")))))
