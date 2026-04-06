# BOU-10: Playwright Admin E2E Test Suite

## Summary

End-to-end test suite for the Boundary admin UI (Users + Tenants), using Spel (Clojure Playwright wrapper) within the existing `libs/e2e/` library and Kaocha test runner.

## File Structure

```
libs/e2e/test/boundary/e2e/
├── helpers/
│   └── admin.clj              ← NEW: admin login + navigation + HTMX helpers
└── html/
    ├── admin_users_test.clj   ← NEW: Users list + detail + edit tests
    └── admin_tenants_test.clj ← NEW: Tenants list + detail + edit + soft-delete tests
```

## Shared Helper: `helpers/admin.clj`

Reusable functions wrapping BOU-9 auth helpers:

| Function | Purpose |
|---|---|
| `login-as-admin!` | Navigate to `/web/login`, fill seed admin credentials, wait for redirect to `/web/admin/users` |
| `login-as-user!` | Same with regular user credentials (for access control tests) |
| `wait-for-htmx!` | Wait for HTMX `afterSettle` event via `page/evaluate` — avoids `waitForNavigation` on fragment updates |
| `table-headers` | Read column names from `table.data-table thead th` |
| `table-row-count` | Count `table.data-table tbody tr` rows |
| `search!` | Fill `.search-input`, trigger search, wait for HTMX settlement |

## Test Data

Uses existing `with-fresh-seed` fixture — clean DB per test via `POST /test/reset`.

Baseline seed provides:
- **Admin:** `admin@acme.test` / `Test-Pass-1234!` (role: admin)
- **User:** `user@acme.test` / `Test-Pass-1234!` (role: user)
- **Tenant:** "Acme Test" (slug: acme, status: active)

## CSS Selectors (from Boundary admin UI conventions)

| Element | Selector |
|---|---|
| Data table | `table.data-table` within `div#entity-table-container` |
| Form (edit/create) | `div.form-card` with `div.form-card-body` |
| Field groups | `div.form-field-group[data-group-id]` |
| Search input | `.search-input` within `.entity-search-form` |
| Validation errors | `.field-errors` |
| Pagination | `div.pagination` |

## Test Scenarios

### Users — List (`admin_users_test.clj`)

| Test | Assertion |
|---|---|
| `list-shows-data-table` | `table.data-table` visible; columns include email, name, role, created-at |
| `list-hides-sensitive-fields` | password-hash, mfa-secret, mfa-backup-codes not in table headers |
| `search-filters-by-email` | Search seed admin email → 1 row; search "nonexistent" → empty state message |
| `search-htmx-no-full-reload` | Search triggers HTMX fragment swap, not full page navigation |

### Users — Detail & Edit

| Test | Assertion |
|---|---|
| `detail-shows-field-groups` | Field groups "identity" (email, name) and "access" (role, active) visible via `div.form-field-group` |
| `detail-readonly-fields` | id, created-at, updated-at have `disabled` or `readonly` attribute |
| `edit-role-via-dropdown` | Change role via select → submit → detail reloads with new value |
| `edit-required-field-empty-shows-error` | Clear name → submit → `.field-errors` visible, no redirect |
| `edit-success-shows-notification` | Successful save shows success notification element |

### Tenants — List (`admin_tenants_test.clj`)

| Test | Assertion |
|---|---|
| `list-shows-data-table` | `table.data-table` visible; columns include slug, name, status, created-at |
| `list-hides-internal-fields` | schema-name, settings, deleted-at not in table headers |
| `search-filters-by-name` | Search "Acme" → row found; search "zzz" → empty state |
| `search-htmx-no-full-reload` | Fragment update without full page reload |

### Tenants — Detail & Edit

| Test | Assertion |
|---|---|
| `detail-shows-field-groups` | Groups "identity" and "state" visible |
| `detail-readonly-slug-schema` | slug and schema-name fields are readonly/disabled |
| `edit-status-change` | Change status active → suspended → submit → detail shows new status |
| `soft-delete-removes-from-list` | Delete action → tenant disappears from list |

### Access Control (shared)

| Test | Assertion |
|---|---|
| `unauthenticated-redirects-to-login` | Visit `/web/admin/users` without session → redirect to `/web/login` |
| `regular-user-denied-admin` | Login as user → navigate to `/web/admin/users` → redirect or 403 |

## HTMX Testing Strategy

No `waitForNavigation` for HTMX fragment updates. Instead:

```clojure
(defn wait-for-htmx! [pg]
  (page/evaluate pg
    "new Promise(r => document.addEventListener('htmx:afterSettle', r, {once:true}))"))
```

Fallback: `page/wait-for-selector` on the element that appears/changes after HTMX swap.

## Explicitly Excluded

- **Pagination tests** — baseline seed has only 2 users and 1 tenant; seeding 25+ records requires `/test/reset` extension (separate ticket)
- **Email change + login verification** — split-table update already covered by unit tests in `split_table_update_test.clj`
- **Sort verification** — not meaningful with 2 seed records
- **Default sort order** — cannot verify `created-at desc` with single-record entity sets

## Entity Config Reference

- Users config: `resources/conf/test/admin/users.edn`
- Tenants config: `resources/conf/test/admin/tenants.edn`

Key config keys used in tests:
- `:list-fields` — determines visible table columns
- `:hide-fields` — fields excluded from UI entirely
- `:readonly-fields` — fields with disabled/readonly inputs
- `:search-fields` — fields searchable via the search input
- `:field-groups` — sections on the detail/edit form
- `:split-table-update` — email lives in `auth_users`, profile fields in `users`

## Running Tests

```bash
bb e2e           # All e2e tests (requires running test server on port 3100)
clojure -M:test:e2e :e2e --focus boundary.e2e.html.admin-users-test
clojure -M:test:e2e :e2e --focus boundary.e2e.html.admin-tenants-test
```
