(ns boundary.mcp.core.tools
  "Catalog of the Tier 0 read/analyze tools (BOU-100). Pure data: each tool
   declares its MCP wire fields (name, description, JSON-Schema inputSchema) and
   its capability tier (all :read — zero mutation). Execution lives in
   boundary.mcp.shell.tools; the gate + audit live in the dispatch.")

(def catalog
  [{:name        "explain-error"
    :description "Explain a Clojure/Boundary error or stacktrace: a concise summary plus any matching BND error code (rule, principle, fix)."
    :capability  :read
    :inputSchema {:type       "object"
                  :properties {"error" {:type "string"
                                        :description "The error message or full stacktrace text."}}
                  :required   ["error"]}}
   {:name        "lint"
    :description "Run clj-kondo over the given file paths and return structured findings (file, row, level, message)."
    :capability  :read
    :inputSchema {:type       "object"
                  :properties {"paths" {:type        "array"
                                        :items       {:type "string"}
                                        :description "Files or directories to lint (relative to the project root)."}}
                  :required   ["paths"]}}
   {:name        "validate-schema"
    :description "Validate a value against a Malli schema (given as EDN) and return humanized errors, or {:valid? true}."
    :capability  :read
    :inputSchema {:type       "object"
                  :properties {"schema" {:type "string"
                                         :description "Malli schema as an EDN string, e.g. [:map [:name :string]]."}
                               "value"  {:description "The value to validate (any JSON value)."}}
                  :required   ["schema" "value"]}}
   {:name        "describe-module"
    :description "Describe a module from the running project: its dependencies, ports presence, external libs, and schema when reflected."
    :capability  :read
    :inputSchema {:type       "object"
                  :properties {"module" {:type "string"
                                         :description "Module name, e.g. \"user\"."}}
                  :required   ["module"]}}
   {:name        "sql-preview"
    :description "Generate HoneySQL (and raw SQL) from a natural-language query — generated only, never executed. Requires an AI provider."
    :capability  :read
    :inputSchema {:type       "object"
                  :properties {"query" {:type "string"
                                        :description "Natural-language description of the query."}}
                  :required   ["query"]}}])

(def tool-names
  (into #{} (map :name) catalog))

(defn capability
  "Capability tier for tool `name` (nil if unknown). All Tier 0 tools are :read."
  [name]
  (some #(when (= name (:name %)) (:capability %)) catalog))
