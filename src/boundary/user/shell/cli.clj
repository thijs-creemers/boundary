(ns boundary.user.shell.cli
  "CLI commands for user management.

   This is the SHELL layer in Functional Core / Imperative Shell architecture.
   Responsibilities:
   - Parse command-line arguments using tools.cli
   - Orchestrate service calls (no business logic here)
   - Format output (table or JSON)
   - Handle errors and exit codes

   All business logic lives in boundary.user.core.* and boundary.user.shell.service.
   All observability is handled automatically by interceptors."
  (:require [boundary.user.ports :as ports]
            [boundary.shared.core.utils.validation :as validation]
            [boundary.shared.core.utils.type-conversion :as type-conv]
            [boundary.shared.core.interceptor :as interceptor]
            [boundary.shared.core.interceptor-context :as interceptor-context]
            [boundary.user.shell.interceptors :as user-interceptors]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli])
  (:import [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Global CLI Options
;; =============================================================================

(def global-options
  [["-f" "--format FORMAT" "Output format: table (default) or json"
    :default "table"
    :validate [validation/valid-output-format? "Must be 'table' or 'json'"]]
   ["-h" "--help" "Show help"]])

;; =============================================================================
;; User Command Options
;; =============================================================================

(def user-create-options
  [[nil "--email EMAIL" "User email address (required)"
    :validate [#(re-matches #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$" %)
               "Must be a valid email address"]]
   [nil "--name NAME" "User full name (required)"]
   [nil "--role ROLE" "User role: admin, user, or viewer (required)"
    :parse-fn keyword
    :validate [#(contains? #{:admin :user :viewer} %) "Must be admin, user, or viewer"]]
   [nil "--tenant-id UUID" "Tenant UUID (required)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]
   [nil "--active BOOL" "User active status (default: true)"
    :default true
    :parse-fn type-conv/parse-bool
    :validate [some? "Must be true, false, yes, no, 1, or 0"]]])

(def user-list-options
  [[nil "--tenant-id UUID" "Tenant UUID (required)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]
   [nil "--limit N" "Maximum number of results (default: 20)"
    :default 20
    :parse-fn type-conv/parse-int
    :validate [some? "Must be a positive integer"]]
   [nil "--offset N" "Number of results to skip (default: 0)"
    :default 0
    :parse-fn #(Integer/parseInt %)
    :validate [#(>= % 0) "Must be non-negative"]]
   [nil "--role ROLE" "Filter by role: admin, user, or viewer"
    :parse-fn keyword
    :validate [#(contains? #{:admin :user :viewer} %) "Must be admin, user, or viewer"]]
   [nil "--active BOOL" "Filter by active status"
    :parse-fn type-conv/parse-bool
    :validate [some? "Must be true, false, yes, no, 1, or 0"]]])

(def user-find-options
  [[nil "--id UUID" "User ID"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]
   [nil "--email EMAIL" "User email address"
    :validate [#(re-matches #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$" %)
               "Must be a valid email address"]]
   [nil "--tenant-id UUID" "Tenant UUID (required with --email)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]])

(def user-update-options
  [[nil "--id UUID" "User ID (required)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]
   [nil "--name NAME" "User full name"]
   [nil "--role ROLE" "User role: admin, user, or viewer"
    :parse-fn keyword
    :validate [#(contains? #{:admin :user :viewer} %) "Must be admin, user, or viewer"]]
   [nil "--active BOOL" "User active status"
    :parse-fn type-conv/parse-bool
    :validate [some? "Must be true, false, yes, no, 1, or 0"]]])

(def user-delete-options
  [[nil "--id UUID" "User ID (required)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]])

;; =============================================================================
;; Session Command Options
;; =============================================================================

(def session-create-options
  [[nil "--user-id UUID" "User UUID (required)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]
   [nil "--tenant-id UUID" "Tenant UUID (required)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]
   [nil "--user-agent AGENT" "User agent string"]
   [nil "--ip-address IP" "IP address"]])

(def session-invalidate-options
  [[nil "--token TOKEN" "Session token (required)"]])

(def session-list-options
  [[nil "--user-id UUID" "User UUID (required)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]])

;; =============================================================================
;; Output Formatting - Time
;; =============================================================================

(def default-datetime-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn format-instant
  "Format an Instant as a human-readable string."
  [instant]
  (when instant
    (.format default-datetime-formatter
             (.atZone instant (java.time.ZoneId/systemDefault)))))

(defn truncate-string
  "Truncate string to max-length with ellipsis."
  [s max-length]
  (if (and s (> (count s) max-length))
    (str (subs s 0 (- max-length 3)) "...")
    s))

;; =============================================================================
;; Output Formatting - Table
;; =============================================================================

(defn format-table-row
  "Format a single row with column widths."
  [row widths]
  (str "| "
       (str/join " | "
                 (map (fn [val width]
                        (let [s (str val)]
                          (format (str "%-" width "s") s)))
                      row
                      widths))
       " |"))

(defn format-table-separator
  "Create a table separator line."
  [widths]
  (str "+-"
       (str/join "-+-"
                 (map #(apply str (repeat % "-")) widths))
       "-+"))

(defn render-table
  "Render data as a formatted table.

   Args:
     headers: Vector of column header strings
     rows: Vector of vectors containing row data

   Returns:
     Formatted table string"
  [headers rows]
  (if (empty? rows)
    "No results found."
    (let [;; Calculate column widths
          widths (reduce (fn [ws row]
                           (map max ws (map #(count (str %)) row)))
                         (map count headers)
                         rows)
          separator (format-table-separator widths)
          header-row (format-table-row headers widths)]
      (str separator "\n"
           header-row "\n"
           separator "\n"
           (str/join "\n" (map #(format-table-row % widths) rows)) "\n"
           separator))))

(defn format-user-table
  "Format users as a table."
  [users]
  (let [headers ["ID" "Email" "Name" "Role" "Active" "Created"]
        rows (map (fn [user]
                    [(truncate-string (str (:id user)) 36)
                     (truncate-string (:email user) 30)
                     (truncate-string (:name user) 25)
                     (name (:role user))
                     (str (:active user))
                     (format-instant (:created-at user))])
                  users)]
    (render-table headers rows)))

(defn format-session-table
  "Format sessions as a table."
  [sessions]
  (let [headers ["Token" "User ID" "Created" "Expires" "Revoked"]
        rows (map (fn [session]
                    [(:session-token session) ; Don't truncate token - needed for invalidation
                     (truncate-string (str (:user-id session)) 36)
                     (format-instant (:created-at session))
                     (format-instant (:expires-at session))
                     (if (:revoked-at session) "Yes" "No")])
                  sessions)]
    (render-table headers rows)))

;; =============================================================================
;; Output Formatting - JSON
;; =============================================================================

(defn format-instant-json
  "Format Instant for JSON output."
  [instant]
  (when instant
    (str instant)))

(defn user->json
  "Transform user entity for JSON output."
  [user]
  (-> user
      (update :id str)
      (update :tenant-id str)
      (update :role name)
      (update :created-at format-instant-json)
      (update :updated-at format-instant-json)
      (update :deleted-at format-instant-json)
      (update :last-login format-instant-json)))

(defn session->json
  "Transform session entity for JSON output."
  [session]
  (-> session
      (update :id str)
      (update :user-id str)
      (update :tenant-id str)
      (update :created-at format-instant-json)
      (update :expires-at format-instant-json)
      (update :last-accessed-at format-instant-json)
      (update :revoked-at format-instant-json)))

(defn format-json
  "Format data as pretty JSON."
  [data]
  (json/generate-string data {:pretty true}))

;; =============================================================================
;; Output Formatting - Dispatcher
;; =============================================================================

(defn format-success
  "Format successful result based on output format.

   Args:
     format-type: :table or :json
     entity-type: :user, :user-list, :session, :session-list
     data: Entity or collection to format"
  [format-type entity-type data]
  (case format-type
    :json (case entity-type
            :user (format-json (user->json data))
            :user-list (format-json {:users (map user->json data)
                                     :count (count data)})
            :session (format-json (session->json data))
            :session-list (format-json {:sessions (map session->json data)
                                        :count (count data)}))
    :table (case entity-type
             :user (format-user-table [data])
             :user-list (format-user-table data)
             :session (format-session-table [data])
             :session-list (format-session-table data))))

(defn format-error
  "Format error message based on output format."
  [format-type error-data]
  (case format-type
    :json (format-json {:error error-data})
    :table (str "Error: " (:message error-data)
                (when-let [details (:details error-data)]
                  (str "\nDetails: " details)))))

;; =============================================================================
;; Command Execution
;; =============================================================================

(defn extract-observability-services
  "Extracts observability services from user-service for interceptor context.
   
   Note: Since service layer cleanup removed direct observability dependencies,
   the interceptor context will obtain these services from the system wiring."
  [user-service]
  {:user-service user-service})

(defn create-cli-interceptor-context
  "Creates interceptor context for CLI operations with real observability services."
  [operation-type user-service args options]
  (-> (interceptor-context/create-cli-context-with-system
       operation-type
       (extract-observability-services user-service)
       args
       options)
      (assoc :opts options)))

(defn execute-user-create
  "Execute user create command using interceptor pipeline.
   
   This version demonstrates the interceptor-based approach that eliminates
   manual observability boilerplate while providing comprehensive tracking."
  [service error-reporter opts]
  (let [;; Create context for the operation
        context (create-cli-interceptor-context
                 :user-create
                 service
                 [] ;; args (not used for this CLI pattern)
                 opts)

        ;; Create the interceptor pipeline for user creation
        pipeline (user-interceptors/create-user-creation-pipeline :cli)

        ;; Execute the pipeline
        result-context (interceptor/run-pipeline context pipeline)]

    ;; Return the response from context
    (:response result-context)))

(defn execute-user-get
  "Execute user get command using interceptor pipeline."
  [service error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :user-get
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-user-get-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-user-list
  "Execute user list command using interceptor pipeline."
  [service error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :user-list
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-user-list-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-user-update
  "Execute user update command using interceptor pipeline."
  [service error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :user-update
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-user-update-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-user-delete
  "Execute user delete command using interceptor pipeline."
  [service error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :user-delete
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-user-delete-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-session-create
  "Execute session create command using interceptor pipeline."
  [service error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :session-create
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-session-creation-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-session-validate-v2
  "Execute session validate command using interceptor pipeline."
  [service error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :session-validate
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-session-validation-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-session-invalidate
  "Execute session invalidate command using interceptor pipeline."
  [service error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :session-invalidate
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-session-invalidation-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-session-list
  "Execute session list command - NOT IMPLEMENTED"
  [service error-reporter opts]
  ;; TODO: Implement session list functionality
  ;; This would require:
  ;; 1. Creating session list interceptors in user-interceptors.clj
  ;; 2. Implementing session list functionality in the service layer
  ;; 3. Adding appropriate business logic in core layer
  {:status 501
   :body "Session list functionality not yet implemented"})

;; =============================================================================
;; Command Dispatch
;; =============================================================================

(defn dispatch-command
  "Dispatch command to appropriate executor using interceptor pipeline.

   Args:
     domain: :user or :session
     verb: :create, :list, :find, :update, :delete, :invalidate
     opts: Parsed command options
     service: User service instance

   Returns:
     Map with :status, :entity-type, :data, or :message"
  [domain verb opts service]
  (case domain
    :user (case verb
            :create (execute-user-create service nil opts)
            :list (execute-user-list service nil opts)
            :find (execute-user-get service nil opts) ; Note: find -> get mapping
            :update (execute-user-update service nil opts)
            :delete (execute-user-delete service nil opts)
            (throw (ex-info (str "Unknown user command: " (name verb))
                            {:type :unknown-command
                             :message (str "Unknown command: user " (name verb))})))
    :session (case verb
               :create (execute-session-create service nil opts)
               :invalidate (execute-session-invalidate service nil opts)
               :list (execute-session-list service nil opts) ; Note: needs implementing
               (throw (ex-info (str "Unknown session command: " (name verb))
                               {:type :unknown-command
                                :message (str "Unknown command: session " (name verb))})))
    (throw (ex-info (str "Unknown domain: " (name domain))
                    {:type :unknown-domain
                     :message (str "Unknown domain: " (name domain))}))))

;; =============================================================================
;; Help Text
;; =============================================================================

(def root-help
  "Boundary CLI - User and Session Management

Usage: boundary <domain> <command> [options]

Domains:
  user       User management commands
  session    Session management commands

Global Options:
  -f, --format FORMAT  Output format: table (default) or json
  -h, --help           Show help

Examples:
  boundary user create --email john@example.com --name \"John Doe\" --role user --tenant-id UUID
  boundary user list --tenant-id UUID --format json
  boundary session create --user-id UUID --tenant-id UUID

For domain-specific help:
  boundary user --help
  boundary session --help")

(def user-help
  "User Management Commands

Usage: boundary user <command> [options]

Commands:
  create    Create a new user
  list      List users in a tenant
  find      Find a user by ID or email
  update    Update user properties
  delete    Soft-delete a user

Options for 'create':
  --email EMAIL        User email address (required)
  --name NAME          User full name (required)
  --role ROLE          User role: admin, user, or viewer (required)
  --tenant-id UUID     Tenant UUID (required)
  --active BOOL        User active status (default: true)

Options for 'list':
  --tenant-id UUID     Tenant UUID (required)
  --limit N            Maximum results (default: 20)
  --offset N           Results to skip (default: 0)
  --role ROLE          Filter by role
  --active BOOL        Filter by active status

Options for 'find':
  --id UUID            User ID
  --email EMAIL        User email
  --tenant-id UUID     Tenant UUID (required with --email)
  Note: Use --id OR (--email AND --tenant-id)

Options for 'update':
  --id UUID            User ID (required)
  --name NAME          New name
  --role ROLE          New role
  --active BOOL        New active status
  Note: At least one of --name, --role, or --active required

Options for 'delete':
  --id UUID            User ID (required)

Examples:
  boundary user create --email john@example.com --name \"John\" --role user --tenant-id UUID
  boundary user list --tenant-id UUID --limit 10
  boundary user find --id UUID
  boundary user find --email john@example.com --tenant-id UUID
  boundary user update --id UUID --role admin
  boundary user delete --id UUID")

(def session-help
  "Session Management Commands

Usage: boundary session <command> [options]

Commands:
  create       Create a new session (login)
  invalidate   Invalidate a session (logout)
  list         List sessions for a user

Options for 'create':
  --user-id UUID       User UUID (required)
  --tenant-id UUID     Tenant UUID (required)
  --user-agent AGENT   User agent string (optional)
  --ip-address IP      IP address (optional)

Options for 'invalidate':
  --token TOKEN        Session token (required)

Options for 'list':
  --user-id UUID       User UUID (required)

Examples:
  boundary session create --user-id UUID --tenant-id UUID
  boundary session invalidate --token TOKEN
  boundary session list --user-id UUID")

;; =============================================================================
;; Main CLI Entry Point
;; =============================================================================

(defn run-cli!
  "Main CLI entry point. Parses arguments, executes commands, and returns status.

   Args:
     service: User service instance
     args: Command-line arguments vector

   Returns:
     Exit status: 0 for success, 1 for error

   Side effects:
     Prints to stdout/stderr based on command and format"
  [service args]
  (try
    (if (empty? args)
      (do
        (println root-help)
        0)
      (let [;; Parse to extract domain and verb (skip option flags and their values)
            ;; We need to skip pairs like ["--format" "json"] and ["--help"]
            parsed-for-domain (cli/parse-opts args global-options :in-order true)
            global-errors (:errors parsed-for-domain)
            domain-args (:arguments parsed-for-domain)
            [domain-str verb-str] domain-args
            domain (when domain-str (keyword domain-str))
            verb (when verb-str (keyword verb-str))

            ;; Check for help flags early
            has-help-flag? (or (:help (:options parsed-for-domain))
                               (some #(= % "--help") args))
            help-as-verb? (= verb-str "--help")]

        ;; Check for global option errors first (e.g., invalid --format value)
        (if (seq global-errors)
          (do
            (binding [*out* *err*]
              (println (format-error :table
                                     {:type :parse-error
                                      :message "Invalid arguments"
                                      :details (str/join ", " global-errors)})))
            1)
          ;; Otherwise continue with help and command dispatch
          (cond
          ;; Global --help or no domain
            (and has-help-flag? (nil? domain))
            (do (println root-help) 0)

          ;; Domain-level --help: user --help or user create --help
            (and (= domain :user) (or help-as-verb? has-help-flag?))
            (do (println user-help) 0)

            (and (= domain :session) (or help-as-verb? has-help-flag?))
            (do (println session-help) 0)

          ;; Legacy: domain help verb
            (and (= domain :user) (= verb :help))
            (do (println user-help) 0)

            (and (= domain :session) (= verb :help))
            (do (println session-help) 0)

          ;; Execute command - parse options now
            (and domain verb)
            (let [;; Get all args after domain and verb
                  domain-verb-count 2
                  remaining-args (vec (drop domain-verb-count args))

                 ;; Get command-specific options
                  cmd-options (case domain
                                :user (case verb
                                        :create user-create-options
                                        :list user-list-options
                                        :find user-find-options
                                        :update user-update-options
                                        :delete user-delete-options
                                        nil)
                                :session (case verb
                                           :create session-create-options
                                           :invalidate session-invalidate-options
                                           :list session-list-options
                                           nil)
                                nil)]
              (if-not cmd-options
                (do
                  (binding [*out* *err*]
                    (println (format-error :table
                                           {:type :unknown-command
                                            :message (str "Unknown command: " domain-str " " verb-str)})))
                  1)
                (let [;; Merge global options with command options
                      all-options (into global-options cmd-options)
                     ;; Parse with merged options
                      parsed (cli/parse-opts remaining-args all-options)
                      opts (:options parsed)
                      errors (:errors parsed)
                      format-type (keyword (get opts :format "table"))]
                  (if errors
                    (do
                      (binding [*out* *err*]
                        (println (format-error format-type
                                               {:type :parse-error
                                                :message "Invalid arguments"
                                                :details (str/join ", " errors)})))
                      1)
                    (let [result (dispatch-command domain verb opts service)]
                      (if (:message result)
                        (println (:message result))
                        (println (format-success format-type
                                                 (:entity-type result)
                                                 (:data result))))
                      (:status result))))))

            :else
            (do (println root-help) 0)))))

    (catch Exception e
      (let [ex-data (ex-data e)
            format-type (or (try (keyword (get-in (cli/parse-opts args global-options)
                                                  [:options :format]))
                                 (catch Exception _ :table))
                            :table)
            error-data {:type (or (:type ex-data) :error)
                        :message (.getMessage e)
                        :details (dissoc ex-data :type)}]
        (binding [*out* *err*]
          (println (format-error format-type error-data)))
        1))))
