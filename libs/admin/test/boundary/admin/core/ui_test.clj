(ns boundary.admin.core.ui-test
  "Unit tests for admin UI pure functions.

   Tests cover:
   - Layout components (admin-sidebar, admin-shell, admin-layout, admin-home)
   - Entity list components (search, table, pagination)
   - Field rendering (render-field-value with different types)
   - Form widgets (render-field-widget with all widget types)
   - Entity forms (create/edit forms)
   - URL construction (build-table-url)
   - Error pages (forbidden, not-found)
   - Utility functions (format-field-label, get-field-errors)
   
   All tests are pure function tests - data in, Hiccup out.
   No browser or HTTP simulation required."
  (:require [boundary.admin.core.ui :as ui]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str])
  (:import [java.time Instant]
           [java.util UUID]))

^{:kaocha.testable/meta {:unit true :admin true}}

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def sample-user
  "Sample user for testing"
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "test@example.com"
   :display-name "Test User"
   :role :admin
   :active true})

(def sample-entity-config
  "Sample entity configuration"
  {:label "Users"
   :description "User management"
   :icon :users
   :primary-key :id
   :list-fields [:email :name :active]
   :editable-fields [:email :name :active]
   :search-fields [:email :name]
   :hide-fields #{:password-hash}
   :readonly-fields #{:id :created-at}
   :fields {:id {:type :uuid :label "ID"}
            :email {:type :string :label "Email" :required true}
            :name {:type :string :label "Name" :required true}
            :active {:type :boolean :label "Active"}
            :created-at {:type :instant :label "Created At"}
            :password-hash {:type :string :widget :password-input}}})

(def sample-entity-configs
  "Sample entity configs map"
  {:users sample-entity-config
   :orders {:label "Orders" :icon :shopping-cart}
   :products {:label "Products" :icon :box}})

(def sample-record
  "Sample entity record"
  {:id #uuid "00000000-0000-0000-0000-000000000002"
   :email "user@example.com"
   :name "Sample User"
   :active true
   :created-at (Instant/parse "2026-01-09T12:00:00Z")
   :password-hash "hashed123"})

(def sample-permissions
  "Sample admin permissions"
  {:can-view true
   :can-create true
   :can-edit true
   :can-delete true
   :can-bulk-delete true})

;; =============================================================================
;; Utility Function Tests
;; =============================================================================

(deftest format-field-label-test
  (testing "Format field names as human-readable labels"
    (is (= "Email" (ui/format-field-label :email)))
    (is (= "Created at" (ui/format-field-label :created-at)))
    (is (= "Password hash" (ui/format-field-label :password-hash)))
    (is (= "User id" (ui/format-field-label :user_id)))
    (is (= "First name" (ui/format-field-label :first-name)))
    (is (= "Id" (ui/format-field-label :id)))))

(deftest get-field-errors-test
  (testing "Extract errors for specific field from validation result"
    (testing "Errors as map (field -> vector of messages)"
      (let [errors {:email ["Required field" "Invalid format"]
                    :name ["Too short"]}]
        (is (= ["Required field" "Invalid format"]
               (ui/get-field-errors errors :email)))
        (is (= ["Too short"]
               (ui/get-field-errors errors :name)))
        (is (= []
               (ui/get-field-errors errors :active)))))

    (testing "Errors as vector of maps with :field key"
      (let [errors [{:field :email :message "Required"}
                    {:field :email :message "Invalid"}
                    {:field :name :message "Too short"}]]
        (is (= [{:field :email :message "Required"}
                {:field :email :message "Invalid"}]
               (ui/get-field-errors errors :email)))
        (is (= [{:field :name :message "Too short"}]
               (ui/get-field-errors errors :name)))
        (is (= []
               (ui/get-field-errors errors :active)))))

    (testing "Empty or nil errors"
      (is (= [] (ui/get-field-errors {} :email)))
      (is (= [] (ui/get-field-errors [] :email)))
      (is (= [] (ui/get-field-errors nil :email))))))

