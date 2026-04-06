(ns boundary.e2e.helpers.admin
  "Shared helpers for admin UI e2e tests.
   Wraps BOU-9 auth helpers with admin-specific login and
   DOM query utilities for tables, forms, and HTMX interactions."
  (:require [clojure.string :as str]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [boundary.e2e.helpers.reset :as reset]))

(def ^:private base-url (reset/default-base-url))

(defn admin-url
  "Build a full admin URL from a relative path."
  [path]
  (str base-url "/web/admin" path))

(defn login-as-admin!
  "Navigate to /web/login, fill seed admin credentials, wait for redirect
   to /web/admin/users. Returns the page for chaining."
  [pg seed]
  (page/navigate pg (str base-url "/web/login"))
  (page/wait-for-load-state pg)
  (loc/fill (page/locator pg "input[name='email']") (-> seed :admin :email))
  (loc/fill (page/locator pg "input[name='password']") (-> seed :admin :password))
  (loc/click (page/locator pg "form.form-card button[type='submit']"))
  (page/wait-for-url pg #".*/web/admin/users.*" {:timeout 10000.0})
  pg)

(defn login-as-user!
  "Navigate to /web/login, fill seed user credentials, wait for redirect
   to /web/dashboard. Returns the page for chaining."
  [pg seed]
  (page/navigate pg (str base-url "/web/login"))
  (page/wait-for-load-state pg)
  (loc/fill (page/locator pg "input[name='email']") (-> seed :user :email))
  (loc/fill (page/locator pg "input[name='password']") (-> seed :user :password))
  (loc/click (page/locator pg "form.form-card button[type='submit']"))
  (page/wait-for-url pg #".*/web/dashboard.*" {:timeout 10000.0})
  pg)

(defn wait-for-htmx!
  "Wait for HTMX to settle after a fragment update. Uses a JS promise
   that resolves on the htmx:afterSettle event with a 5s safety timeout."
  [pg]
  (page/evaluate pg
                 "new Promise(r => { const h = () => { r(); }; document.addEventListener('htmx:afterSettle', h, {once:true}); setTimeout(h, 5000); })"))

(defn table-headers
  "Read visible column header texts from table.data-table thead th.
   Returns a vector of lowercase trimmed strings."
  [pg]
  (let [ths (page/locator pg "table.data-table thead th")]
    (->> (range (loc/count-elements ths))
         (mapv #(-> (loc/nth-element ths %)
                    loc/text-content
                    str/trim
                    str/lower-case))
         (filterv seq))))

(defn table-row-count
  "Count data rows in table.data-table tbody."
  [pg]
  (loc/count-elements (page/locator pg "table.data-table tbody tr")))

(defn search!
  "Type a search query into the search input and wait for HTMX table update.
   Uses clear + type-text instead of fill to trigger keyup events for HTMX."
  [pg query]
  (let [input (page/locator pg "input.search-input")]
    (loc/clear input)
    (loc/type-text input query)
    (wait-for-htmx! pg)))

(defn has-empty-state?
  "Check if the empty state element is visible or the table has no data rows."
  [pg]
  (or (pos? (loc/count-elements (page/locator pg ".empty-state")))
      (zero? (loc/count-elements (page/locator pg "table.data-table tbody tr")))))

(defn field-group-visible?
  "Check if a field group with the given data-group-id is visible."
  [pg group-id]
  (loc/is-visible? (page/locator pg (str "div.form-field-group[data-group-id='" group-id "']"))))

(defn flash-visible?
  "Check if a flash/alert message of the given type is visible."
  [pg flash-type]
  (loc/is-visible? (page/locator pg (str ".alert.alert-" (name flash-type)))))
