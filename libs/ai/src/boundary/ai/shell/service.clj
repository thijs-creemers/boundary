(ns boundary.ai.shell.service
  "AI service orchestration.

   Public API entry points for all AI features.
   Combines context extraction (shell I/O) with pure core functions
   and delegates to the configured IAIProvider.

   FC/IS: this namespace is in the shell layer — file reads happen here."
  (:require [boundary.ai.core.context :as ctx]
            [boundary.ai.core.parsing :as parsing]
            [boundary.ai.core.prompts :as prompts]
            [boundary.ai.ports :as ports]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Internal helpers
;; =============================================================================

(defn- slurp-file
  "Read a file to a string, return nil if not found."
  [path]
  (try
    (slurp path)
    (catch Exception _ nil)))

(defn- list-clj-files
  "Return all .clj files under a directory path as a map of {path content}."
  [dir]
  (let [d (io/file dir)]
    (if (and d (.exists d) (.isDirectory d))
      (->> (file-seq d)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))
           (reduce (fn [m f]
                     (let [content (slurp f)]
                       (assoc m (.getPath f) content)))
                   {}))
      {})))

(defn- resolve-provider
  "Return the primary provider, falling back if the primary fails.

   Args:
     service  - AIService map with :provider and optional :fallback
     messages - messages vector
     opts     - completion opts

   Returns:
     Result map from the successful provider."
  [{:keys [provider fallback]} messages opts]
  (let [result (ports/complete provider messages opts)]
    (if (and (:error result) fallback)
      (do
        (log/warn "primary AI provider failed, trying fallback"
                  {:error (:error result)})
        (ports/complete fallback messages opts))
      result)))

(defn- resolve-provider-json
  "Like resolve-provider but for complete-json."
  [{:keys [provider fallback]} messages schema opts]
  (let [result (ports/complete-json provider messages schema opts)]
    (if (and (:error result) fallback)
      (do
        (log/warn "primary AI provider failed for JSON, trying fallback"
                  {:error (:error result)})
        (ports/complete-json fallback messages schema opts))
      result)))

;; =============================================================================
;; Feature 1: NL Scaffolding
;; =============================================================================

(defn scaffold-from-description
  "Parse a natural language module description into a scaffolder spec.

   Args:
     service      - AIService map {:provider IAIProvider :fallback IAIProvider?}
     description  - NL description string
     project-root - project root path string (for module discovery)
     opts         - optional map (e.g. {:model \"...\"}

   Returns:
     {:module-name str :entity str :fields [...] :http bool :web bool}
     or {:error str} on failure."
  ([service description project-root]
   (scaffold-from-description service description project-root {}))
  ([service description project-root opts]
   (log/info "ai scaffold-from-description" {:description description})
   (let [lib-dirs     (let [d (io/file project-root "libs")]
                        (if (.exists d)
                          (mapv #(.getPath %) (filter #(.isDirectory %) (.listFiles d)))
                          []))
         existing     (ctx/extract-module-names lib-dirs)
         messages     (prompts/scaffolding-messages description existing)
         result       (resolve-provider-json service messages "ModuleSpec" opts)]
     (if (:error result)
       result
       (parsing/parse-module-spec (pr-str (:data result)))))))

;; =============================================================================
;; Feature 2: Error Explainer
;; =============================================================================

(defn explain-error
  "Explain a Clojure/Boundary stack trace and suggest a fix.

   Args:
     service      - AIService map
     stacktrace   - stack trace string
     project-root - project root path string (for source file lookup)
     opts         - optional completion opts

   Returns:
     {:text str :tokens int :provider kw :model str}
     or {:error str} on failure."
  ([service stacktrace project-root]
   (explain-error service stacktrace project-root {}))
  ([service stacktrace project-root opts]
   (log/info "ai explain-error")
   (let [truncated    (ctx/summarise-stacktrace stacktrace)
         file-refs    (ctx/extract-file-references stacktrace)
         ;; Try to load referenced source files (best-effort)
         source-files (reduce (fn [m ref]
                                (let [fname (first (str/split ref #":"))]
                                  (if-let [content (some #(slurp-file %)
                                                         [(str project-root "/src/" fname)
                                                          (str project-root "/" fname)])]
                                    (assoc m fname content)
                                    m)))
                              {}
                              file-refs)
         messages     (prompts/error-explainer-messages truncated source-files)]
     (resolve-provider service messages opts))))

;; =============================================================================
;; Feature 3: Test Generator
;; =============================================================================

(defn generate-tests
  "Generate a test namespace for a source file.

   Args:
     service     - AIService map
     source-path - path string to the source file
     opts        - optional completion opts

   Returns:
     {:text str :tokens int :provider kw :model str}
     where :text is the generated test namespace source,
     or {:error str} on failure."
  ([service source-path]
   (generate-tests service source-path {}))
  ([service source-path opts]
   (log/info "ai generate-tests" {:source-path source-path})
   (let [source-code (slurp-file source-path)]
     (if-not source-code
       {:error (str "Cannot read source file: " source-path)}
       (let [test-type (ctx/determine-test-type source-path)
             messages  (prompts/test-generator-messages source-path source-code test-type)
             result    (resolve-provider service messages opts)]
         (if (:error result)
           result
           (assoc result :text (parsing/parse-generated-tests (:text result)))))))))

;; =============================================================================
;; Feature 4: SQL Copilot
;; =============================================================================

(defn sql-from-description
  "Generate a HoneySQL query from a natural language description.

   Args:
     service      - AIService map
     description  - NL description string
     project-root - project root path string (for schema discovery)
     opts         - optional completion opts

   Returns:
     {:honeysql str :explanation str :raw-sql str}
     or {:error str} on failure."
  ([service description project-root]
   (sql-from-description service description project-root {}))
  ([service description project-root opts]
   (log/info "ai sql-from-description" {:description description})
   (let [schema-files (into {}
                            (filter (fn [[path _]]
                                      (str/ends-with? path "schema.clj"))
                                    (list-clj-files project-root)))
         schema-ctx   (ctx/extract-schema-context schema-files)
         messages     (prompts/sql-copilot-messages description schema-ctx)
         result       (resolve-provider-json service messages "SQLResponse" opts)]
     (if (:error result)
       result
       (parsing/parse-sql-response (pr-str (:data result)))))))

;; =============================================================================
;; Feature 5: Documentation Wizard
;; =============================================================================

(defn generate-docs
  "Generate documentation for a Boundary module.

   Args:
     service      - AIService map
     module-path  - path string to the module root (e.g. \"libs/user\")
     doc-type     - :agents, :openapi, or :readme
     opts         - optional completion opts

   Returns:
     {:text str :tokens int :provider kw :model str}
     where :text is the generated documentation markdown/YAML,
     or {:error str} on failure."
  ([service module-path doc-type]
   (generate-docs service module-path doc-type {}))
  ([service module-path doc-type opts]
   (log/info "ai generate-docs" {:module-path module-path :doc-type doc-type})
   (let [all-files    (list-clj-files (str module-path "/src"))
         source-files (into {}
                            (map (fn [[path content]]
                                   [path (ctx/truncate-source content)])
                                 all-files))
         messages     (prompts/docs-messages module-path source-files doc-type)]
     (resolve-provider service messages opts))))