(deftest build-table-url-test
  (testing "Build table URL with query parameters"
    (testing "Base URL with no parameters"
      (let [url (ui/build-table-url :users {})]
        (is (= "/web/admin/users/table" url))))

    (testing "With pagination parameters"
      (let [url (ui/build-table-url :users {:page 2 :page-size 50})]
        (is (str/includes? url "/web/admin/users/table?"))
        (is (str/includes? url "page=2"))
        (is (str/includes? url "page-size=50"))))

    (testing "With sorting parameters"
      (let [url (ui/build-table-url :users {:sort "email" :dir "asc"})]
        (is (str/includes? url "/web/admin/users/table?"))
        (is (str/includes? url "sort=email"))
        (is (str/includes? url "dir=asc"))))

    (testing "With search parameter"
      (let [url (ui/build-table-url :users {:search "test"})]
        (is (str/includes? url "/web/admin/users/table?"))
        (is (str/includes? url "search=test"))))

    (testing "With multiple parameters"
      (let [url (ui/build-table-url :users {:page 1
                                            :page-size 20
                                            :sort "name"
                                            :dir "desc"
                                            :search "admin"})]
        (is (str/starts-with? url "/web/admin/users/table?"))
        (is (str/includes? url "page=1"))
        (is (str/includes? url "page-size=20"))))))

;; =============================================================================
;; Field Rendering Tests
;; =============================================================================

(deftest render-field-value-test
  (testing "Render field values for display in tables/detail views"
    (testing "Nil values"
      (let [result (ui/render-field-value :email nil {:type :string})]
        (is (vector? result))
        (is (= :span.null-value (first result)))
        (is (= "—" (last result)))))

    (testing "Boolean values"
      (let [true-result (ui/render-field-value :active true {:type :boolean})
            false-result (ui/render-field-value :active false {:type :boolean})]
        (is (vector? true-result))
        (is (= :span (first true-result)))
        (is (str/includes? (str true-result) "Yes"))
        (is (vector? false-result))
        (is (str/includes? (str false-result) "No"))))

    (testing "Instant/datetime values"
      (let [instant (Instant/parse "2026-01-09T12:00:00Z")
            result (ui/render-field-value :created-at instant {:type :instant})]
        (is (string? result))
        (is (str/includes? result "2026-01-09"))))

    (testing "Date values"
      (let [result (ui/render-field-value :birth-date "2026-01-09" {:type :date})]
        (is (string? result))
        (is (= "2026-01-09" result))))

    (testing "UUID values"
      (let [uuid (UUID/randomUUID)
            result (ui/render-field-value :id uuid {:type :uuid})]
        (is (vector? result))
        (is (= :span.uuid-value (first result)))
        (is (str/includes? (str result) (str uuid)))))

    (testing "Enum values"
      (let [result (ui/render-field-value :status :active {:type :enum})]
        (is (vector? result))
        (is (= :span.enum-badge (first result)))
        (is (= "Active" (last result)))))

    (testing "JSON values"
      (let [result (ui/render-field-value :metadata {:key "value"} {:type :json})]
        (is (vector? result))
        (is (= :code (first result)))))

    (testing "String values"
      (testing "Short strings"
        (let [result (ui/render-field-value :name "John Doe" {:type :string})]
          (is (= "John Doe" result))))

      (testing "Long strings get truncated"
        (let [long-string (apply str (repeat 60 "a"))
              result (ui/render-field-value :description long-string {:type :string})]
          (is (string? result))
          (is (< (count result) (count long-string)))
          (is (str/ends-with? result "...")))))

    (testing "Other values (numbers, etc.)"
      (is (= "42" (ui/render-field-value :count 42 {:type :integer})))
      (is (= "19.99" (ui/render-field-value :price 19.99 {:type :decimal}))))))

;; =============================================================================
;; Form Widget Tests
;; =============================================================================

