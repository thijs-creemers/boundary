(ns boundary.user.shell.cli
  "CLI commands for user management.

   This is the SHELL layer in Functional Core / Imperative Shell architecture.
   Responsibilities:
   - Parse command-line arguments using tools.cli
   - Orchestrate service calls (no business logic here)
   - Format output (table or JSON)
   - Handle errors and exit codes

   All business logic lives in boundary.user.core.* and boundary.user.shell.service."
  (:require [boundary.user.ports :as ports]
            [boundary.shared.core.utils.validation :as validation]
            [boundary.shared.core.utils.type-conversion :as type-conv]
            [boundary.error-reporting.core :as error-reporting]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log])
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

(defn add-cli-breadcrumb
  "Add CLI command breadcrumb for tracking command execution.
   
   Args:
     error-reporter: IErrorContext instance from service
     command-info: Map with :domain, :verb, and :opts for command context
     status: :start, :success, or :error
     details: Optional additional details map"
  [error-reporter command-info status & [details]]
  (error-reporting/add-breadcrumb
   error-reporter ; Use error-reporter from service that implements IErrorContext
   (str (name (:domain command-info))
        " "
        (name (:verb command-info))
        " "
        (case status
          :start "initiated"
          :success "completed"
          :error "failed"))
   "cli" ; category
   :info ; level - :info for start/success, will be overridden to :error for error case
   (merge
    {:domain (name (:domain command-info))
     :command (name (:verb command-info))
     :status (name status)}
    (when-let [opts (:opts command-info)]
      {:arguments (dissoc opts :token)}) ; Remove sensitive data
    details)))

(defn add-user-operation-breadcrumb
  "Add user operation breadcrumb with action tracking.
   
   Args:
     error-reporter: IErrorContext instance from service
     operation: String describing the operation
     user-id: User ID (optional)
     tenant-id: Tenant ID (optional)
     status: :start, :success, or :error
     details: Optional additional details"
  [error-reporter operation user-id tenant-id status & [details]]
  (let [target (if user-id
                 (str "user:" user-id (when tenant-id (str " tenant:" tenant-id)))
                 (str "tenant:" tenant-id))]
    (error-reporting/track-user-action
     error-reporter
     operation
     target)))

(defn add-validation-breadcrumb
  "Add validation breadcrumb for CLI validation errors.
   
   Args:
     error-reporter: IErrorContext instance from service
     validation-type: String describing what was validated
     error-details: Map with validation error details"
  [error-reporter validation-type error-details]
  (error-reporting/add-breadcrumb
   error-reporter ; Use error-reporter from service that implements IErrorContext
   (str "CLI " validation-type " validation failed")
   "validation" ; category
   :warning ; level
   (merge
    {:validation-type validation-type
     :source "cli"}
    error-details)))

(defn mask-sensitive-token
  "Mask sensitive token data for breadcrumbs, showing only first 8 characters."
  [token]
  (when token
    (if (> (count token) 8)
      (str (subs token 0 8) "...")
      "***")))

