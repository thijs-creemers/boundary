(ns boundary.user.core.ui
  "User-specific UI components based on User schema.
   
   This namespace contains pure functions for generating user-related Hiccup
   structures. Components are derived from the User schema and handle
   user-specific business logic for display and forms."
  (:require [boundary.shared.ui.core.components :as ui]
            [boundary.shared.ui.core.layout :as layout]
            [boundary.shared.ui.core.table :as table-ui]
            [boundary.shared.ui.core.icons :as icons]
            [boundary.shared.web.table :as web-table]
            [clojure.string :as str]))

;; =============================================================================
;; User Table Components
;; =============================================================================

(defn user-row
  "Generate user table row data based on User schema.
   
   Args:
     user: User entity map (from User schema)
     
   Returns:
     Vector of cell values for table row (some as Hiccup structures)"
  [user]
  (let [role (:role user)
        active? (boolean (:active user))]
    (with-meta
      [(:id user)
       (:name user)
       (:email user)
       [:span {:class (str "role-badge " (name role))}
        (-> role name str/capitalize)]
       [:span {:class (str "status-badge " (if active? "active" "inactive"))}
        (if active? "Active" "Inactive")]
       ""]
      {:onclick (str "window.location.href='/web/users/" (:id user) "'")})))

(defn users-table-fragment
  "Generate just the users table container fragment (for HTMX refresh).
   
   Args:
     users:       Collection of User entity maps
     table-query: Normalized TableQuery (see boundary.shared.web.table)
     total-count: Total number of users
     filters:     Optional map of parsed search filters (see boundary.shared.web.table)

   Returns:
     Hiccup structure for users table container (no page layout)"
  ([users table-query total-count]
   (users-table-fragment users table-query total-count {}))
  ([users table-query total-count filters]
   (let [{:keys [sort dir page page-size]} table-query
         base-url      "/web/users/table"
         hx-target     "#users-table-container"
         table-params  (web-table/table-query->params table-query)
         filter-params (web-table/search-filters->params filters)
         qs-map        (merge table-params filter-params)
         hx-url        (str base-url "?" (web-table/encode-query-params qs-map))]
     (if (empty? users)
       [:div#users-table-container
        {:hx-get     hx-url
         :hx-trigger "userCreated from:body, userUpdated from:body, userDeleted from:body"
         :hx-target  hx-target}
        [:div.empty-state "No users found."]]
       [:div#users-table-container
        {:hx-get     hx-url
         :hx-trigger "userCreated from:body, userUpdated from:body, userDeleted from:body"
         :hx-target  hx-target}
        [:form#bulk-action-form {:hx-post   "/web/users/bulk"
                                 :hx-target "#users-table-container"
                                 :hx-swap   "outerHTML"
                                 :onchange  "const count = this.querySelectorAll('input[name=\"user-ids\"]:checked').length; document.getElementById('bulk-action-btn').disabled = !count;"}
          ;; Preserve current table state and filters when posting bulk actions
         (for [[k v] table-params]
           [:input {:type "hidden" :name k :value v}])
         (for [[k v] filter-params]
           [:input {:type "hidden" :name k :value v}])
          ;; Action selector for bulk operations (moved to header for better UX)
         [:input#bulk-action-input {:type "hidden" :name "action" :value "deactivate"}]
         [:div.table-wrapper
          [:table {:class "data-table" :id "users-table"}
           [:thead
            [:tr
             [:th ""]
             (table-ui/sortable-th {:label        "ID"
                                    :field        :id
                                    :current-sort sort
                                    :current-dir  dir
                                    :base-url     base-url
                                    :page         page
                                    :page-size    page-size
                                    :hx-target    hx-target
                                    :hx-push-url? true
                                    :extra-params filters})
             (table-ui/sortable-th {:label        "Name"
                                    :field        :name
                                    :current-sort sort
                                    :current-dir  dir
                                    :base-url     base-url
                                    :page         page
                                    :page-size    page-size
                                    :hx-target    hx-target
                                    :hx-push-url? true
                                    :extra-params filters})
             (table-ui/sortable-th {:label        "Email"
                                    :field        :email
                                    :current-sort sort
                                    :current-dir  dir
                                    :base-url     base-url
                                    :page         page
                                    :page-size    page-size
                                    :hx-target    hx-target
                                    :hx-push-url? true
                                    :extra-params filters})
             [:th "Role"]
             [:th ""]]]
           [:tbody
            (for [user users
                  :when (nil? (:deleted-at user))
                  :let [row       (user-row user)
                        row-attrs (meta row)]]
              [:tr row-attrs
               [:td
                [:input {:type    "checkbox"
                         :name    "user-ids"
                         :value   (str (:id user))
                         :onclick "event.stopPropagation();"}]]
               (for [cell row]
                 [:td cell])])]]
          (table-ui/pagination {:table-query  table-query
                                :total-count total-count
                                :base-url    base-url
                                :hx-target   hx-target
                                :extra-params filters})]]]))))

