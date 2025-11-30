(ns boundary.scaffolder.cli
  "CLI commands for module scaffolding.

   This is the SHELL layer in Functional Core / Imperative Shell architecture.
   Responsibilities:
   - Parse command-line arguments using tools.cli
   - Orchestrate scaffolder service calls
   - Format output (list files, success/error messages)
   - Handle errors and exit codes"
  (:require [boundary.scaffolder.ports :as ports]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]))

;; =============================================================================
;; Global CLI Options
;; =============================================================================

(def global-options
  [["-f" "--format FORMAT" "Output format: text (default) or json"
    :default "text"
    :validate [#(contains? #{"text" "json"} %) "Must be 'text' or 'json'"]]
   ["-h" "--help" "Show help"]])

;; =============================================================================
;; Generate Command Options
;; =============================================================================

(def generate-options
  [[nil "--module-name NAME" "Module name (lowercase, kebab-case) (required)"
    :validate [#(re-matches #"^[a-z][a-z0-9-]*$" %)
               "Must be lowercase with hyphens only"]]
   [nil "--entity NAME" "Entity name (PascalCase) (required)"
    :validate [#(re-matches #"^[A-Z][a-zA-Z0-9]*$" %)
               "Must be PascalCase"]]
   [nil "--field SPEC" "Field specification: name:type[:required][:unique] (can be repeated)"
    :multi true
    :default []
    :update-fn conj]
   [nil "--http" "Enable HTTP (REST API) interface (default: true)"
    :default true]
   [nil "--cli" "Enable CLI interface (default: true)"
    :default true]
   [nil "--web" "Enable Web UI interface (default: true)"
    :default true]
   [nil "--audit" "Enable audit logging (default: true)"
    :default true]
   [nil "--pagination" "Enable pagination support (default: true)"
    :default true]
   [nil "--dry-run" "Show what would be generated without creating files"
    :default false]])

;; =============================================================================
;; Field Parsing
;; =============================================================================

(defn parse-field-spec
  "Parse a field specification string into a field map.
  
   Format: name:type[:required][:unique]
   
   Examples:
     email:email:required:unique
     name:string:required
     age:integer
     status:enum
     
   Returns:
     Map with keys: :name, :type, :required, :unique, or error map"
  [field-spec]
  (let [parts (str/split field-spec #":")
        [name-str type-str & flags] parts
        valid-types #{"string" "text" "integer" "int" "decimal" "boolean" "email" "uuid" "enum" "date" "datetime" "inst" "json"}
        ;; Map CLI type names to schema type names
        type-mapping {"integer" :int
                      "int" :int
                      "date" :inst
                      "datetime" :inst
                      "text" :text
                      "json" :json}
        type-kw (get type-mapping type-str (keyword type-str))]
    (cond
      (< (count parts) 2)
      {:error (str "Invalid field spec: " field-spec " (expected format: name:type[:required][:unique])")}
      
      (not (re-matches #"^[a-z][a-z0-9-]*$" name-str))
      {:error (str "Invalid field name: " name-str " (must be lowercase kebab-case)")}
      
      (not (contains? valid-types type-str))
      {:error (str "Invalid field type: " type-str " (must be one of: " (str/join ", " (sort valid-types)) ")")}
      
      :else
      {:name (keyword name-str)
       :type type-kw
       :required (boolean (some #(= % "required") flags))
       :unique (boolean (some #(= % "unique") flags))})))

(defn parse-all-fields
  "Parse all field specifications.
  
   Returns:
     [success? fields-or-errors]"
  [field-specs]
  (let [parsed (map parse-field-spec field-specs)
        errors (filter :error parsed)
        fields (remove :error parsed)]
    (if (seq errors)
      [false (mapv :error errors)]
      [true (vec fields)])))

;; =============================================================================
;; Output Formatting
;; =============================================================================

(defn format-file-list
  "Format generated files as text list."
  [files]
  (str/join "\n"
            (map (fn [{:keys [path action]}]
                   (format "  %s: %s" action path))
                 files)))

(defn format-success-text
  "Format successful generation result as text."
  [result]
  (str "✓ Successfully generated module: " (:module-name result) "\n"
       "\n"
       "Generated files:\n"
       (format-file-list (:files result))
       "\n"
       "\n"
       "Next steps:\n"
       "  1. Review the generated files\n"
       "  2. Add module to config: [:active :boundary/settings :modules]\n"
       "  3. Wire module into Integrant system configuration\n"
       "  4. Run tests: clojure -M:test:db/h2 --focus-meta :" (:module-name result)))

(defn format-error-text
  "Format error result as text."
  [result]
  (str "✗ Failed to generate module\n"
       "\n"
       "Errors:\n"
       (str/join "\n" (map #(str "  - " %) (:errors result)))))

(defn format-dry-run-text
  "Format dry-run result as text."
  [result]
  (str "Dry run - would generate module: " (:module-name result) "\n"
       "\n"
       "Would create files:\n"
       (format-file-list (:files result))
       "\n"
       "\n"
       "Run without --dry-run to create files."))

(defn format-success
  "Format successful result based on output format."
  [format-type result]
  (case format-type
    :json (json/generate-string result {:pretty true})
    :text (if (:dry-run result)
            (format-dry-run-text result)
            (format-success-text result))
    (format-success-text result)))

(defn format-error
  "Format error message based on output format."
  [format-type errors]
  (case format-type
    :json (json/generate-string {:success false :errors errors} {:pretty true})
    :text (if (string? errors)
            (str "Error: " errors)
            (str "Errors:\n" (str/join "\n" (map #(str "  - " %) errors))))
    (str "Error: " errors)))

;; =============================================================================
;; Command Execution
;; =============================================================================

(defn validate-generate-options
  "Validate required options for generate command.
  
   Returns:
     [valid? error-messages]"
  [opts]
  (let [errors (cond-> []
                 (not (:module-name opts))
                 (conj "Missing required option: --module-name")
                 
                 (not (:entity opts))
                 (conj "Missing required option: --entity")
                 
                 (empty? (:field opts))
                 (conj "At least one --field is required"))]
    [(empty? errors) errors]))

(defn execute-generate
  "Execute generate command."
  [service opts]
  (let [[valid? errors] (validate-generate-options opts)]
    (if-not valid?
      {:status 1
       :errors errors}
      (let [[fields-valid? fields-or-errors] (parse-all-fields (:field opts))]
        (if-not fields-valid?
          {:status 1
           :errors fields-or-errors}
          (let [request {:module-name (:module-name opts)
                         :entities [{:name (:entity opts)
                                     :fields fields-or-errors}]
                         :interfaces {:http (:http opts)
                                     :cli (:cli opts)
                                     :web (:web opts)}
                         :features {:audit (:audit opts)
                                   :pagination (:pagination opts)}
                         :dry-run (:dry-run opts)}
                result (ports/generate-module service request)]
            (if (:success result)
              {:status 0
               :result result}
              {:status 1
               :errors (:errors result)})))))))

;; =============================================================================
;; Command Dispatch
;; =============================================================================

(defn dispatch-command
  "Dispatch command to appropriate executor.
  
   Args:
     verb: :generate, :list, :help
     opts: Parsed command options
     service: Scaffolder service instance
     
   Returns:
     Map with :status, :result, or :errors"
  [verb opts service]
  (case verb
    :generate (execute-generate service opts)
    (throw (ex-info (str "Unknown scaffolder command: " (name verb))
                    {:type :unknown-command
                     :message (str "Unknown command: " (name verb))}))))

;; =============================================================================
;; Help Text
;; =============================================================================

(def root-help
  "Boundary CLI - Module Scaffolding

Usage: boundary scaffolder <command> [options]

Commands:
  generate    Generate a new module with full FC/IS structure

Global Options:
  -f, --format FORMAT  Output format: text (default) or json
  -h, --help           Show help

Examples:
  boundary scaffolder generate --module-name product --entity Product \\
    --field name:string:required \\
    --field sku:string:required:unique \\
    --field price:decimal:required

For command-specific help:
  boundary scaffolder generate --help")

(def generate-help
  "Generate Module Command

Usage: boundary scaffolder generate [options]

Generates a complete Boundary module with Functional Core / Imperative Shell
architecture including:
  - Schema definitions (Malli)
  - Port protocols
  - Core business logic
  - Service orchestration
  - Persistence layer
  - HTTP routes (REST API + Web UI)
  - CLI commands
  - Database migrations

Required Options:
  --module-name NAME   Module name (lowercase, e.g., 'product', 'billing')
  --entity NAME        Entity name (PascalCase, e.g., 'Product', 'Customer')
  --field SPEC         Field specification (can be repeated)

Field Specification Format:
  name:type[:required][:unique]

Field Types:
  string     - Text field
  integer    - Integer number
  decimal    - Decimal number
  boolean    - True/false
  email      - Email address (validated)
  uuid       - UUID value
  enum       - Enumeration (specify values in code)
  date       - Date only
  datetime   - Date and time

Field Flags:
  required   - Field cannot be null
  unique     - Field must be unique across all records

Interface Options (default: all enabled):
  --http               Enable HTTP (REST API) interface
  --cli                Enable CLI interface
  --web                Enable Web UI interface

Feature Options (default: all enabled):
  --audit              Enable audit logging
  --pagination         Enable pagination support

Other Options:
  --dry-run            Show what would be generated without creating files

Examples:
  # Generate a product module
  boundary scaffolder generate \\
    --module-name product \\
    --entity Product \\
    --field name:string:required \\
    --field sku:string:required:unique \\
    --field price:decimal:required \\
    --field active:boolean:required

  # Generate a customer module with email
  boundary scaffolder generate \\
    --module-name customer \\
    --entity Customer \\
    --field name:string:required \\
    --field email:email:required:unique \\
    --field phone:string

  # Dry run to preview files
  boundary scaffolder generate \\
    --module-name billing \\
    --entity Invoice \\
    --field amount:decimal:required \\
    --dry-run")

;; =============================================================================
;; Main CLI Entry Point
;; =============================================================================

(defn run-cli!
  "Main CLI entry point for scaffolder module.
  
   Args:
     service: Scaffolder service instance
     args: Command-line arguments vector
     
   Returns:
     Exit status: 0 for success, 1 for error
     
   Side effects:
     Prints to stdout/stderr based on command and format"
  [service args]
  (try
    (let [;; Parse to extract verb
          parsed-for-verb (cli/parse-opts args global-options :in-order true)
          global-errors (:errors parsed-for-verb)
          verb-args (:arguments parsed-for-verb)
          [verb-str] verb-args
          verb (when verb-str (keyword verb-str))
          
          ;; Check for help flags early
          has-help-flag? (or (:help (:options parsed-for-verb))
                             (some #(= % "--help") args))]
      (cond
        ;; No args -> show root help
        (empty? args)
        (do
          (println root-help)
          0)
        
        ;; Global option errors
        (seq global-errors)
        (do
          (binding [*out* *err*]
            (println (format-error :text global-errors)))
          1)
        
        ;; Global --help or no command
        (or has-help-flag? (nil? verb))
        (do
          (println root-help)
          0)
        
        ;; Command-specific help
        (and (= verb :generate) has-help-flag?)
        (do
          (println generate-help)
          0)
        
        ;; Execute command
        :else
        (let [;; Get all args after the verb
              remaining-args (vec (drop 1 args))
              
              ;; Get command-specific options
              cmd-options (case verb
                            :generate generate-options
                            nil)]
          (if-not cmd-options
            (do
              (binding [*out* *err*]
                (println (format-error :text (str "Unknown command: " (name verb)))))
              1)
            (let [;; Merge global options with command options
                  all-options (into global-options cmd-options)
                  ;; Parse with merged options
                  parsed (cli/parse-opts remaining-args all-options)
                  opts (:options parsed)
                  errors (:errors parsed)
                  format-type (keyword (get opts :format "text"))]
              (if errors
                (do
                  (binding [*out* *err*]
                    (println (format-error format-type errors)))
                  1)
                (let [result (dispatch-command verb opts service)]
                  (if (:errors result)
                    (do
                      (binding [*out* *err*]
                        (println (format-error format-type (:errors result))))
                      (:status result))
                    (do
                      (println (format-success format-type (:result result)))
                      (:status result))))))))))
    (catch Exception e
      (binding [*out* *err*]
        (println "Fatal error:" (.getMessage e)))
      1)))