(defn execute-user-create
  "Execute user create command."
  [service error-reporter opts]
  (let [command-info {:domain :user :verb :create :opts opts}]
    (add-cli-breadcrumb error-reporter command-info :start)
    (try
      (let [{:keys [email name role tenant-id active]} opts]
        (when-not (and email name role tenant-id)
          (add-validation-breadcrumb error-reporter "create-user-arguments"
                                     {:missing-fields (remove #(get opts %) [:email :name :role :tenant-id])
                                      :provided-fields (keys (select-keys opts [:email :name :role :tenant-id :active]))})
          (throw (ex-info "Missing required arguments"
                          {:type :validation-error
                           :message "Required: --email, --name, --role, --tenant-id"})))

        (add-user-operation-breadcrumb error-reporter "create-user" nil tenant-id :start
                                       {:email email :role role :active active})

        (let [user-data {:email email
                         :name name
                         :role role
                         :tenant-id tenant-id
                         :active active}
              result (ports/register-user service user-data)]

          (add-user-operation-breadcrumb error-reporter "create-user" (:id result) tenant-id :success
                                         {:user-id (:id result) :email email :role role})
          (add-cli-breadcrumb error-reporter command-info :success {:user-id (:id result)})

          {:status 0
           :entity-type :user
           :data result}))
      (catch Exception e
        (add-cli-breadcrumb error-reporter command-info :error {:error-message (.getMessage e)})
        (throw e)))))

(defn execute-user-list
  "Execute user list command."
  [service error-reporter opts]
  (let [command-info {:domain :user :verb :list :opts opts}]
    (add-cli-breadcrumb error-reporter command-info :start)
    (try
      (let [{:keys [tenant-id limit offset role active]} opts]
        (when-not tenant-id
          (add-validation-breadcrumb error-reporter "list-users-arguments"
                                     {:missing-fields [:tenant-id]
                                      :provided-fields (keys (select-keys opts [:tenant-id :limit :offset :role :active]))})
          (throw (ex-info "Missing required argument"
                          {:type :validation-error
                           :message "Required: --tenant-id"})))

        (add-user-operation-breadcrumb error-reporter "list-users" nil tenant-id :start
                                       {:limit limit :offset offset :role-filter role :active-filter active})

        (let [options (cond-> {:limit limit :offset offset}
                        role (assoc :filter-role role)
                        (some? active) (assoc :filter-active active))
              result (ports/list-users-by-tenant service tenant-id options)]

          (add-user-operation-breadcrumb error-reporter "list-users" nil tenant-id :success
                                         {:user-count (count (:users result)) :limit limit :offset offset})
          (add-cli-breadcrumb error-reporter command-info :success
                              {:user-count (count (:users result)) :tenant-id tenant-id})

          {:status 0
           :entity-type :user-list
           :data (:users result)}))
      (catch Exception e
        (add-cli-breadcrumb error-reporter command-info :error {:error-message (.getMessage e)})
        (throw e)))))

(defn execute-user-find
  "Execute user find command."
  [service error-reporter opts]
  (let [command-info {:domain :user :verb :find :opts opts}]
    (add-cli-breadcrumb error-reporter command-info :start)
    (try
      (let [{:keys [id email tenant-id]} opts]
        (cond
          id (do
               (add-user-operation-breadcrumb error-reporter "find-user-by-id" id nil :start {:user-id id})
               (let [result (ports/get-user-by-id service id)]
                 (when-not result
                   (add-user-operation-breadcrumb error-reporter "find-user-by-id" id nil :error
                                                  {:user-id id :error "not-found"})
                   (add-cli-breadcrumb error-reporter command-info :error {:user-id id :error "not-found"})
                   (throw (ex-info "User not found"
                                   {:type :user-not-found
                                    :message (str "No user found with ID: " id)})))
                 (add-user-operation-breadcrumb error-reporter "find-user-by-id" id (:tenant-id result) :success
                                                {:user-id id :email (:email result)})
                 (add-cli-breadcrumb error-reporter command-info :success {:user-id id :found true})
                 {:status 0
                  :entity-type :user
                  :data result}))

          (and email tenant-id)
          (do
            (add-user-operation-breadcrumb error-reporter "find-user-by-email" nil tenant-id :start
                                           {:email email :tenant-id tenant-id})
            (let [result (ports/get-user-by-email service email tenant-id)]
              (when-not result
                (add-user-operation-breadcrumb error-reporter "find-user-by-email" nil tenant-id :error
                                               {:email email :tenant-id tenant-id :error "not-found"})
                (add-cli-breadcrumb error-reporter command-info :error
                                    {:email email :tenant-id tenant-id :error "not-found"})
                (throw (ex-info "User not found"
                                {:type :user-not-found
                                 :message (str "No user found with email: " email)})))
              (add-user-operation-breadcrumb error-reporter "find-user-by-email" (:id result) tenant-id :success
                                             {:user-id (:id result) :email email})
              (add-cli-breadcrumb error-reporter command-info :success
                                  {:user-id (:id result) :email email :found true})
              {:status 0
               :entity-type :user
               :data result}))

          :else
          (do
            (add-validation-breadcrumb error-reporter "find-user-arguments"
                                       {:missing-fields "Either --id OR (--email AND --tenant-id) required"
                                        :provided-fields (keys (select-keys opts [:id :email :tenant-id]))})
            (throw (ex-info "Missing required arguments"
                            {:type :validation-error
                             :message "Required: --id OR (--email AND --tenant-id)"})))))
      (catch Exception e
        (add-cli-breadcrumb error-reporter command-info :error {:error-message (.getMessage e)})
        (throw e)))))

(defn execute-user-update
  "Execute user update command."
  [service error-reporter opts]
  (let [command-info {:domain :user :verb :update :opts opts}]
    (add-cli-breadcrumb error-reporter command-info :start)
    (try
      (let [{:keys [id name role active]} opts]
        (when-not id
          (add-validation-breadcrumb error-reporter "update-user-arguments"
                                     {:missing-fields [:id]
                                      :provided-fields (keys (select-keys opts [:id :name :role :active]))})
          (throw (ex-info "Missing required argument"
                          {:type :validation-error
                           :message "Required: --id"})))
        (when-not (or name role (some? active))
          (add-validation-breadcrumb error-reporter "update-user-fields"
                                     {:missing-fields "At least one update field required"
                                      :provided-fields (keys (select-keys opts [:name :role :active]))})
          (throw (ex-info "No fields to update"
                          {:type :validation-error
                           :message "At least one of --name, --role, or --active required"})))

        (add-user-operation-breadcrumb error-reporter "update-user" id nil :start
                                       {:user-id id :name name :role role :active active})

        (let [current-user (ports/get-user-by-id service id)]
          (when-not current-user
            (add-user-operation-breadcrumb error-reporter "update-user" id nil :error
                                           {:user-id id :error "not-found"})
            (add-cli-breadcrumb error-reporter command-info :error {:user-id id :error "not-found"})
            (throw (ex-info "User not found"
                            {:type :user-not-found
                             :message (str "No user found with ID: " id)})))
          (let [updated-user (cond-> current-user
                               name (assoc :name name)
                               role (assoc :role role)
                               (some? active) (assoc :active active))
                result (ports/update-user-profile service updated-user)]

            (add-user-operation-breadcrumb error-reporter "update-user" id (:tenant-id result) :success
                                           {:user-id id :changes (select-keys opts [:name :role :active])})
            (add-cli-breadcrumb error-reporter command-info :success
                                {:user-id id :updated-fields (keys (select-keys opts [:name :role :active]))})

            {:status 0
             :entity-type :user
             :data result})))
      (catch Exception e
        (add-cli-breadcrumb error-reporter command-info :error {:error-message (.getMessage e)})
        (throw e)))))

(defn execute-user-delete
  "Execute user delete command."
  [service error-reporter opts]
  (let [command-info {:domain :user :verb :delete :opts opts}]
    (add-cli-breadcrumb error-reporter command-info :start)
    (try
      (let [{:keys [id]} opts]
        (when-not id
          (add-validation-breadcrumb error-reporter "delete-user-arguments"
                                     {:missing-fields [:id]
                                      :provided-fields (keys (select-keys opts [:id]))})
          (throw (ex-info "Missing required argument"
                          {:type :validation-error
                           :message "Required: --id"})))

        (add-user-operation-breadcrumb error-reporter "delete-user" id nil :start {:user-id id})

        (ports/deactivate-user service id)

        (add-user-operation-breadcrumb error-reporter "delete-user" id nil :success {:user-id id})
        (add-cli-breadcrumb error-reporter command-info :success {:user-id id :action "deactivated"})

        {:status 0
         :message "User deleted successfully"})
      (catch Exception e
        (add-cli-breadcrumb error-reporter command-info :error {:error-message (.getMessage e)})
        (throw e)))))

(defn execute-session-create
  "Execute session create command."
  [service error-reporter opts]
  (let [command-info {:domain :session :verb :create :opts opts}]
    (add-cli-breadcrumb error-reporter command-info :start)
    (try
      (let [{:keys [user-id tenant-id user-agent ip-address]} opts]
        (when-not (and user-id tenant-id)
          (add-validation-breadcrumb error-reporter "create-session-arguments"
                                     {:missing-fields (remove #(get opts %) [:user-id :tenant-id])
                                      :provided-fields (keys (select-keys opts [:user-id :tenant-id :user-agent :ip-address]))})
          (throw (ex-info "Missing required arguments"
                          {:type :validation-error
                           :message "Required: --user-id, --tenant-id"})))

        (add-user-operation-breadcrumb error-reporter "create-session" user-id tenant-id :start
                                       {:user-id user-id :tenant-id tenant-id :has-user-agent (boolean user-agent) :has-ip-address (boolean ip-address)})

        (let [session-data (cond-> {:user-id user-id
                                    :tenant-id tenant-id}
                             user-agent (assoc :user-agent user-agent)
                             ip-address (assoc :ip-address ip-address))
              result (ports/authenticate-user service session-data)]

          (add-user-operation-breadcrumb error-reporter "create-session" user-id tenant-id :success
                                         {:user-id user-id :session-token (mask-sensitive-token (:token result))})
          (add-cli-breadcrumb error-reporter command-info :success
                              {:user-id user-id :session-token (mask-sensitive-token (:token result))})

          {:status 0
           :entity-type :session
           :data result}))
      (catch Exception e
        (add-cli-breadcrumb error-reporter command-info :error {:error-message (.getMessage e)})
        (throw e)))))

(defn execute-session-invalidate
  "Execute session invalidate command."
  [service error-reporter opts]
  (let [command-info {:domain :session :verb :invalidate :opts (update opts :token mask-sensitive-token)}]
    (add-cli-breadcrumb error-reporter command-info :start)
    (try
      (let [{:keys [token]} opts]
        (when-not token
          (add-validation-breadcrumb error-reporter "invalidate-session-arguments"
                                     {:missing-fields [:token]
                                      :provided-fields (keys (select-keys opts [:token]))})
          (throw (ex-info "Missing required argument"
                          {:type :validation-error
                           :message "Required: --token"})))

        (add-user-operation-breadcrumb error-reporter "invalidate-session" nil nil :start
                                       {:session-token (mask-sensitive-token token)})

        (let [result (ports/logout-user service token)]
          (if result
            (do
              (add-user-operation-breadcrumb error-reporter "invalidate-session" nil nil :success
                                             {:session-token (mask-sensitive-token token)})
              (add-cli-breadcrumb error-reporter command-info :success
                                  {:session-token (mask-sensitive-token token) :action "invalidated"})
              {:status 0
               :message "Session invalidated successfully"})
            (do
              (add-user-operation-breadcrumb error-reporter "invalidate-session" nil nil :error
                                             {:session-token (mask-sensitive-token token) :error "not-found"})
              (add-cli-breadcrumb error-reporter command-info :error
                                  {:session-token (mask-sensitive-token token) :error "not-found"})
              (throw (ex-info "Session not found"
                              {:type :session-not-found
                               :message (str "No session found with token: " token)}))))))
      (catch Exception e
        (add-cli-breadcrumb error-reporter command-info :error {:error-message (.getMessage e)})
        (throw e)))))

(defn execute-session-list
  "Execute session list command."
  [service error-reporter opts]
  (let [command-info {:domain :session :verb :list :opts opts}]
    (add-cli-breadcrumb error-reporter command-info :start)
    (try
      (let [{:keys [user-id]} opts]
        (when-not user-id
          (add-validation-breadcrumb error-reporter "list-sessions-arguments"
                                     {:missing-fields [:user-id]
                                      :provided-fields (keys (select-keys opts [:user-id]))})
          (throw (ex-info "Missing required argument"
                          {:type :validation-error
                           :message "Required: --user-id"})))

        (add-user-operation-breadcrumb error-reporter "list-sessions" user-id nil :start {:user-id user-id})

        (let [sessions (.find-sessions-by-user (get service :session-repository service) user-id)]

          (add-user-operation-breadcrumb error-reporter "list-sessions" user-id nil :success
                                         {:user-id user-id :session-count (count sessions)})
          (add-cli-breadcrumb error-reporter command-info :success
                              {:user-id user-id :session-count (count sessions)})

          {:status 0
           :entity-type :session-list
           :data sessions}))
      (catch Exception e
        (add-cli-breadcrumb error-reporter command-info :error {:error-message (.getMessage e)})
        (throw e)))))

;; =============================================================================
;; Command Dispatch
;; =============================================================================

(defn dispatch-command
  "Dispatch command to appropriate executor.

   Args:
     domain: :user or :session
     verb: :create, :list, :find, :update, :delete, :invalidate
     opts: Parsed command options
     service: User service instance
     error-reporter: IErrorContext instance from service

   Returns:
     Map with :status, :entity-type, :data, or :message"
  [domain verb opts service error-reporter]
  (case domain
    :user (case verb
            :create (execute-user-create service error-reporter opts)
            :list (execute-user-list service error-reporter opts)
            :find (execute-user-find service error-reporter opts)
            :update (execute-user-update service error-reporter opts)
            :delete (execute-user-delete service error-reporter opts)
            (throw (ex-info (str "Unknown user command: " (name verb))
                            {:type :unknown-command
                             :message (str "Unknown command: user " (name verb))})))
    :session (case verb
               :create (execute-session-create service error-reporter opts)
               :invalidate (execute-session-invalidate service error-reporter opts)
               :list (execute-session-list service error-reporter opts)
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
  (let [error-reporter (:error-reporter service)]
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

          ;; Add CLI session breadcrumb
          (when (and domain verb)
            (add-cli-breadcrumb error-reporter {:domain domain :verb verb :opts {}} :start))

          ;; Check for global option errors first (e.g., invalid --format value)
          (if (seq global-errors)
            (do
              (when (and domain verb)
                (add-cli-breadcrumb error-reporter {:domain domain :verb verb :opts {}} :error
                                    {:error "parse-error" :details (str/join ", " global-errors)}))
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
                    (add-cli-breadcrumb error-reporter {:domain domain :verb verb :opts {}} :error
                                        {:error "unknown-command" :domain-str domain-str :verb-str verb-str})
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
                        (add-cli-breadcrumb error-reporter {:domain domain :verb verb :opts opts} :error
                                            {:error "parse-error" :details (str/join ", " errors)})
                        (binding [*out* *err*]
                          (println (format-error format-type
                                                 {:type :parse-error
                                                  :message "Invalid arguments"
                                                  :details (str/join ", " errors)})))
                        1)
                      (let [result (dispatch-command domain verb opts service error-reporter)]
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
          (log/error "CLI command failed" {:error (.getMessage e) :data ex-data})
          ;; Add top-level error breadcrumb
          (error-reporting/add-breadcrumb error-reporter "CLI execution failed" "cli" :error
                                          {:error-type (or (:type ex-data) :error)
                                           :error-message (.getMessage e)
                                           :source "cli-top-level"})
          (binding [*out* *err*]
            (println (format-error format-type error-data)))
          1)))))