(defn users-table
  "Generate a table displaying users based on User schema.

   Arities:
   - ([users])                         ; basic table with default sorting, no query params in hx-get
   - ([users table-query total-count]) ; full control for paging/sorting
   - ([users table-query total-count filters]) ; with search filters

   Args (3-arity):
     users:       Collection of User entity maps
     table-query: TableQuery map (sorting/paging)
     total-count: Total number of users

   Returns:
     Hiccup structure for users table"
  ([users]
   ;; Backwards-compatible helper used mainly in unit tests.
   (let [table-query {:sort      :created-at
                      :dir       :desc
                      :page      1
                      :page-size 20
                      :offset    0
                      :limit     20}
         total-count (count users)
         fragment    (users-table-fragment users table-query total-count {})]
     (update fragment 1 assoc :hx-get "/web/users/table")))
  ([users table-query total-count]
   (users-table users table-query total-count {}))
  ([users table-query total-count filters]
   (users-table-fragment users table-query total-count filters)))

;; =============================================================================
;; Password Validation UI Components
;; =============================================================================

(defn password-requirement-item
  "Display a single password requirement with status indicator.
   
   Args:
     requirement - map with :met?, :message
     
   Returns:
     Hiccup list item with checkmark/x indicator
     
   Pure: true"
  [requirement]
  (let [icon (if (:met? requirement) "✓" "✗")
        class (if (:met? requirement) "requirement-met" "requirement-unmet")]
    [:li {:class class}
     [:span.requirement-icon icon]
     [:span.requirement-text (:message requirement)]]))

