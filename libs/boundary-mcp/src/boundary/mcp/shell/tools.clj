(ns boundary.mcp.shell.tools
  "Executors for the MCP tools. All real work (running clj-kondo, Malli
   validation, project reflection, AI calls, scaffolding + the verify loop)
   lives here; the core stays a pure catalog. Each executor takes (args deps)
   and returns result data; throwing is fine — the dispatch maps it to an
   isError result.

   Tier 0 (BOU-100): read/analyze. Tier 1 (BOU-101): generate — scaffold/write
   to disk (reversible via git) then run the closed verify loop
   (boundary.mcp.shell.verify)."
  (:require [boundary.ai.core.context :as context]
            [boundary.ai.core.parsing :as parsing]
            [boundary.ai.core.prompts :as prompts]
            [boundary.ai.ports :as ai]
            [boundary.devtools.error-codes :as codes]
            [boundary.mcp.core.resources :as resources]
            [boundary.mcp.ports :as ports]
            [boundary.mcp.shell.verify :as verify]
            [boundary.scaffolder.ports :as scaffold]
            [clj-kondo.core :as kondo]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.io File)))

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
;; Paths are agent-supplied, so they are confined to the project root (the
;; current working directory the in-process adapter reflects). Relative paths
;; resolve against the root; anything that escapes it — via `..` or an absolute
;; path elsewhere — is rejected before clj-kondo touches the filesystem.

(defn- project-root ^File []
  (.getCanonicalFile (io/file (System/getProperty "user.dir"))))

(defn- confine-path
  "Resolve `p` against `root` (absolute paths are honored as-is) and return its
   canonical path, or throw if it escapes the root."
  [^File root p]
  (let [in (io/file (str p))
        f  (.getCanonicalFile (if (.isAbsolute in) in (io/file root (str p))))
        rp (.getPath root)]
    (if (or (= (.getPath f) rp)
            (str/starts-with? (.getPath f) (str rp File/separator)))
      (.getPath f)
      (throw (ex-info (str "Path escapes the project root: " (pr-str p))
                      {:type :validation-error :path p})))))

