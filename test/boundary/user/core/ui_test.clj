(ns boundary.user.core.ui-test
  "Unit tests for user-specific UI components.
   
   Tests cover all user UI components including table rows, forms,
   success messages, and complete page compositions based on User schema."
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.user.core.ui :as ui]))

;; =============================================================================
;; Test Data
;; =============================================================================

(def sample-user
  {:id 123
   :name "John Doe"
   :email "john@example.com"
   :role :admin
   :active true})

(def inactive-user
  {:id 456
   :name "Jane Smith"
   :email "jane@example.com"
   :role :user
   :active false})

(def sample-users [sample-user inactive-user])

(def create-user-data
  {:name "New User"
   :email "newuser@example.com"
   :role :user})

(def validation-errors
  {:name ["Name is required"]
   :email ["Email format is invalid"]
   :password ["Password must be at least 6 characters"]})

;; =============================================================================
;; User Table Component Tests
;; =============================================================================

(deftest user-row-test
  (testing "generates correct table row for active user"
    (let [row (ui/user-row sample-user)]
      (is (vector? row))
      (is (= 6 (count row)))
      (is (= 123 (nth row 0)))
      (is (= "John Doe" (nth row 1)))
      (is (= "john@example.com" (nth row 2)))
      (is (= "ADMIN" (nth row 3)))
      (is (= "Active" (nth row 4)))
      ;; New structure: actions div with Edit button and Deactivate button
      (let [actions (nth row 5)]
        (is (= :div.actions (first actions)))
        (is (= [:a.button.small {:href "/web/users/123"} "Edit"] (second actions)))
        (is (= :button.button.small.danger (first (nth actions 2)))))))

  (testing "generates correct table row for inactive user"
    (let [row (ui/user-row inactive-user)]
      (is (= "USER" (nth row 3)))
      (is (= "Inactive" (nth row 4)))
      ;; New structure: actions div with Edit button and Deactivate button
      (let [actions (nth row 5)]
        (is (= :div.actions (first actions)))
        (is (= [:a.button.small {:href "/web/users/456"} "Edit"] (second actions))))))

  (testing "handles different role types"
    (let [viewer-user (assoc sample-user :role :viewer)
          row (ui/user-row viewer-user)]
      (is (= "VIEWER" (nth row 3))))))