(defn password-requirements-list
  "Display list of password requirements with current status.
   
   Args:
     violations - vector of violation maps from meets-password-policy?
     policy     - password policy configuration map
     
   Returns:
     Hiccup list of requirements
     
   Pure: true"
  [violations policy]
  (let [violation-codes (set (map :code violations))
        requirements [{:code :too-short
                       :met? (not (contains? violation-codes :too-short))
                       :message (str "At least " (get policy :min-length 8) " characters")}
                      {:code :missing-uppercase
                       :met? (not (contains? violation-codes :missing-uppercase))
                       :message "One uppercase letter"
                       :required? (get policy :require-uppercase false)}
                      {:code :missing-lowercase
                       :met? (not (contains? violation-codes :missing-lowercase))
                       :message "One lowercase letter"
                       :required? (get policy :require-lowercase false)}
                      {:code :missing-number
                       :met? (not (contains? violation-codes :missing-number))
                       :message "One number"
                       :required? (get policy :require-numbers true)}
                      {:code :missing-special-char
                       :met? (not (contains? violation-codes :missing-special-char))
                       :message "One special character (!@#$%^&*...)"
                       :required? (get policy :require-special-chars false)}]
        active-requirements (filter #(not= false (:required? %)) requirements)]
    [:div.password-requirements
     [:h4 "Password Requirements"]
     [:ul.requirements-list
      (for [req active-requirements]
        (password-requirement-item req))]]))

(defn password-strength-indicator
  "Display password strength meter based on violation count.
   
   Args:
     violations - vector of violation maps
     
   Returns:
     Hiccup structure for strength meter
     
   Pure: true"
  [violations]
  (let [violation-count (count violations)
        strength (cond
                   (= violation-count 0) :strong
                   (<= violation-count 1) :medium
                   (<= violation-count 2) :weak
                   :else :very-weak)
        strength-label (case strength
                         :strong "Strong"
                         :medium "Medium"
                         :weak "Weak"
                         :very-weak "Very Weak")
        strength-class (name strength)]
    [:div.password-strength-indicator
     [:div.strength-meter {:class (str "strength-" strength-class)}
      [:div.strength-bar]]
     [:span.strength-label strength-label]]))

;; =============================================================================
;; User Form Components  
;; =============================================================================

(defn user-detail-form
  "Generate a form for viewing/editing user details based on User schema.
   
   Args:
     user: User entity map (from User schema)
     
   Returns:
     Hiccup structure for user detail form"
  [user]
  (let [active? (boolean (:active user))]
    [:div#user-detail
     [:h2 "User Details"]
     [:form {:hx-put    (str "/web/users/" (:id user))
             :hx-target "#user-detail"}
      (ui/form-field :name "Name"
                     (ui/text-input :name (:name user) {:required true})
                     nil)
      (ui/form-field :email "Email"
                     (ui/email-input :email (:email user) {:required true})
                     nil)
      (ui/form-field :role "Role"
                     (ui/select-field :role
                                      [[:admin "Admin"]
                                       [:user "User"]
                                       [:viewer "Viewer"]]
                                      (:role user))
                     nil)
      (ui/form-field :active "Active"
                     (ui/checkbox :active active?)
                     nil)
      [:div.form-actions
       (ui/submit-button "Update User" {:loading-text "Updating..."})
       ;; Show appropriate action button based on active status
       (if active?
         [:button.button.danger
          {:type "button"
           :style "margin-left: 10px;"
           :onclick (str "if(confirm('Are you sure you want to deactivate this user?')) {"
                         "fetch('/web/users/" (:id user) "', {method: 'DELETE', headers: {'HX-Request': 'true'}});"
                         "window.location.href='/web/users';"
                         "}")}
          "Deactivate User"]
         ;; Reactivate button for inactive users - uses the same update endpoint but sets active=true
         [:button.button.primary
          {:type "button"
           :style "margin-left: 10px;"
           :onclick (str "const form = this.closest('form');"
                         "const activeCheckbox = form.querySelector('input[name=active]');"
                         "activeCheckbox.checked = true;"
                         "form.requestSubmit();")}
          "Reactivate User"])]]]))

(defn create-user-form
  "Generate a form for creating new users based on CreateUserRequest schema.
   
   Args:
     data: Optional form data map for pre-filling
     errors: Optional validation errors map
     password-violations: Optional password violations from policy check
     policy: Optional password policy configuration
     
   Returns:
     Hiccup structure for create user form"
  ([data errors password-violations policy]
   [:div#create-user-form
    [:h2 "Create New User"]
    [:form {:hx-post   "/web/users"
            :hx-target "#create-user-form"}
     (ui/form-field :name "Name"
                    (ui/text-input :name (:name data) {:required true})
                    (:name errors))
     (ui/form-field :email "Email"
                    (ui/email-input :email (:email data) {:required true})
                    (:email errors))
     ;; Password field with validation feedback
     [:div {:class "form-field"}
      [:label {:for "password"} "Password"]
      (ui/password-input :password "" {:required true})
      ;; Show password requirements if policy provided
      (when policy
        (password-requirements-list (or password-violations []) policy))
      ;; Show validation errors if present
      (when (seq (:password errors))
        [:div.validation-errors
         (for [err (:password errors)]
           [:p err])])]
     (ui/form-field :role "Role"
                    (ui/select-field :role
                                     [[:user "User"]
                                      [:admin "Admin"]
                                      [:viewer "Viewer"]]
                                     (:role data))
                    (:role errors))
     (ui/form-field :send-welcome "Send Welcome Email"
                    (ui/checkbox :send-welcome (get data :send-welcome true))
                    nil)
     (ui/submit-button "Create User" {:loading-text "Creating..."})]])
  ([data errors]
   (create-user-form data errors nil {:min-length 8 :require-numbers true}))
  ([]
   (create-user-form {} {} nil {:min-length 8 :require-numbers true})))

;; =============================================================================
;; User Success Messages
;; =============================================================================

(defn user-created-success
  "Generate success message for user creation based on User schema.
   
   Args:
     user: Created User entity map
     
   Returns:
     Hiccup structure showing success message"
  [user]
  (let [active? (boolean (:active user))]
    (ui/success-message
     [:div
      [:h3 "User Created Successfully!"]
      [:p "Created user: " (:name user) " (" (:email user) ")"]
      [:div.user-details
       [:p "Role: " (str/upper-case (name (:role user)))]
       [:p "Status: " (if active? "Active" "Inactive")]]
      [:a.button {:href "/web/users"} "View All Users"]])))

(defn user-updated-success
  "Generate success message for user update based on User schema.
   
   Args:
     user: Updated User entity map
     
   Returns:
     Hiccup structure showing success message"
  [user]
  (ui/success-message
   [:div
    [:h3 "User Updated Successfully!"]
    [:p "Updated user: " (:name user) " (" (:email user) ")"]
    [:div.user-details
     [:p "Role: " (str/upper-case (name (:role user)))]]
    [:a.button {:href (str "/web/users/" (:id user))} "View User"]]))
(defn user-deleted-success
  "Generate success message for user deletion.
   
   Args:
     user-id: ID of deleted user
     
   Returns:
     Hiccup structure showing success message"
  [user-id]
  (ui/success-message
   [:div
    [:h3 "User Deleted Successfully!"]
    [:p "User " user-id " has been removed."]
    [:a.button {:href "/web/users"} "View All Users"]]))

;; =============================================================================
;; User-specific Validation Display
;; =============================================================================

(defn user-validation-errors
  "Generate validation error display for user forms based on User schema.
   
   Args:
     errors: Map of field -> error messages from user validation
     
   Returns:
     Hiccup structure for validation errors"
  [errors]
  (ui/validation-errors errors))

;; =============================================================================
;; User Page Templates
;; =============================================================================

(defn users-page
  "Complete users listing page.

   Arities:
   - ([users])                  ; infer total-count and no extra options
   - ([users opts])             ; infer total-count, with page options
   - ([users total-count opts]) ; full control

   Args (3-arity):
     users:       Collection of User entities
     total-count: Total number of users
     opts:        Optional page options (user context, flash messages, etc.)
                  May contain :table-query for sorting/paging and :filters.

   Returns:
     Complete HTML page for users listing"
  ([users]
   (users-page users (count users) {}))
  ([users opts]
   (users-page users (count users) opts))
  ([users total-count opts]
   (let [opts        (or opts {})
         table-query (or (:table-query opts)
                         {:sort      :created-at
                          :dir       :desc
                          :page      1
                          :page-size 20
                          :offset    0
                          :limit     20})
         filters     (or (:filters opts) {})]
     (layout/page-layout
      "Users"
      [:div.users-page
       [:div.page-header
        [:h1 "Users"]
        [:div.page-actions
            ;; Bulk action selector and button
         [:div.bulk-action-controls {:style "display: flex; gap: 0.5rem; align-items: center;"}
          [:select#bulk-action-select
           {:onchange "document.getElementById('bulk-action-input').value = this.value;"}
           [:option {:value "deactivate"} "Deactivate"]
           [:option {:value "activate"} "Activate"]
           [:option {:value "delete"} "Delete Permanently"]
           [:option {:value "role-admin"} "Change Role to Admin"]
           [:option {:value "role-user"} "Change Role to User"]]
          [:button#bulk-action-btn.icon-button.danger
           {:disabled true
            :title "Apply action to selected users"
            :onclick "const action = document.getElementById('bulk-action-select').value; const count = document.querySelectorAll('input[name=\"user-ids\"]:checked').length; if(confirm(`Are you sure you want to ${action} ${count} selected user(s)?`)) { document.getElementById('bulk-action-form').submit(); }"}
           "Apply to Selected"]]
           ;; Inline compact search/filter with overlay
         [:details#search-filter-details.search-filter-inline
          [:summary.search-toggle
           (icons/icon :search {:size 16})
           [:span {:style "margin-left: 0.5rem;"} "Filters"]]
          [:div.search-filter-overlay
           {:onclick "if(event.target === this) document.getElementById('search-filter-details').open = false;"}
           [:form.search-filter-form
            {:method "get"
             :action "/web/users"
             :onsubmit "setTimeout(() => document.getElementById('search-filter-details').open = false, 100);"}
            [:div.filter-header
             [:h3 "Search & Filter"]
             [:button.close-button
              {:type "button"
               :onclick "document.getElementById('search-filter-details').open = false;"
               :aria-label "Close"}
              "×"]]
            [:input {:type        "search"
                     :name        "q"
                     :placeholder "Search email..."
                     :value       (get filters :q "")}]
            [:select {:name "role"}
             [:option {:value "" :selected (nil? (:role filters))} "All roles"]
             [:option {:value "user" :selected (= "user" (:role filters))} "User"]
             [:option {:value "admin" :selected (= "admin" (:role filters))} "Admin"]
             [:option {:value "viewer" :selected (= "viewer" (:role filters))} "Viewer"]]
            [:div.filter-actions
             [:button.button.small {:type "submit"} "Apply"]
             [:a.button.small.secondary {:href "/web/users"} "Clear"]]]]]
         [:a.button.primary {:href "/web/users/new"} "Create User"]]]
       (users-table users table-query total-count filters)]
      opts))))

(defn user-detail-page
  "Complete user detail page.
   
   Args:
     user: User entity
     opts: Optional page options (user context, flash messages, etc.)
     
   Returns:
     Complete HTML page for user details"
  [user & [opts]]
  (let [current-user (:user opts)
        is-admin? (= :admin (:role current-user))]
    (layout/page-layout
     (str "User: " (:name user))
     [:div.user-detail-page
      [:div.page-header
       [:h1 (:name user)]
       [:div.page-actions
         ;; Admin-only hard delete button
        (when (and is-admin? (not (:active user)))
          [:form {:method "post"
                  :action (str "/web/users/" (:id user) "/hard-delete")
                  :style "display: inline-block; margin-right: 10px;"
                  :onsubmit "return confirm('⚠️  PERMANENT DELETE\\n\\nThis will IRREVERSIBLY delete this user and ALL related data.\\n\\nThis action cannot be undone.\\n\\nAre you absolutely sure?');"}
           [:button.button.danger {:type "submit"}
            (icons/icon :trash {:size 16})
            [:span {:style "margin-left: 0.5rem;"} "Permanently Delete"]]])
        [:a.button.secondary {:href (str "/web/users/" (:id user) "/sessions")} "View Sessions"]
        [:a.button {:href "/web/users"} "← Back to Users"]]]
      (user-detail-form user)]
     opts)))

(defn create-user-page
  "Complete create user page.
   
   Args:
     data: Optional form data for pre-filling
     errors: Optional validation errors 
     opts: Optional page options (user context, flash messages, etc.)
     
   Returns:
     Complete HTML page for creating users"
  [& [data errors opts]]
  (layout/page-layout
   "Create User"
   [:div.create-user-page
    [:div.page-header
     [:h1 "Create New User"]
     [:div.page-actions
      [:a.button {:href "/web/users"} "← Back to Users"]]]
    (create-user-form data errors)]
   opts))

;; =============================================================================
;; Login Form & Page
;; =============================================================================

(defn login-form
  "Login form for email/password sign-in.

   Args:
     data   - map with optional :email / :remember / :return-to
     errors - map of field keyword -> vector of error messages"
  [data errors]
  (let [err (fn [k]
              (when-let [es (seq (get errors k))]
                [:div.validation-errors
                 (for [e es]
                   [:p e])]))]
    [:div#login-form
     [:h2
      (icons/icon :log-in {:size 24})
      [:span {:style "margin-left: 0.5rem;"} "Sign in"]]
     [:form {:method "post"
             :action "/web/login"}
       ;; Hidden field to preserve return-to URL
      (when (:return-to data)
        [:input {:type "hidden" :name "return-to" :value (:return-to data)}])
      (ui/form-field :email "Email"
                     (ui/email-input :email (:email data) {:required true})
                     (:email errors))
      (ui/form-field :password "Password"
                     (ui/password-input :password "" {:required true})
                     (:password errors))
      (ui/form-field :remember "Remember me"
                     (ui/checkbox :remember (boolean (:remember data)))
                     nil)
      (ui/submit-button "Sign in" {:loading-text "Signing in..."})]]))

(defn login-page
  "Complete login page.

   Args:
     data   - form data map (may be empty)
     errors - validation errors (may be nil)
     opts   - page options (user context, flash messages, return-to URL, etc.)"
  [& [data errors opts]]
  (let [data-with-return (if (:return-to opts)
                           (assoc data :return-to (:return-to opts))
                           data)]
    (layout/page-layout
     "Sign in"
     [:div.login-page
      [:div.page-header
       [:h1 "Boundary"]
       [:p "Sign in to manage users"]]
      (login-form data-with-return errors)]
     opts)))

;; =============================================================================
;; Self-Service Registration Form & Page
;; =============================================================================

(defn register-form
  "Form for self-service account creation.

   Args:
     data   - map with optional :name / :email
     errors - map of field keyword -> vector of error messages
     password-violations - optional password violations from policy check
     policy - optional password policy configuration"
  ([data errors password-violations policy]
    [:div#register-form
     [:h2
      (icons/icon :user-plus {:size 24})
      [:span {:style "margin-left: 0.5rem;"} "Create Account"]]
     [:form {:method "post"
             :action "/web/register"}
      (ui/form-field :name "Name"
                     (ui/text-input :name (:name data) {:required true})
                     (:name errors))
      (ui/form-field :email "Email"
                     (ui/email-input :email (:email data) {:required true})
                     (:email errors))
      ;; Password field with validation feedback
      [:div {:class "form-field"}
       [:label {:for "password"} "Password"]
       (ui/password-input :password "" {:required true})
       ;; Show password requirements if policy provided
       (when policy
         (password-requirements-list (or password-violations []) policy))
       ;; Show validation errors if present
       (when (seq (:password errors))
         [:div.validation-errors
          (for [err (:password errors)]
            [:p err])])]
      (ui/submit-button "Create Account" {:loading-text "Creating..."})]])
  ([data errors]
   (register-form data errors nil {:min-length 8 :require-numbers true})))

(defn register-page
  "Complete self-service registration page.

   Args:
     data   - form data map (may be empty)
     errors - validation errors (may be nil)
     opts   - page options (user context, flash messages, etc.)"
  [& [data errors opts]]
  (layout/page-layout
   "Create Account"
   [:div.register-page
    [:div.page-header
     [:h1 "Create Account"]
     [:div.page-actions
      [:a.button {:href "/web/login"}
       (icons/icon :arrow-left {:size 16})
       [:span {:style "margin-left: 0.5rem;"} "Back to Login"]]]]
    (register-form data errors)]
   opts))

;; =============================================================================
;; Session Management UI
;; =============================================================================

(defn format-relative-time
  "Format a timestamp as relative time (e.g., '2 hours ago', 'just now').
   
   Args:
     timestamp - java.time.Instant or similar
     
   Returns:
     String describing time relative to now
     
   Pure: false (depends on current time)"
  [timestamp]
  (when timestamp
    (let [now (java.time.Instant/now)
          duration (java.time.Duration/between timestamp now)
          seconds (.getSeconds duration)
          minutes (quot seconds 60)
          hours (quot minutes 60)
          days (quot hours 24)]
      (cond
        (< seconds 60) "just now"
        (< minutes 60) (str minutes " minute" (when (> minutes 1) "s") " ago")
        (< hours 24) (str hours " hour" (when (> hours 1) "s") " ago")
        (< days 7) (str days " day" (when (> days 1) "s") " ago")
        :else (.format (java.time.format.DateTimeFormatter/ofPattern "MMM d, yyyy")
                       (.atZone timestamp (java.time.ZoneId/systemDefault)))))))

(defn parse-user-agent
  "Extract browser and device information from user-agent string.
   
   Args:
     user-agent - user-agent string
     
   Returns:
     String like 'Chrome on Desktop' or 'Safari on Mobile'
     
   Pure: true"
  [user-agent]
  (when user-agent
    (let [ua (str user-agent)
          browser (cond
                    (re-find #"Chrome" ua) "Chrome"
                    (re-find #"Safari" ua) "Safari"
                    (re-find #"Firefox" ua) "Firefox"
                    (re-find #"Edge" ua) "Edge"
                    :else "Unknown Browser")
          device (cond
                   (re-find #"Mobile|Android|iPhone" ua) "Mobile"
                   (re-find #"Tablet|iPad" ua) "Tablet"
                   :else "Desktop")]
      (str browser " on " device))))

(defn session-row
  "Single row in the sessions table.
   
   Args:
     session       - session map with :token, :ip-address, :user-agent, :created-at, :last-active
     current-token - the current session token (to mark current session)
     user-id       - user ID for action URLs
     
   Returns:
     Hiccup table row
     
   Pure: false (uses format-relative-time)"
  [session current-token user-id]
  (let [is-current? (= (:session-token session) current-token)]
    [:tr {:class (when is-current? "current-session")}
     [:td
      [:strong (parse-user-agent (:user-agent session))]
      (when is-current?
        [:span.badge.current " Current Session"])]
     [:td (or (:ip-address session) "Unknown")]
     [:td (format-relative-time (:last-accessed-at session))]
     [:td (format-relative-time (:created-at session))]
     [:td
      (when-not is-current?
        [:form {:method "post"
                :action (str "/web/sessions/" (:session-token session) "/revoke")
                :style "display: inline;"}
         [:input {:type "hidden" :name "user-id" :value user-id}]
         [:button.button.secondary.small
          {:type "submit"
           :onclick "return confirm('Revoke this session? The device will need to sign in again.');"}
          "Revoke"]])]]))

(defn sessions-list
  "Table displaying all user sessions.
   
   Args:
     sessions      - collection of session maps
     current-token - the current session token
     user-id       - user ID for action URLs
     
   Returns:
     Hiccup table
     
   Pure: false (delegates to session-row)"
  [sessions current-token user-id]
  (if (empty? sessions)
    [:p "No active sessions."]
    [:table
     [:thead
      [:tr
       [:th "Device / Browser"]
       [:th "IP Address"]
       [:th "Last Active"]
       [:th "Created"]
       [:th "Actions"]]]
     [:tbody
      (for [session sessions]
        (session-row session current-token user-id))]]))

(defn user-sessions-page
  "Complete page for managing user sessions.
   
   Args:
     user          - user map
     sessions      - collection of session maps
     current-token - the current session token
     opts          - page options (user context, flash messages, etc.)
     
   Returns:
     Complete page hiccup
     
   Pure: false"
  [user sessions current-token opts]
  (layout/page-layout
   (str "Sessions for " (:name user))
   [:div.user-sessions-page
    [:div.page-header
     [:h1 "Active Sessions"]
     [:p "Manage active sessions for " [:strong (:name user)]]
     [:div.page-actions
      [:a.button.secondary {:href (str "/web/users/" (:id user))} "\u2190 Back to User"]
      (when (> (count sessions) 1)
        [:form {:method "post"
                :action (str "/web/users/" (:id user) "/sessions/revoke-all")
                :style "display: inline; margin-left: 0.5rem;"}
         [:input {:type "hidden" :name "keep-current" :value "true"}]
         [:button.button.danger
          {:type "submit"
           :onclick "return confirm('Revoke all other sessions? This will log out all other devices.');"}
          "Revoke All Others"]])]]
    (sessions-list sessions current-token (:id user))]
   opts))

;; =============================================================================
;; Audit Log Components
;; =============================================================================

(defn action-badge
  "Display action type with appropriate styling.
   
   Args:
     action - keyword action type (:create, :update, :delete, etc.)
     
   Returns:
     Hiccup span with styled badge
     
   Pure: true"
  [action]
  (let [action-name (name action)
        action-class (case action
                       :create "success"
                       :update "info"
                       :delete "danger"
                       :deactivate "warning"
                       :activate "success"
                       :login "info"
                       :logout "secondary"
                       :role-change "warning"
                       :bulk-action "info"
                       "secondary")]
    [:span {:class (str "action-badge " action-class)}
     (str/capitalize action-name)]))

(defn result-badge
  "Display result status with appropriate styling.
   
   Args:
     result - keyword result (:success or :failure)
     
   Returns:
     Hiccup span with styled badge
     
   Pure: true"
  [result]
  (let [result-class (if (= result :success) "success" "danger")]
    [:span {:class (str "result-badge " result-class)}
     (str/capitalize (name result))]))

(defn format-audit-timestamp
  "Format timestamp for audit log display.
   
   Args:
     timestamp - java.time.Instant or similar
     
   Returns:
     Formatted string
     
   Pure: true"
  [timestamp]
  (when timestamp
    (str timestamp)))

(defn audit-log-row
  "Generate audit log table row.
   
   Args:
     audit-log - audit log entity map
     
   Returns:
     Hiccup table row
     
   Pure: true"
  [audit-log]
  (let [action (:action audit-log)
        result (:result audit-log)
        created-at (:created-at audit-log)]
    [:tr
     [:td (format-audit-timestamp created-at)]
     [:td (action-badge action)]
     [:td (or (:actor-email audit-log) [:em "System"])]
     [:td (or (:target-user-email audit-log) "-")]
     [:td (result-badge result)]
     [:td (or (:ip-address audit-log) "-")]
     [:td [:button.button.small.secondary
           {:type "button"
            :onclick (str "alert('Details:\\n"
                          "Action: " (name action) "\\n"
                          "Actor: " (or (:actor-email audit-log) "System") "\\n"
                          "Target: " (or (:target-user-email audit-log) "-") "\\n"
                          "IP: " (or (:ip-address audit-log) "-") "\\n"
                          "User Agent: " (or (:user-agent audit-log) "-") "\\n"
                          (when (:error-message audit-log)
                            (str "Error: " (:error-message audit-log) "\\n"))
                          "');")}
           "Details"]]]))

(defn audit-logs-table
  "Generate audit logs table.
   
   Args:
     audit-logs - collection of audit log entity maps
     table-query - normalized TableQuery
     total-count - total number of audit logs
     filters - optional map of parsed search filters
     
   Returns:
     Hiccup structure for audit logs table
     
   Pure: true"
  ([audit-logs table-query total-count]
   (audit-logs-table audit-logs table-query total-count {}))
  ([audit-logs table-query total-count filters]
   (let [{:keys [sort dir page page-size]} table-query
         base-url "/web/audit/table"
         hx-target "#audit-table-container"
         table-params (web-table/table-query->params table-query)
         filter-params (web-table/search-filters->params filters)
         qs-map (merge table-params filter-params)
         hx-url (str base-url "?" (web-table/encode-query-params qs-map))]
     (if (empty? audit-logs)
       [:div#audit-table-container
        {:hx-get hx-url
         :hx-trigger "auditRefresh from:body"
         :hx-target hx-target}
        [:div.empty-state "No audit logs found."]]
       [:div#audit-table-container
        {:hx-get hx-url
         :hx-trigger "auditRefresh from:body"
         :hx-target hx-target}
        [:div.table-wrapper
         [:table {:class "data-table" :id "audit-table"}
          [:thead
           [:tr
            (table-ui/sortable-th {:label "Timestamp"
                                   :field :created-at
                                   :current-sort sort
                                   :current-dir dir
                                   :base-url base-url
                                   :page page
                                   :page-size page-size
                                   :hx-target hx-target
                                   :hx-push-url? true
                                   :extra-params filters})
            [:th "Action"]
            [:th "Actor"]
            [:th "Target User"]
            [:th "Result"]
            [:th "IP Address"]
            [:th "Actions"]]]
          [:tbody
           (for [audit-log audit-logs]
             (audit-log-row audit-log))]]
         (table-ui/pagination
          {:table-query table-query
           :total-count total-count
           :base-url base-url
           :hx-target hx-target
           :extra-params filter-params})]]))))

(defn audit-filters
  "Generate filter form for audit logs.
   
   Args:
     filters - current filter values
     
   Returns:
     Hiccup form for filtering
     
   Pure: true"
  [filters]
  [:div.filters-panel
   [:h3 "Filters"]
   [:form {:hx-get "/web/audit/table"
           :hx-target "#audit-table-container"
           :hx-push-url "true"}
    [:div.filter-group
     [:label {:for "action"} "Action"]
     [:select {:name "action" :id "action"}
      [:option {:value ""} "All Actions"]
      [:option {:value "create" :selected (= "create" (:action filters))} "Create"]
      [:option {:value "update" :selected (= "update" (:action filters))} "Update"]
      [:option {:value "delete" :selected (= "delete" (:action filters))} "Delete"]
      [:option {:value "activate" :selected (= "activate" (:action filters))} "Activate"]
      [:option {:value "deactivate" :selected (= "deactivate" (:action filters))} "Deactivate"]
      [:option {:value "login" :selected (= "login" (:action filters))} "Login"]
      [:option {:value "logout" :selected (= "logout" (:action filters))} "Logout"]
      [:option {:value "role-change" :selected (= "role-change" (:action filters))} "Role Change"]
      [:option {:value "bulk-action" :selected (= "bulk-action" (:action filters))} "Bulk Action"]]]

    [:div.filter-group
     [:label {:for "result"} "Result"]
     [:select {:name "result" :id "result"}
      [:option {:value ""} "All Results"]
      [:option {:value "success" :selected (= "success" (:result filters))} "Success"]
      [:option {:value "failure" :selected (= "failure" (:result filters))} "Failure"]]]

    [:div.filter-group
     [:label {:for "target-email"} "Target User Email"]
     [:input {:type "text"
              :name "target-email"
              :id "target-email"
              :placeholder "user@example.com"
              :value (:target-email filters)}]]

    [:div.filter-group
     [:label {:for "actor-email"} "Actor Email"]
     [:input {:type "text"
              :name "actor-email"
              :id "actor-email"
              :placeholder "admin@example.com"
              :value (:actor-email filters)}]]

    [:div.form-actions
     [:button.button.primary {:type "submit"} "Apply Filters"]
     [:button.button.secondary
      {:type "button"
       :onclick "window.location.href='/web/audit'"}
      "Clear Filters"]]]])

(defn audit-page
  "Full audit logs page with layout.
   
   Args:
     audit-logs - collection of audit log entity maps
     table-query - normalized TableQuery
     total-count - total number of audit logs
     filters - optional map of parsed search filters
     opts - page options (user context, flash messages, etc.)
     
   Returns:
     Complete page hiccup
     
   Pure: false"
  ([audit-logs table-query total-count opts]
   (audit-page audit-logs table-query total-count {} opts))
  ([audit-logs table-query total-count filters opts]
   (layout/page-layout
    "Audit Logs"
    [:div.audit-page
     [:div.page-header
      [:h1 "Audit Trail"]
      [:p "Complete history of all user-related actions and system events"]]

     [:div.audit-content
      [:div.filters-sidebar
       (audit-filters filters)]

      [:div.audit-main
       (audit-logs-table audit-logs table-query total-count filters)]]]
    opts)))