(defn- lint [{:keys [paths]} _deps]
  (let [root  (project-root)
        safe  (mapv #(confine-path root %) paths)
        {:keys [findings summary]} (kondo/run! {:lint safe})]
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
  (let [mg    (resources/force-val (:module-graph (ports/snapshot (:system-source deps))))
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

;; --- Tier 1 (:generate) — scaffold + closed verify loop ---------------------
;; Each tool builds a scaffolder request, the scaffolder writes the files
;; (reversible via git), and the verify loop runs kondo → FC/IS → tests over
;; what was written. The structured report is the agent's feedback to
;; self-correct and re-invoke. `:allow true` requests an audited override of the
;; soft guardrails (FC/IS / convention); it is honored only when *every*
;; blocking issue is soft (see core/verify).

;; Names become file paths in the scaffolder (`src/boundary/<module>/…`), which
;; does not sanitize them. Guard agent-supplied names here so a value like
;; "../../../etc/x" cannot write outside the project tree.
(def ^:private module-name-re #"[a-z][a-z0-9-]*")
(def ^:private entity-name-re #"[A-Za-z][A-Za-z0-9]*")

(defn- valid-name!
  [kind ^java.util.regex.Pattern re v]
  (when-not (and (string? v) (re-matches re v))
    (throw (ex-info (format "Invalid %s name: %s (must match %s)"
                            (name kind) (pr-str v) (str re))
                    {:type :validation-error :field kind :value v})))
  v)

(defn- field->scaffolder
  "MCP field map → scaffolder FieldDefinition: :name and :type become keywords."
  [{:keys [name type] :as f}]
  (-> f
      (assoc :name (keyword name) :type (keyword type))
      (select-keys [:name :type :required :unique :default :enum-values :min :max :description])))

(defn- entity->scaffolder
  [{:keys [name plural fields]}]
  (cond-> {:name name :fields (mapv field->scaffolder fields)}
    plural (assoc :plural plural)))

(defn- file-summary [files]
  (mapv #(select-keys % [:path :action]) files))

(defn- record-override!
  "Audit a soft-guardrail override when the verify report was overridden."
  [deps tool-name module report]
  (when (= :overridden (:status report))
    (when-let [audit (:audit deps)]
      (ports/record! audit {:event  :guardrail-override
                            :tool   tool-name
                            :module module
                            :codes  (vec (distinct (keep :code (:issues report))))}))))

(defn- source->test-path
  "Map a source path to its conventional test path:
   .../src/.../foo.clj -> .../test/.../foo_test.clj."
  [src]
  (-> src
      (str/replace #"(^|/)src/" "$1test/")
      (str/replace #"\.clj$" "_test.clj")))

(defn- scaffold-module [{:keys [module entities interfaces preview allow]} deps]
  (valid-name! :module module-name-re module)
  (run! #(valid-name! :entity entity-name-re (:name %)) entities)
  (let [svc (:scaffolder deps)
        req {:module-name module
             :entities    (mapv entity->scaffolder entities)
             :interfaces  (select-keys (or interfaces {}) [:http :cli :web])
             :dry-run     (boolean preview)}]
    (if preview
      (let [r (scaffold/generate-module svc (assoc req :dry-run true))]
        {:status  :preview
         :module  module
         :success (boolean (:success r))
         :plan    (file-summary (:files r))
         :errors  (:errors r)})
      (let [r      (scaffold/generate-module svc req)
            report (verify/verify-generated deps (assoc r :module module)
                                            {:overridden? (boolean allow)})]
        (record-override! deps "scaffold-module" module report)
        (assoc report :module module :files (file-summary (:files r)))))))

(defn- add-field [{:keys [module entity field allow]} deps]
  (valid-name! :module module-name-re module)
  (valid-name! :entity entity-name-re entity)
  (let [svc (:scaffolder deps)
        r   (scaffold/add-field svc {:module-name module
                                     :entity      entity
                                     :field       (field->scaffolder field)
                                     :dry-run     false})
        report (verify/verify-generated deps (assoc r :module module)
                                        {:overridden? (boolean allow)})]
    (record-override! deps "add-field" module report)
    (assoc report :module module
           :files    (file-summary (:files r))
           :warnings (:warnings r))))

(defn- gen-migration [{:keys [module entity fields allow]} deps]
  (valid-name! :module module-name-re module)
  (valid-name! :entity entity-name-re entity)
  (let [svc (:scaffolder deps)
        ;; Reuse generate-module's migration generator via dry-run, then write
        ;; only the .sql file — no source/test files for a migration-only tool.
        r   (scaffold/generate-module svc {:module-name module
                                           :entities    [{:name entity :fields (mapv field->scaffolder fields)}]
                                           :interfaces  {}
                                           :dry-run     true})
        migration (first (filter #(re-find #"migrations/.*\.sql$" (:path %)) (:files r)))]
    (if-not migration
      {:status :error :module module :note "No migration was generated."}
      (do
        (let [f (io/file (:path migration))]
          (.mkdirs (.getParentFile f))
          (spit f (:content migration)))
        (let [written {:success true :module module :files [(assoc migration :action :create)]}
              report  (verify/verify-generated deps written {:overridden? (boolean allow)})]
          (record-override! deps "gen-migration" module report)
          (assoc report :module module :files (file-summary [migration])))))))

(defn- gen-tests [{:keys [source-path allow]} deps]
  (if-let [provider (:ai-provider deps)]
    (let [root        (project-root)
          src         (confine-path root source-path)
          source-code (slurp src)
          messages    (prompts/test-generator-messages
                       src source-code (context/determine-test-type src))
          result      (ai/complete provider messages {})]
      (if (:error result)
        {:status :error :error (:error result)}
        (let [test-path (confine-path root (source->test-path src))
              test-src  (parsing/parse-generated-tests (:text result))]
          (let [f (io/file test-path)]
            (.mkdirs (.getParentFile f))
            (spit f test-src))
          (let [written {:success true :files [{:path test-path :action :create}]}
                report  (verify/verify-generated deps written {:overridden? (boolean allow)})]
            (assoc report :test-file test-path)))))
    {:status :unavailable
     :note   "gen-tests requires an AI provider; none configured for this server."}))

;; --- registry ---------------------------------------------------------------

(def ^:private executors
  {"explain-error"   explain-error
   "lint"            lint
   "validate-schema" validate-schema
   "describe-module" describe-module
   "sql-preview"     sql-preview
   "scaffold-module" scaffold-module
   "add-field"       add-field
   "gen-tests"       gen-tests
   "gen-migration"   gen-migration})

(defn run
  "Execute tool `name` with `args` (a map) and `deps`
   ({:system-source :ai-provider}). Returns result data, or nil if `name` is
   not a known tool."
  [deps name args]
  (when-let [f (get executors name)]
    (f args deps)))
