(ns boundary.e2e.html.admin-tenants-test
  "E2E browser tests for the admin Tenants UI: list overview, search,
   detail/edit forms, soft-delete, and access control."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [boundary.e2e.fixtures :as fx]
            [boundary.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

;; ---------------------------------------------------------------------------
;; List overview
;; ---------------------------------------------------------------------------

(deftest ^:e2e list-shows-data-table
  (testing "Admin tenants list page loads and shows table.data-table with expected columns"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      (is (loc/is-visible? (page/locator pg "table.data-table"))
          "table.data-table should be visible on tenants list page")
      (let [headers (admin/table-headers pg)]
        (is (some #(str/includes? % "slug") headers)
            "Table should have a slug column")
        (is (some #(str/includes? % "name") headers)
            "Table should have a name column")
        (is (some #(str/includes? % "status") headers)
            "Table should have a status column")))))

(deftest ^:e2e list-hides-internal-fields
  (testing "Internal fields (schema-name, settings, deleted-at) are not shown in the table"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      (let [headers (admin/table-headers pg)]
        (is (not (some #(str/includes? % "schema") headers))
            "schema-name should not be a table column")
        (is (not (some #(str/includes? % "setting") headers))
            "settings should not be a table column")
        (is (not (some #(str/includes? % "deleted") headers))
            "deleted-at should not be a table column")))))

(deftest ^:e2e search-filters-by-name
  (testing "Search input filters the tenants table via HTMX"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      ;; Search for the seed tenant
      (admin/search! pg "Acme")
      (let [rows (admin/table-row-count pg)]
        (is (pos? rows) "Search for 'Acme' should return at least 1 row"))
      ;; Search for something that doesn't exist
      (admin/search! pg "zzz-nonexistent-tenant")
      (is (admin/has-empty-state? pg)
          "Search for nonexistent tenant should show empty state"))))

(deftest ^:e2e search-htmx-no-full-reload
  (testing "Search triggers HTMX fragment update, not a full page reload"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      (page/evaluate pg "document.body.setAttribute('data-e2e-marker', 'alive')")
      (admin/search! pg "Acme")
      (let [marker (page/evaluate pg "document.body.getAttribute('data-e2e-marker')")]
        (is (= "alive" marker)
            "DOM marker should survive HTMX fragment update (no full page reload)")))))

;; ---------------------------------------------------------------------------
;; Detail & edit
;; ---------------------------------------------------------------------------

(deftest ^:e2e detail-shows-field-groups
  (testing "Tenant detail page shows field groups 'identity' and 'state'"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/tenants/" (-> fx/*seed* :tenant :id))))
      (page/wait-for-load-state pg)
      (is (admin/field-group-visible? pg "identity")
          "Field group 'identity' (slug, name) should be visible")
      (is (admin/field-group-visible? pg "state")
          "Field group 'state' (status, schema-name) should be visible"))))

(deftest ^:e2e detail-shows-form-with-fields
  (testing "Tenant detail page shows editable form with slug, name, status, and schema-name"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/tenants/" (-> fx/*seed* :tenant :id))))
      (page/wait-for-load-state pg)
      (is (loc/is-visible? (page/locator pg "form.entity-form"))
          "Entity form should be visible")
      ;; Verify key fields are present in the form
      (is (pos? (loc/count-elements (page/locator pg "form.entity-form [name='slug']")))
          "Slug field should be present in the form")
      (is (pos? (loc/count-elements (page/locator pg "form.entity-form [name='name']")))
          "Name field should be present in the form")
      (is (pos? (loc/count-elements (page/locator pg "form.entity-form [name='schema-name']")))
          "Schema-name field should be present in the form"))))

(deftest ^:e2e edit-name-change
  (testing "Changing tenant name and submitting persists correctly"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/tenants/" (-> fx/*seed* :tenant :id))))
      (page/wait-for-load-state pg)
      (page/wait-for-selector pg "form.entity-form" {:timeout 10000.0})
      ;; Change the name
      (loc/fill (page/locator pg "input[name='name']") "Updated Acme")
      ;; Submit
      (loc/click (page/locator pg "form.entity-form button[type='submit']"))
      (admin/wait-for-htmx! pg)
      (page/wait-for-load-state pg)
      ;; Verify name updated
      (let [name-value (loc/input-value (page/locator pg "input[name='name']"))]
        (is (= "Updated Acme" name-value)
            "Name should be updated to 'Updated Acme' after form submission")))))

(deftest ^:e2e soft-delete-removes-from-list
  (testing "Soft-deleting a tenant removes it from the list"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      (let [initial-count (admin/table-row-count pg)]
        ;; Click the checkbox for the first row to select it
        (loc/click (page/locator pg "table.data-table tbody tr:first-child td.checkbox-cell input"))
        ;; Auto-accept the hx-confirm dialog
        (page/evaluate pg "document.addEventListener('htmx:confirm', function(e) { e.detail.issueRequest(); }, {once: true})")
        ;; Click the bulk delete button
        (loc/click (page/locator pg "#bulk-delete-btn"))
        (admin/wait-for-htmx! pg)
        ;; Row count should decrease
        (let [new-count (admin/table-row-count pg)]
          (is (< new-count initial-count)
              "Soft-deleted tenant should disappear from the list"))))))
