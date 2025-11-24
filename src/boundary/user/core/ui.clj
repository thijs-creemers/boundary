(ns boundary.user.core.ui
  "User-specific UI components based on User schema.
   
   This namespace contains pure functions for generating user-related Hiccup
   structures. Components are derived from the User schema and handle
   user-specific business logic for display and forms."
  (:require [boundary.shared.ui.core.components :as ui]
            [boundary.shared.ui.core.layout :as layout]
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
  [(:id user)
   (:name user)
   (:email user)
   (str/upper-case (name (:role user)))
   (if (:active user) "Active" "Inactive")
   [:div.actions
    [:a.button.small {:href (str "/web/users/" (:id user))} "Edit"]
    [:button.button.small.danger
     {:hx-delete  (str "/web/users/" (:id user))
      :hx-confirm "Deactivate this user?"
      :hx-target  "#users-table-container"}
     "Deactivate"]]])

(defn users-table-fragment
  "Generate just the users table container fragment (for HTMX refresh).
   
   Args:
     users: Collection of User entity maps
     
   Returns:
     Hiccup structure for users table container (no page layout)"
  [users]
  [:div#users-table-container
   {:hx-get     "/web/users/table"
    :hx-trigger "userCreated from:body, userUpdated from:body, userDeleted from:body"
    :hx-target  "#users-table-container"}
   (if (empty? users)
     [:div.empty-state "No users found."]
     (let [headers ["ID" "Name" "Email" "Role" "Status" "Actions"]
           rows    (map user-row users)]
       (ui/data-table headers rows {:id "users-table"})))])

(defn users-table
  "Generate a table displaying users based on User schema.
   
   Args:
     users: Collection of User entity maps
     
   Returns:
     Hiccup structure for users table"
  [users]
  (users-table-fragment users))

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
      (ui/checkbox :active (:active user))
      nil)
    (ui/submit-button "Update User" {:loading-text "Updating..."})]])

(defn create-user-form
  "Generate a form for creating new users based on CreateUserRequest schema.
   
   Args:
     data: Optional form data map for pre-filling
     errors: Optional validation errors map
     
   Returns:
     Hiccup structure for create user form"
  ([data errors]
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
     (ui/form-field :password "Password"
       (ui/password-input :password "" {:required true})
       (:password errors))
     (ui/form-field :role "Role"
       (ui/select-field :role
         [[:user "User"]
          [:admin "Admin"]
          [:viewer "Viewer"]]
         (:role data))
       (:role errors))
     (ui/submit-button "Create User" {:loading-text "Creating..."})]])
  ([]
   (create-user-form {} {})))


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
  (ui/success-message
    [:div
     [:h3 "User Created Successfully!"]
     [:p "Created user: " (:name user) " (" (:email user) ")"]
     [:div.user-details
      [:p "Role: " (str/upper-case (name (:role user)))]
      [:p "Status: " (if (:active user) "Active" "Inactive")]]
     [:a.button {:href "/web/users"} "View All Users"]]))

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
      [:p "Role: " (str/upper-case (name (:role user)))]
      [:p "Status: " (if (:active user) "Active" "Inactive")]]
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
   
   Args:
     users: Collection of User entities
     opts: Optional page options (user context, flash messages, etc.)
     
   Returns:
     Complete HTML page for users listing"
  [users & [opts]]
  (layout/page-layout
    "Users"
    [:div.users-page
     [:div.page-header
      [:h1 "Users"]
      [:div.page-actions
       [:a.button.primary {:href "/web/users/new"} "Create User"]]]
     (users-table users)]
    ;; Ensure user-specific CSS is loaded in addition to global defaults
    (update opts :css (fnil into []) ["/modules/user/css/user.css"])))

(defn user-detail-page
  "Complete user detail page.
   
   Args:
     user: User entity
     opts: Optional page options (user context, flash messages, etc.)
     
   Returns:
     Complete HTML page for user details"
  [user & [opts]]
  (layout/page-layout
    (str "User: " (:name user))
    [:div.user-detail-page
     [:div.page-header
      [:h1 (:name user)]
      [:div.page-actions
       [:a.button {:href "/web/users"} "Back to Users"]]]
     (user-detail-form user)]
    opts))

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
       [:a.button {:href "/web/users"} "Back to Users"]]]
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
     [:h2 "Sign in"]
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
     errors - map of field keyword -> vector of error messages"
  [data errors]
  (let [err (fn [k]
              (when-let [es (seq (get errors k))]
                [:div.validation-errors
                 (for [e es]
                   [:p e])]))]
    [:div#register-form
     [:h2 "Create Account"]
     [:form {:method "post"
             :action "/web/register"}
      (ui/form-field :name "Name"
        (ui/text-input :name (:name data) {:required true})
        (:name errors))
      (ui/form-field :email "Email"
        (ui/email-input :email (:email data) {:required true})
        (:email errors))
      (ui/form-field :password "Password"
        (ui/password-input :password "" {:required true})
        (:password errors))
      (ui/submit-button "Create Account" {:loading-text "Creating..."})]]))

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
       [:a.button {:href "/web/login"} "\u2190 Back to Login"]]]
     (register-form data errors)]
    opts))