(deftest render-field-widget-test
  (testing "Render form input widgets based on field configuration"
    (testing "Text input widget"
      (let [widget (ui/render-field-widget :name "John"
                                           {:widget :text-input
                                            :label "Name"
                                            :required true}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))
        ;; Check for label
        (is (some #(and (vector? %) (= :label (first %))) widget))
        ;; Check for required indicator
        (is (str/includes? (str widget) "*"))))

    (testing "Email input widget"
      (let [widget (ui/render-field-widget :email "test@example.com"
                                           {:widget :email-input
                                            :label "Email"}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Password input widget"
      (let [widget (ui/render-field-widget :password nil
                                           {:widget :password-input
                                            :label "Password"
                                            :required true}
                                           nil)]
        (is (vector? widget))
        (is (str/includes? (str widget) "Password"))))

    (testing "Checkbox widget"
      (let [widget (ui/render-field-widget :active true
                                           {:widget :checkbox
                                            :label "Active"
                                            :help-text "Enable user account"}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))
        ;; Should have help text
        (is (str/includes? (str widget) "Enable user account"))))

    (testing "Select widget"
      (let [widget (ui/render-field-widget :role :admin
                                           {:widget :select
                                            :label "Role"
                                            :options [[:admin "Admin"] [:user "User"]]}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Textarea widget"
      (let [widget (ui/render-field-widget :description "Long text..."
                                           {:widget :textarea
                                            :label "Description"
                                            :rows 8}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Number input widget"
      (let [widget (ui/render-field-widget :age 25
                                           {:widget :number-input
                                            :label "Age"
                                            :min 0
                                            :max 120}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Date input widget"
      (let [widget (ui/render-field-widget :birth-date "2000-01-01"
                                           {:widget :date-input
                                            :label "Birth Date"}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Datetime input widget"
      (let [widget (ui/render-field-widget :created-at "2026-01-09T12:00"
                                           {:widget :datetime-input
                                            :label "Created At"}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Hidden widget"
      (let [widget (ui/render-field-widget :id "123"
                                           {:widget :hidden}
                                           nil)]
        (is (vector? widget))
        ;; Hidden field should still be in form-field wrapper
        (is (= :div.form-field (first widget)))))

    (testing "Widget with validation errors"
      (let [widget (ui/render-field-widget :email ""
                                           {:widget :email-input
                                            :label "Email"
                                            :required true}
                                           ["Required field" "Invalid format"])]
        (is (vector? widget))
        ;; Should have has-errors class
        (is (some #(and (map? %) (contains? % :class)) (tree-seq coll? seq widget)))
        ;; Should display errors
        (is (str/includes? (str widget) "Required field"))
        (is (str/includes? (str widget) "Invalid format"))))

    (testing "Readonly field"
      (let [widget (ui/render-field-widget :id "123"
                                           {:widget :text-input
                                            :label "ID"
                                            :readonly true}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))))

;; =============================================================================
;; Layout Component Tests
;; =============================================================================

(deftest admin-sidebar-test
  (testing "Admin sidebar with entity navigation"
    (let [entities [:users :orders :products]
          sidebar (ui/admin-sidebar entities sample-entity-configs :users)]

      (is (vector? sidebar))
      (is (= :aside.admin-sidebar (first sidebar)))

      ;; Should have header with brand logo (alt text is "Boundary")
      (is (str/includes? (str sidebar) "Boundary"))

      ;; Should have navigation for each entity
      (is (str/includes? (str sidebar) "Users"))
      (is (str/includes? (str sidebar) "Orders"))
      (is (str/includes? (str sidebar) "Products"))

      ;; Should have links to entity pages
      (is (str/includes? (str sidebar) "/web/admin/users"))
      (is (str/includes? (str sidebar) "/web/admin/orders"))

      ;; Current entity should have active class
      (is (str/includes? (str sidebar) "active")))))

(deftest admin-shell-test
  (testing "Admin shell layout with sidebar and topbar"
    (let [content [:div "Main content"]
          opts {:user sample-user
                :current-entity :users
                :entities [:users :orders]
                :entity-configs sample-entity-configs
                :page-title "Users"}
          shell (ui/admin-shell content opts)]

      (is (vector? shell))
      ;; Shell now returns a wrapper div [:div store-init [:div.admin-shell ...]]
      ;; for Alpine.js sidebar store initialization (using div instead of fragment for Hiccup compatibility)
      (is (= :div (first shell)))

      ;; Should contain admin-shell div
      (is (some #(and (vector? %) (= :div.admin-shell (first %)))
                (tree-seq vector? seq shell)))

      ;; Should contain sidebar
      (is (some #(and (vector? %) (= :aside.admin-sidebar (first %)))
                (tree-seq vector? seq shell)))

      ;; Should have topbar with user info
      (is (str/includes? (str shell) "Welcome"))
      (is (str/includes? (str shell) "Test User"))

      ;; Should have main content area
      (is (str/includes? (str shell) "Main content"))

      ;; Should have page title
      (is (str/includes? (str shell) "Users"))

      ;; Should have Alpine.js store initialization script
      (is (some #(and (vector? %) (= :script (first %)))
                (tree-seq vector? seq shell))))))

(deftest admin-layout-test
  (testing "Complete admin page layout"
    (let [content [:div "Page content"]
          opts {:user sample-user
                :current-entity :users
                :entities [:users :orders]
                :entity-configs sample-entity-configs
                :flash {:success "Operation successful"}}
          layout (ui/admin-layout content opts)]

      (is (vector? layout))

      ;; Should be a complete page with HTML structure
      ;; The layout/page-layout function wraps everything
      (is (str/includes? (str layout) "Page content")))))

(deftest admin-home-test
  (testing "Admin dashboard home page"
    (let [entities [:users :orders :products]
          stats {:users {:count 42}
                 :orders {:count 156}
                 :products {:count 89}}
          home (ui/admin-home entities sample-entity-configs stats)]

      (is (vector? home))
      (is (= :div.admin-home (first home)))

      ;; Should have dashboard title
      (is (str/includes? (str home) "Admin Dashboard"))

      ;; Should have entity cards
      (is (str/includes? (str home) "Users"))
      (is (str/includes? (str home) "Orders"))
      (is (str/includes? (str home) "Products"))

      ;; Should show record counts
      (is (str/includes? (str home) "42 records"))
      (is (str/includes? (str home) "156 records"))
      (is (str/includes? (str home) "89 records"))

      ;; Entity cards should link to entity pages
      (is (str/includes? (str home) "/web/admin/users"))
      (is (str/includes? (str home) "/web/admin/orders")))))

;; =============================================================================
;; Entity List Component Tests
;; =============================================================================

(deftest entity-search-form-test
  (testing "Entity search form for filtering"
    (testing "Entity with search fields"
      (let [form (ui/entity-search-form :users sample-entity-config "test" nil)]
        (is (vector? form))
        (is (= :div.entity-search-form (first form)))

        ;; Should have search input
        (is (str/includes? (str form) "Search"))

        ;; Should have current search value
        (is (str/includes? (str form) "test"))

        ;; Should have HTMX attributes
        (is (str/includes? (str form) "hx-get"))
        (is (str/includes? (str form) "/web/admin/users/table"))))

    (testing "Entity without search fields"
      (let [config (dissoc sample-entity-config :search-fields)
            form (ui/entity-search-form :users config nil nil)]
        ;; Should return nil when no search fields
        (is (nil? form))))))

(deftest entity-table-row-test
  (testing "Entity table row generation"
    (let [row (ui/entity-table-row :users sample-record sample-entity-config sample-permissions)]

      (is (vector? row))
      (is (= :tr (first row)))

      ;; Should have checkbox for bulk selection
      (is (str/includes? (str row) "checkbox"))

      ;; Should display list-fields values
      (is (str/includes? (str row) "user@example.com"))
      (is (str/includes? (str row) "Sample User"))

      ;; Should have action buttons (edit)
      (is (str/includes? (str row) "/web/admin/users/"))

      ;; Should NOT display hidden fields (password-hash)
      (is (not (str/includes? (str row) "hashed123"))))))

(deftest entity-table-test
  (testing "Complete entity table with records"
    (let [records [sample-record]
          table-query {:page 1 :page-size 20 :sort :email :dir :asc}
          total-count 1
          table (ui/entity-table :users records sample-entity-config
                                 table-query total-count sample-permissions nil)]

      (is (vector? table))
      (is (= :div#entity-table-container (first table)))

      ;; Should have table element
      (is (some #(and (vector? %) (= :table.data-table (first %)))
                (tree-seq vector? seq table)))

      ;; Should have column headers for list-fields
      (is (str/includes? (str table) "Email"))
      (is (str/includes? (str table) "Name"))
      (is (str/includes? (str table) "Active"))

      ;; Should have record data
      (is (str/includes? (str table) "user@example.com"))

      ;; Should have HTMX attributes for dynamic updates
      (is (str/includes? (str table) "hx-trigger"))
      (is (str/includes? (str table) "entityCreated")))

    (testing "Empty table state"
      (let [table (ui/entity-table :users [] sample-entity-config
                                   {:page 1 :page-size 20} 0
                                   sample-permissions nil)]
        (is (vector? table))
        ;; Should show empty state message
        (is (str/includes? (str table) "No records found"))

        ;; Should show create button if can-create
        (is (str/includes? (str table) "Create First Record"))))))

(deftest entity-list-page-test
  (testing "Complete entity list page"
    (let [records [sample-record]
          table-query {:page 1 :page-size 20}
          total-count 1
          opts {:search "test" :filters {} :flash {:success "Saved successfully"}}
          page (ui/entity-list-page :users records sample-entity-config
                                    table-query total-count sample-permissions opts)]

      (is (vector? page))
      (is (= :div.entity-list-page (first page)))

      ;; Should show flash message
      (is (str/includes? (str page) "Saved successfully"))

      ;; Should have toolbar with search and create button
      (is (str/includes? (str page) "search-input"))
      (is (str/includes? (str page) "/web/admin/users/new"))

      ;; Should show record count
      (is (str/includes? (str page) "1 Users"))

      ;; Should have the table
      (is (str/includes? (str page) "user@example.com")))))

;; =============================================================================
;; Entity Form Tests
;; =============================================================================

(deftest entity-form-test
  (testing "Entity create/edit form generation"
    (testing "Create form (new record)"
      (let [form (ui/entity-form :users sample-entity-config nil nil sample-permissions)]
        (is (vector? form))
        (is (= :form.entity-form (first form)))

        ;; Should have HTMX post for create
        (is (str/includes? (str form) "hx-post"))
        (is (str/includes? (str form) "/web/admin/users"))

        ;; Should have editable fields
        (is (str/includes? (str form) "Email"))
        (is (str/includes? (str form) "Name"))

        ;; Should have submit button with "Create"
        (is (str/includes? (str form) "Create"))

        ;; Should have cancel link
        (is (str/includes? (str form) "Cancel"))))

    (testing "Edit form (existing record)"
      (let [form (ui/entity-form :users sample-entity-config sample-record nil sample-permissions)]
        (is (vector? form))

        ;; Should have HTMX put for update
        (is (str/includes? (str form) "hx-put"))

        ;; Should have current values
        (is (str/includes? (str form) "user@example.com"))
        (is (str/includes? (str form) "Sample User"))

        ;; Should have submit button with "Update"
        (is (str/includes? (str form) "Update"))))

    (testing "Form with validation errors"
      (let [errors {:email ["Required field"] :name ["Too short"]}
            form (ui/entity-form :users sample-entity-config nil errors sample-permissions)]
        (is (vector? form))

        ;; Should display validation errors
        (is (str/includes? (str form) "Required field"))
        (is (str/includes? (str form) "Too short"))))))

(deftest entity-detail-page-test
  (testing "Entity detail/edit page"
    (testing "Edit existing record"
      (let [page (ui/entity-detail-page :users sample-entity-config sample-record
                                        nil sample-permissions nil)]
        (is (vector? page))
        (is (= :div.entity-detail-page (first page)))

        ;; Should have breadcrumbs
        (is (str/includes? (str page) "Admin"))
        (is (str/includes? (str page) "Users"))

        ;; Should have page title
        (is (str/includes? (str page) "Edit Users"))

        ;; Should contain the form
        (is (str/includes? (str page) "user@example.com"))))

    (testing "Create new record"
      (let [page (ui/entity-detail-page :users sample-entity-config nil
                                        nil sample-permissions nil)]
        (is (vector? page))

        ;; Should have "Create" in title
        (is (str/includes? (str page) "Create Users"))))))

(deftest entity-new-page-test
  (testing "Entity creation page (convenience wrapper)"
    (let [page (ui/entity-new-page :users sample-entity-config nil sample-permissions nil)]
      (is (vector? page))
      (is (= :div.entity-detail-page (first page)))

      ;; Should be a create page
      (is (str/includes? (str page) "Create Users")))))

;; =============================================================================
;; Dialog Component Tests
;; =============================================================================

(deftest confirm-delete-dialog-test
  (testing "Confirmation dialog for delete operations"
    (let [dialog (ui/confirm-delete-dialog :users "123")]
      (is (vector? dialog))
      (is (= :div.modal#confirm-delete-modal (first dialog)))

      ;; Should have warning message
      (is (str/includes? (str dialog) "Confirm Delete"))
      (is (str/includes? (str dialog) "cannot be undone"))

      ;; Should have HTMX delete button
      (is (str/includes? (str dialog) "hx-delete"))
      (is (str/includes? (str dialog) "/web/admin/users/123"))

      ;; Should have cancel button
      (is (str/includes? (str dialog) "Cancel")))))

;; =============================================================================
;; Error Page Tests
;; =============================================================================

(deftest admin-forbidden-page-test
  (testing "403 Forbidden error page"
    (let [page (ui/admin-forbidden-page "You must be an admin" sample-user)]
      (is (vector? page))

      ;; Should show 403 status
      (is (str/includes? (str page) "403"))
      (is (str/includes? (str page) "Access Denied"))

      ;; Should show reason
      (is (str/includes? (str page) "You must be an admin"))

      ;; Should have link to dashboard (user is logged in)
      (is (str/includes? (str page) "Dashboard")))

    (testing "Forbidden page for unauthenticated user"
      (let [page (ui/admin-forbidden-page "Not authenticated" nil)]
        (is (vector? page))

        ;; Should have link to login
        (is (str/includes? (str page) "Login"))))))

(deftest admin-not-found-page-test
  (testing "404 Not Found error page for admin entities"
    (let [page (ui/admin-not-found-page :users sample-user)]
      (is (vector? page))

      ;; Should show 404 status
      (is (str/includes? (str page) "404"))
      (is (str/includes? (str page) "Not Found"))

      ;; Should mention the entity
      (is (str/includes? (str page) "users"))

      ;; Should have link back to admin
      (is (str/includes? (str page) "Back to Admin")))))

;; =============================================================================
;; HTMX Integration Tests
;; =============================================================================

(deftest htmx-attributes-test
  (testing "HTMX attributes are correctly generated"
    (testing "Table has HTMX trigger for entity events"
      (let [table (ui/entity-table :users [sample-record] sample-entity-config
                                   {:page 1 :page-size 20} 1 sample-permissions nil)]
        (is (str/includes? (str table) "hx-get"))
        (is (str/includes? (str table) "hx-trigger"))
        (is (str/includes? (str table) "entityCreated"))))

    (testing "Form has HTMX submit"
      (let [form (ui/entity-form :users sample-entity-config nil nil sample-permissions)]
        (is (str/includes? (str form) "hx-post"))
        (is (str/includes? (str form) "hx-swap"))))

    (testing "Search input has HTMX change trigger"
      (let [search (ui/entity-search-form :users sample-entity-config nil nil)]
        (when search
          (is (str/includes? (str search) "hx-get"))
          (is (str/includes? (str search) "hx-trigger")))))))

;; =============================================================================
;; Permission-Based UI Tests
;; =============================================================================

(deftest permission-based-ui-test
  (testing "UI elements shown/hidden based on permissions"
    (testing "Create button shown when can-create"
      (let [page (ui/entity-list-page :users [] sample-entity-config
                                      {:page 1 :page-size 20} 0
                                      {:can-create true :can-edit false :can-delete false}
                                      nil)]
        (is (str/includes? (str page) "/web/admin/users/new"))))

    (testing "Create button hidden when cannot create"
      (let [page (ui/entity-list-page :users [] sample-entity-config
                                      {:page 1 :page-size 20} 0
                                      {:can-create false :can-edit false :can-delete false}
                                      nil)]
        ;; Should not have create link
        (is (not (str/includes? (str page) "/web/admin/users/new")))))

    (testing "Edit button shown when can-edit"
      (let [row (ui/entity-table-row :users sample-record sample-entity-config
                                     {:can-edit true :can-delete false})]
        (is (str/includes? (str row) "/web/admin/users/"))))

    (testing "Edit button hidden when cannot edit"
      (let [row (ui/entity-table-row :users sample-record sample-entity-config
                                     {:can-edit false :can-delete false})]
        ;; Should not have edit link (no actions)
        (is (not (str/includes? (str row) "Edit")))))))

;; =============================================================================
;; Accessibility Tests
;; =============================================================================

(deftest accessibility-form-labels-test
  (testing "Form inputs have associated labels"
    (testing "Text input has label with for attribute"
      (let [widget (ui/render-field-widget :name "John"
                                           {:widget :text-input
                                            :label "Name"
                                            :required true}
                                           nil)
            widget-str (str widget)]
        ;; Should have label element
        (is (str/includes? widget-str ":label"))
        ;; Should have for attribute matching input id
        (is (str/includes? widget-str ":for"))
        (is (str/includes? widget-str "name"))))

    (testing "All field widgets have labels"
      (let [field-configs {:email {:widget :email-input :label "Email"}
                           :password {:widget :password-input :label "Password"}
                           :active {:widget :checkbox :label "Active"}
                           :role {:widget :select :label "Role" :options [[:admin "Admin"]]}
                           :description {:widget :textarea :label "Description"}}]
        (doseq [[field-name field-config] field-configs]
          (let [widget (ui/render-field-widget field-name nil field-config nil)
                widget-str (str widget)]
            ;; Each widget should have a label
            (is (str/includes? widget-str ":label")
                (str "Missing label for field: " field-name))))))))

(deftest accessibility-aria-labels-test
  (testing "Interactive elements have aria-label attributes"
    (testing "Icon buttons have aria-label"
      (let [page (ui/entity-list-page :users [sample-record] sample-entity-config
                                      {:page 1 :page-size 20} 1 sample-permissions nil)
            page-str (str page)]
        ;; Search button should have aria-label
        (is (str/includes? page-str "aria-label"))
        (is (str/includes? page-str "Search"))))

    (testing "Sidebar buttons have aria-label"
      (let [sidebar (ui/admin-sidebar [:users :orders] sample-entity-configs :users)
            sidebar-str (str sidebar)]
        ;; Toggle and pin buttons should have aria-label
        (is (str/includes? sidebar-str "aria-label"))
        (is (str/includes? sidebar-str "Toggle sidebar"))
        (is (str/includes? sidebar-str "Pin sidebar"))))

    (testing "Table row actions have aria-label"
      (let [row (ui/entity-table-row :users sample-record sample-entity-config
                                     {:can-edit true :can-delete false})
            row-str (str row)]
        ;; Edit button should have aria-label
        (is (str/includes? row-str "aria-label"))
        (is (str/includes? row-str "Edit"))))))

(deftest accessibility-semantic-html-test
  (testing "Pages use semantic HTML elements"
    (testing "Sidebar uses nav element"
      (let [sidebar (ui/admin-sidebar [:users] sample-entity-configs :users)]
        ;; Should have nav element
        (is (some #(and (vector? %) (= :nav.admin-sidebar-nav (first %)))
                  (tree-seq vector? seq sidebar)))))

    (testing "Tables use proper table structure"
      (let [table (ui/entity-table :users [sample-record] sample-entity-config
                                   {:page 1 :page-size 20} 1 sample-permissions nil)]
        ;; Should have table, thead, tbody
        (is (some #(and (vector? %) (= :table.data-table (first %)))
                  (tree-seq vector? seq table)))
        (is (str/includes? (str table) ":thead"))
        (is (str/includes? (str table) ":tbody"))))

    (testing "Forms use form element"
      (let [form (ui/entity-form :users sample-entity-config nil nil sample-permissions)]
        (is (= :form.entity-form (first form)))))))

(deftest accessibility-required-fields-test
  (testing "Required fields are properly marked"
    (testing "Required indicator in label"
      (let [widget (ui/render-field-widget :email "test@example.com"
                                           {:widget :email-input
                                            :label "Email"
                                            :required true}
                                           nil)
            widget-str (str widget)]
        ;; Should have asterisk or "required" indicator
        (is (str/includes? widget-str "*"))))

    (testing "Required attribute on input"
      (let [widget (ui/render-field-widget :email "test@example.com"
                                           {:widget :email-input
                                            :label "Email"
                                            :required true}
                                           nil)
            widget-str (str widget)]
        ;; Should have required attribute
        (is (str/includes? widget-str ":required true"))))))

(deftest accessibility-error-messages-test
  (testing "Validation errors are properly associated with fields"
    (testing "Error messages displayed near field"
      (let [widget (ui/render-field-widget :email ""
                                           {:widget :email-input
                                            :label "Email"
                                            :required true}
                                           ["Required field" "Invalid format"])
            widget-str (str widget)]
        ;; Should have error messages
        (is (str/includes? widget-str "Required field"))
        (is (str/includes? widget-str "Invalid format"))
        ;; Should have error styling class
        (is (str/includes? widget-str "has-errors"))))

    (testing "Form-level errors displayed"
      (let [page (ui/entity-detail-page :users sample-entity-config nil
                                        {:email ["Required"] :name ["Too short"]}
                                        sample-permissions nil)
            page-str (str page)]
        ;; Should display validation errors
        (is (str/includes? page-str "Required"))
        (is (str/includes? page-str "Too short"))))))

(deftest accessibility-color-contrast-test
  (testing "Status indicators use both color and text"
    (testing "Boolean field shows text (not just color)"
      (let [active-result (ui/render-field-value :active true {:type :boolean})
            inactive-result (ui/render-field-value :active false {:type :boolean})]
        ;; Should show "Yes"/"No" text, not just color
        (is (str/includes? (str active-result) "Yes"))
        (is (str/includes? (str inactive-result) "No"))))))
