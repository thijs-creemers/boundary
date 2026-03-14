(ns boundary.ai.core.prompts
  "Pure prompt-building functions for AI features.

   FC/IS rule: no I/O, no logging, no exceptions here.
   All functions are pure transformations from data to prompt strings/messages."
  (:require [clojure.string :as str]))

;; =============================================================================
;; System prompt — shared framework context
;; =============================================================================

(def ^:private framework-system-context
  "You are an AI assistant integrated into Boundary, a Clojure monorepo framework.

Boundary follows the Functional Core / Imperative Shell (FC/IS) pattern strictly:
- core/ namespaces: pure functions only — no I/O, no logging, no side effects
- shell/ namespaces: all side effects — HTTP handlers, DB, logging, external APIs
- ports.clj: protocol definitions (interfaces)
- schema.clj: Malli validation schemas

Key naming conventions:
- Clojure code: kebab-case (:password-hash, :created-at, my-function)
- Database boundary: snake_case (password_hash, created_at)
- API boundary: camelCase (passwordHash, createdAt)
- Always use boundary.shared.core.utils.case-conversion for conversions

Key technologies:
- Clojure 1.12.4, Integrant (DI/lifecycle), Aero (config)
- Ring/Reitit (HTTP), next.jdbc + HoneySQL (DB), Malli (validation)
- Buddy (auth/JWT), Hiccup + HTMX (UI), Kaocha (tests)

Testing strategy:
- ^:unit   — pure core functions, no mocks
- ^:integration — shell services with mocked adapters
- ^:contract — adapters against real DB (H2 in-memory)")

;; =============================================================================
;; Feature 1: NL Scaffolding
;; =============================================================================

(defn build-scaffolding-system-prompt
  "Build the system prompt for natural-language module scaffolding.

   Returns a string."
  []
  (str framework-system-context "

Your task: parse a natural language description of a Boundary module into a structured JSON specification.

Output ONLY valid JSON with this exact structure:
{
  \"module-name\": \"kebab-case-name\",
  \"entity\": \"PascalCaseName\",
  \"fields\": [
    {\"name\": \"field-name\", \"type\": \"string|text|int|decimal|boolean|email|uuid|enum|date|json\", \"required\": true|false, \"unique\": false}
  ],
  \"http\": true,
  \"web\": true
}

Rules:
- module-name MUST be kebab-case (e.g. product, order-item, user-profile)
- entity MUST be PascalCase (e.g. Product, OrderItem, UserProfile)
- field names MUST be kebab-case
- valid field types: string, text, int, decimal, boolean, email, uuid, enum, date, json
- default required=true, unique=false unless stated otherwise
- default http=true, web=true unless stated otherwise
- respond with ONLY the JSON object, no explanation, no markdown fences"))

(defn build-scaffolding-user-prompt
  "Build the user message for NL scaffolding.

   Args:
     description     - natural language description string
     existing-modules - seq of existing module name strings (for context)

   Returns a string."
  [description existing-modules]
  (str "Parse this module description into the JSON specification:\n\n"
       description
       (when (seq existing-modules)
         (str "\n\nExisting modules in this project (for context, avoid name clashes): "
              (str/join ", " existing-modules)))))

(defn scaffolding-messages
  "Return a messages vector for the scaffolding feature.

   Args:
     description      - NL description string
     existing-modules - seq of existing module name strings

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [description existing-modules]
  [{:role :system :content (build-scaffolding-system-prompt)}
   {:role :user   :content (build-scaffolding-user-prompt description existing-modules)}])

;; =============================================================================
;; Feature 2: Error Explainer
;; =============================================================================

(defn build-error-explainer-system-prompt
  "Build the system prompt for the error explainer feature.

   Returns a string."
  []
  (str framework-system-context "

Your task: explain a Clojure/Boundary stack trace and suggest a fix.

Structure your response as:
1. Root cause (1-2 sentences)
2. Affected code (reference the file:line from the trace)
3. Fix suggestion (concrete code change)
4. FC/IS relevance (only if the error relates to a boundary violation)

Known common error patterns in Boundary:
- Malli validation failures: ExceptionInfo with :type :malli/validation
- HoneySQL key errors: keywords must be qualified (e.g. :users/id not :id in joins)
- Integrant init failures: usually missing ref or bad config key
- Jedis connection errors: Redis not running or wrong host/port
- Case mismatch: kebab-case in Clojure but snake_case expected by DB adapter"))

(defn build-error-explainer-user-prompt
  "Build the user message for error explaining.

   Args:
     stacktrace   - stack trace string
     source-files - map of {file-path content-str} for referenced files

   Returns a string."
  [stacktrace source-files]
  (str "Explain this Clojure/Boundary error and suggest a fix:\n\n"
       "```\n" stacktrace "\n```"
       (when (seq source-files)
         (str "\n\nReferenced source files:\n"
              (str/join "\n\n"
                        (map (fn [[path content]]
                               (str "--- " path " ---\n" content))
                             source-files))))))

(defn error-explainer-messages
  "Return a messages vector for the error explainer feature.

   Args:
     stacktrace   - stack trace string
     source-files - map of {file-path content-str}

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [stacktrace source-files]
  [{:role :system :content (build-error-explainer-system-prompt)}
   {:role :user   :content (build-error-explainer-user-prompt stacktrace source-files)}])

;; =============================================================================
;; Feature 3: Test Generator
;; =============================================================================

(defn build-test-generator-system-prompt
  "Build the system prompt for the test generator feature.

   Returns a string."
  []
  (str framework-system-context "

Your task: generate a complete Kaocha-compatible test namespace for a Boundary source file.

Rules:
- For core/ files: use ^:unit metadata on the deftest forms
- For shell/ files: use ^:integration metadata
- For adapter tests: use ^:contract metadata
- Use clojure.test (deftest, is, testing)
- Test each public function with: happy path, edge cases, nil/empty inputs
- For pure functions: no mocking needed
- For shell services: mock protocols using reify
- Namespace: replace src/ with test/ and add -test suffix to ns name
- Follow existing test patterns: (deftest function-name-test (testing \"description\" (is (= expected (f args)))))

Output ONLY valid Clojure code — no markdown fences, no explanation."))

(defn build-test-generator-user-prompt
  "Build the user message for test generation.

   Args:
     source-file - path string of the source file
     source-code - content of the source file
     test-type   - :unit, :integration, or :contract

   Returns a string."
  [source-file source-code test-type]
  (str "Generate a complete test namespace for this Boundary source file.\n"
       "Test type: " (name test-type) "\n"
       "Source file: " source-file "\n\n"
       "```clojure\n" source-code "\n```"))

(defn test-generator-messages
  "Return a messages vector for the test generator feature.

   Args:
     source-file - path string
     source-code - file content string
     test-type   - :unit, :integration, or :contract

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [source-file source-code test-type]
  [{:role :system :content (build-test-generator-system-prompt)}
   {:role :user   :content (build-test-generator-user-prompt source-file source-code test-type)}])

;; =============================================================================
;; Feature 4: SQL Copilot
;; =============================================================================

(defn build-sql-copilot-system-prompt
  "Build the system prompt for the SQL copilot feature.

   Returns a string."
  []
  (str framework-system-context "

Your task: convert a natural language database query description into HoneySQL format.

Output JSON with this structure:
{
  \"honeysql\": \"the HoneySQL map as a Clojure-readable EDN string\",
  \"explanation\": \"brief explanation of the query logic\",
  \"raw-sql\": \"equivalent raw SQL for reference\"
}

HoneySQL rules for Boundary:
- Use keyword qualifications for ambiguous columns: :table/column
- Use {:select [:col1 :col2] :from [:table] :where [...] :order-by [...] :limit n}
- Date functions: [:> :created-at [:raw \"NOW() - INTERVAL '7 days'\"]] for PostgreSQL
- Joins: {:join [:other-table [:= :table/id :other-table/foreign-id]]}
- Always use kebab-case keywords (HoneySQL converts to snake_case automatically)
- is/is-not for NULL checks: [:is :column nil] or [:is-not :column nil]

Output ONLY the JSON object, no markdown fences."))

(defn build-sql-copilot-user-prompt
  "Build the user message for SQL copilot.

   Args:
     description    - NL description of the query
     schema-context - string describing available tables and fields

   Returns a string."
  [description schema-context]
  (str "Convert this description to HoneySQL:\n\n"
       description
       (when (seq schema-context)
         (str "\n\nAvailable schema context:\n" schema-context))))

(defn sql-copilot-messages
  "Return a messages vector for the SQL copilot feature.

   Args:
     description    - NL description string
     schema-context - string describing tables/fields

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [description schema-context]
  [{:role :system :content (build-sql-copilot-system-prompt)}
   {:role :user   :content (build-sql-copilot-user-prompt description schema-context)}])

;; =============================================================================
;; Feature 5: Documentation Wizard
;; =============================================================================

(defn build-docs-system-prompt
  "Build the system prompt for the documentation wizard.

   Args:
     doc-type - :agents, :openapi, or :readme

   Returns a string."
  [doc-type]
  (str framework-system-context "\n\n"
       (case doc-type
         :agents
         "Your task: generate an AGENTS.md developer guide for a Boundary library module.

Structure:
1. ## 1. Purpose — what the module does, what problem it solves
2. ## 2. Key Namespaces — markdown table with Namespace | Layer | Responsibility
3. ## 3. Integrant Configuration — edn config example
4. ## 4. Public API — usage examples with (require ...) and example calls
5. ## 5. Common Pitfalls — numbered list of known gotchas
6. ## 6. Testing Commands — bash code block with test commands

Output ONLY the markdown content."

         :openapi
         "Your task: generate an OpenAPI 3.x YAML specification for a Boundary HTTP module.

Extract endpoints from the Reitit route definitions and Malli schemas.
Use application/json for all request/response bodies.
Include example values where possible.

Output ONLY valid YAML starting with 'openapi: \"3.0.3\"'."

         :readme
         "Your task: generate a README.md for a Boundary library module.

Structure: module name as h1, short description, Installation (deps.edn snippet), Quick Start, Configuration, API Reference, License.

Output ONLY the markdown content.")))

(defn build-docs-user-prompt
  "Build the user message for documentation generation.

   Args:
     module-path  - path string to the module (e.g. 'libs/user')
     source-files - map of {relative-path content-str}
     doc-type     - :agents, :openapi, or :readme

   Returns a string."
  [module-path source-files doc-type]
  (str "Generate " (name doc-type) " documentation for the Boundary module at: " module-path "\n\n"
       "Source files:\n"
       (str/join "\n\n"
                 (map (fn [[path content]]
                        (str "--- " path " ---\n```clojure\n" content "\n```"))
                      source-files))))

(defn docs-messages
  "Return a messages vector for the documentation wizard.

   Args:
     module-path  - module root path string
     source-files - map of {relative-path content-str}
     doc-type     - :agents, :openapi, or :readme

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [module-path source-files doc-type]
  [{:role :system :content (build-docs-system-prompt doc-type)}
   {:role :user   :content (build-docs-user-prompt module-path source-files doc-type)}])