(deftest users-table-test
  (testing "generates table with users wrapped in container"
    (let [table (ui/users-table sample-users)]
      (is (vector? table))
      ;; New structure: container div with HTMX attributes
      (is (= :div#users-table-container (first table)))
      (is (map? (second table)))
      (is (contains? (second table) :hx-get))
      (is (= "/web/users/table" (:hx-get (second table))))))

  (testing "shows empty state when no users"
    (let [table (ui/users-table [])]
      ;; New structure: container div with empty state inside
      (is (= :div#users-table-container (first table)))
      (is (some #(= [:div.empty-state "No users found."] %) table))))

  (testing "includes HTMX attributes for dynamic updates"
    (let [table (ui/users-table sample-users)
          attrs (second table)]
      (is (= "/web/users/table" (:hx-get attrs)))
      (is (= "#users-table-container" (:hx-target attrs)))
      (is (= "userCreated from:body, userUpdated from:body, userDeleted from:body" (:hx-trigger attrs))))))

;; =============================================================================
;; User Form Component Tests
;; =============================================================================

(deftest user-detail-form-test
  (testing "generates form with correct structure"
    (let [form (ui/user-detail-form sample-user)]
      (is (vector? form))
      ;; New structure: div with ID instead of class
      (is (= :div#user-detail (first form)))
      (is (= [:h2 "User Details"] (second form)))))

  (testing "includes HTMX form attributes"
    (let [form (ui/user-detail-form sample-user)
          form-element (nth form 2)]
      (is (= :form (first form-element)))
      (let [attrs (second form-element)]
        ;; New URLs: /web/users/*
        (is (= "/web/users/123" (:hx-put attrs)))
        (is (= "#user-detail" (:hx-target attrs))))))

  (testing "pre-fills form fields with user data"
    (let [form (ui/user-detail-form sample-user)
          form-str (str form)]
      (is (re-find #"John Doe" form-str))
      (is (re-find #"john@example.com" form-str))))

  (testing "includes all required form fields"
    (let [form (ui/user-detail-form sample-user)
          form-content (str form)]
      (is (re-find #"Name" form-content))
      (is (re-find #"Email" form-content))
      (is (re-find #"Role" form-content))
      (is (re-find #"Active" form-content)))))

(deftest create-user-form-test
  (testing "generates form with correct structure"
    (let [form (ui/create-user-form)]
      (is (vector? form))
      ;; New structure: div with ID instead of class
      (is (= :div#create-user-form (first form)))
      (is (= [:h2 "Create New User"] (second form)))))

  (testing "accepts data and errors parameters"
    (let [form (ui/create-user-form create-user-data validation-errors)]
      (is (vector? form))
      (is (= :div#create-user-form (first form)))))

  (testing "includes HTMX form attributes"
    (let [form (ui/create-user-form)
          form-element (nth form 2)]
      (is (= :form (first form-element)))
      (let [attrs (second form-element)]
        ;; New URLs: /web/users
        (is (= "/web/users" (:hx-post attrs)))
        (is (= "#create-user-form" (:hx-target attrs))))))

  (testing "no-arg version works"
    (let [form (ui/create-user-form)]
      (is (vector? form))
      (is (= :div#create-user-form (first form)))))

  (testing "includes password field (not in detail form)"
    (let [form (ui/create-user-form)
          form-content (str form)]
      (is (re-find #"Password" form-content)))))

;; =============================================================================
;; Success Message Component Tests  
;; =============================================================================

(deftest user-created-success-test
  (testing "generates success message with user details"
    (let [message (ui/user-created-success sample-user)]
      (is (vector? message))
      (is (= :div (first message)))
      (is (= "alert alert-success" (get-in message [1 :class])))
      (let [content (str message)]
        (is (re-find #"User Created Successfully!" content))
        (is (re-find #"John Doe" content))
        (is (re-find #"john@example.com" content))
        (is (re-find #"ADMIN" content))
        (is (re-find #"Active" content)))))

  (testing "includes navigation link"
    (let [message (ui/user-created-success sample-user)
          content (str message)]
      ;; New URLs: /web/users
      (is (re-find #"/web/users" content))
      (is (re-find #"View All Users" content)))))

(deftest user-updated-success-test
  (testing "generates success message with user details"
    (let [message (ui/user-updated-success sample-user)]
      (is (vector? message))
      (let [content (str message)]
        (is (re-find #"User Updated Successfully!" content))
        (is (re-find #"John Doe" content))
        (is (re-find #"john@example.com" content)))))

  (testing "includes user-specific navigation link"
    (let [message (ui/user-updated-success sample-user)
          content (str message)]
      ;; New URLs: /web/users/123
      (is (re-find #"/web/users/123" content))
      (is (re-find #"View User" content)))))

(deftest user-deleted-success-test
  (testing "generates success message with user ID"
    (let [message (ui/user-deleted-success 123)]
      (is (vector? message))
      (let [content (str message)]
        (is (re-find #"User Deleted Successfully!" content))
        (is (re-find #"User.*123" content))
        (is (re-find #"has been removed" content)))))

  (testing "includes navigation link to users list"
    (let [message (ui/user-deleted-success 123)
          content (str message)]
      ;; New URLs: /web/users
      (is (re-find #"/web/users" content))
      (is (re-find #"View All Users" content)))))

;; =============================================================================
;; Validation Error Component Tests
;; =============================================================================

(deftest user-validation-errors-test
  (testing "delegates to shared validation errors component"
    (let [errors-display (ui/user-validation-errors validation-errors)]
      (is (vector? errors-display))
      (is (some? errors-display))))

  (testing "returns nil for empty errors map"
    (let [errors-display (ui/user-validation-errors {})]
      (is (nil? errors-display)))))

;; =============================================================================
;; Page Template Component Tests
;; =============================================================================

(deftest users-page-test
  (testing "generates complete users page"
    (let [page (ui/users-page sample-users)]
      (is (vector? page))
      (let [content (str page)]
        (is (re-find #"Users" content))
        (is (re-find #"Create User" content))
        ;; New URLs: /web/users/new
        (is (re-find #"/web/users/new" content)))))

  (testing "accepts optional parameters"
    (let [opts {:flash {:success "User created"}}
          page (ui/users-page sample-users opts)]
      (is (vector? page))))

  (testing "includes users table"
    (let [page (ui/users-page sample-users)
          content (str page)]
      (is (re-find #"john@example.com" content))
      (is (re-find #"jane@example.com" content)))))

(deftest user-detail-page-test
  (testing "generates complete user detail page"
    (let [page (ui/user-detail-page sample-user)]
      (is (vector? page))
      (let [content (str page)]
        (is (re-find #"User: John Doe" content))
        (is (re-find #"John Doe" content))
        (is (re-find #"Back to Users" content)))))

  (testing "accepts optional parameters"
    (let [opts {:flash {:info "User updated"}}
          page (ui/user-detail-page sample-user opts)]
      (is (vector? page))))

  (testing "includes user detail form"
    (let [page (ui/user-detail-page sample-user)
          content (str page)]
      (is (re-find #"User Details" content))
      (is (re-find #"john@example.com" content)))))

(deftest create-user-page-test
  (testing "generates complete create user page with no params"
    (let [page (ui/create-user-page)]
      (is (vector? page))
      (let [content (str page)]
        (is (re-find #"Create User" content))
        (is (re-find #"Create New User" content))
        (is (re-find #"Back to Users" content)))))

  (testing "accepts data parameter"
    (let [page (ui/create-user-page create-user-data)]
      (is (vector? page))))

  (testing "accepts data and errors parameters"
    (let [page (ui/create-user-page create-user-data validation-errors)]
      (is (vector? page))))

  (testing "accepts all optional parameters"
    (let [opts {:flash {:error "Validation failed"}}
          page (ui/create-user-page create-user-data validation-errors opts)]
      (is (vector? page))))

  (testing "includes create user form"
    (let [page (ui/create-user-page)
          content (str page)]
      (is (re-find #"Create New User" content))
      (is (re-find #"Password" content)))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest ui-component-integration-test
  (testing "all components return valid Hiccup structures"
    (is (vector? (ui/user-row sample-user)))
    (is (vector? (ui/users-table sample-users)))
    (is (vector? (ui/user-detail-form sample-user)))
    (is (vector? (ui/create-user-form)))
    (is (vector? (ui/user-created-success sample-user)))
    (is (vector? (ui/user-updated-success sample-user)))
    (is (vector? (ui/user-deleted-success 123)))
    (is (vector? (ui/user-validation-errors validation-errors)))
    (is (vector? (ui/users-page sample-users)))
    (is (vector? (ui/user-detail-page sample-user)))
    (is (vector? (ui/create-user-page))))

  (testing "components handle edge cases gracefully"
    (is (vector? (ui/user-row (assoc sample-user :name nil))))
    (is (vector? (ui/users-table [])))
    (is (vector? (ui/create-user-form {} {})))
    (is (nil? (ui/user-validation-errors {})))))