(ns boundary.mcp.shell.tools
  "Executors for the Tier 0 read/analyze tools (BOU-100). All real work (running
   clj-kondo, Malli validation, project reflection, AI calls) lives here; the
   core stays a pure catalog. Each executor takes (args deps) and returns
   result data; throwing is fine — the dispatch maps it to an isError result."
  (:require [boundary.ai.core.context :as context]
            [boundary.ai.core.parsing :as parsing]
            [boundary.ai.ports :as ai]
            [boundary.devtools.error-codes :as codes]
            [boundary.mcp.ports :as ports]
            [clj-kondo.core :as kondo]
            [clojure.edn :as edn]
            [malli.core :as m]
            [malli.error :as me]))

(defn- forced [v] (if (delay? v) (force v) v))

;; --- explain-error ----------------------------------------------------------
;; Deterministic: summarise the stacktrace (ai.core.context) and, if the text
;; names a BND code, enrich with the catalog entry (rule / principle / fix).

(defn- explain-error [{:keys [error]} _deps]
  (let [code  (some-> error (->> (re-find #"BND-\d{3}")))
        entry (when code (codes/lookup code))]
    (cond-> {:summary (context/summarise-stacktrace (or error "") 40)}
      code  (assoc :code code)
      entry (assoc :rule      (:title entry)
                   :principle (:description entry)
                   :fix       (:fix entry)))))

;; --- lint -------------------------------------------------------------------

(defn- lint [{:keys [paths]} _deps]
  (let [{:keys [findings summary]} (kondo/run! {:lint (vec paths)})]
    {:summary  summary
     :findings (mapv #(select-keys % [:filename :row :col :level :type :message])
                     findings)}))

;; --- validate-schema --------------------------------------------------------

(defn- validate-schema [{:keys [schema value]} _deps]
  (let [schema (m/schema (edn/read-string schema))]
    (if (m/validate schema value)
      {:valid? true}
      {:valid? false
       :errors (me/humanize (m/explain schema value))})))

;; --- describe-module --------------------------------------------------------

(defn- describe-module [{:keys [module]} deps]
  (let [mg    (forced (:module-graph (ports/snapshot (:system-source deps))))
        entry (first (filter #(= module (:name %)) (:modules mg)))]
    (or entry
        {:status    :not-found
         :module    module
         :available (mapv :name (:modules mg))})))

;; --- sql-preview ------------------------------------------------------------

(def ^:private sql-system-prompt
  (str "You translate a natural-language request into a database query for a "
       "Clojure project that uses HoneySQL. Respond ONLY with JSON of the shape "
       "{\"honeysql\": <edn string>, \"raw-sql\": <string>, \"explanation\": <string>}. "
       "Generate the query only; never execute it."))

(defn- sql-preview [{:keys [query]} deps]
  (if-let [provider (:ai-provider deps)]
    (let [resp (ai/complete provider
                            [{:role :system :content sql-system-prompt}
                             {:role :user :content query}]
                            {})]
      (if (:error resp)
        {:status :error :error (:error resp)}
        (parsing/parse-sql-response (:text resp))))
    {:status :unavailable
     :note   "sql-preview requires an AI provider; none configured for this server."}))

;; --- registry ---------------------------------------------------------------

(def ^:private executors
  {"explain-error"   explain-error
   "lint"            lint
   "validate-schema" validate-schema
   "describe-module" describe-module
   "sql-preview"     sql-preview})

(defn run
  "Execute tool `name` with `args` (a map) and `deps`
   ({:system-source :ai-provider}). Returns result data, or nil if `name` is
   not a known tool."
  [deps name args]
  (when-let [f (get executors name)]
    (f args deps)))
