# Admin User Split-Table Fix Design

**Date:** 2026-05-04  
**Branch:** fix/bou-28-create-update-admin-user-in-split-table-setup  
**Status:** Approved

## Problem

Admin user management silently fails for three operations in the split-table design (`auth_users` + `users`):

1. **List** — deleted users appear (no `WHERE deleted_at IS NULL`) because `:soft-delete true` missing from entity config
2. **Update silently fails** — `execute-one!` (SELECT-oriented) used for DML UPDATE in split-table transaction; returns nil, 0 rows changed, no error raised
3. **Bulk delete corrupts data** — `bulk-delete-entities` targets `table-name` (`:users`) for soft-delete instead of `soft-delete-table` (`:auth_users`); `:users` has no `deleted_at` column

Create flow works correctly — `:create-redirect-url "/web/users/new"` delegates to `user/register-user` which writes both tables atomically.

## Root Causes

### 1. Missing `:soft-delete true` in users.edn

Both `resources/conf/dev/admin/users.edn` and `resources/conf/test/admin/users.edn` lack `:soft-delete true` at the entity level.

Effect: `soft-delete?` evaluates to `false` throughout `service.clj`. List queries include soft-deleted rows. Delete path issues hard DELETE against `:users` instead of soft-delete against `:auth_users`.

Fix: Add `:soft-delete true` to both files.

### 2. `execute-one!` used for DML in split-table update

In `libs/admin/src/boundary/admin/shell/service.clj`, `update-entity` calls `db/execute-one!` for the split-table UPDATE statements.

`execute-one!` is defined as `(first (execute-query! ...))` — it executes a SELECT and takes the first row. For an UPDATE statement it returns nil (no rows selected), no exception raised, caller sees apparent success.

`execute-update!` is the correct function — it executes DML and returns `::jdbc/update-count`.

Fix: Replace `execute-one!` with `execute-update!` in both UPDATE paths inside `update-entity`'s split-table branch.

### 3. `bulk-delete-entities` uses wrong table for soft-delete

In `service.clj`, the soft-delete branch of `bulk-delete-entities` builds:

```clojure
{:update table-name   ;; :users — WRONG
 :set soft-delete-data
 :where [:in primary-key id-strings]}
```

Should be:

```clojure
{:update soft-delete-table  ;; :auth_users — CORRECT
 :set soft-delete-data
 :where [:in primary-key id-strings]}
```

The `soft-delete-table` is already present in `:query-overrides` as `:soft-delete-table :auth_users` and is read by `service.clj`. The variable just isn't being used in this branch.

Fix: Replace `table-name` with `soft-delete-table` in the soft-delete branch.

## Design

### Scope

Minimal, targeted fixes. No refactoring beyond what is required to correct the three bugs. Each fix is independently verifiable.

### Changes

#### A. Config: `resources/conf/dev/admin/users.edn`

Add `:soft-delete true` alongside `:table-name :users`:

```clojure
:table-name      :users
:soft-delete     true
```

#### B. Config: `resources/conf/test/admin/users.edn`

Same as A — identical change to keep dev/test configs in sync.

#### C. Service: `libs/admin/src/boundary/admin/shell/service.clj`

Two sub-fixes:

**C1 — `update-entity` DML fix:**

In the split-table UPDATE transaction, replace all `db/execute-one!` calls with `db/execute-update!` for the UPDATE statements.

**C2 — `bulk-delete-entities` table fix:**

In the soft-delete branch, replace `table-name` with `soft-delete-table`.

#### D. Tests: `libs/admin/test/boundary/admin/shell/admin_user_operations_test.clj`

New integration test namespace covering the actual `auth_users`/`users` schema (not the synthetic `test_auth`/`test_profiles` tables used by existing split-table tests).

Tests:
- `update-user-primary-fields` — update `:name`, `:role` → confirm persisted in `users`
- `update-user-secondary-fields` — update `:email`, `:active` → confirm persisted in `auth_users`
- `update-user-mixed-fields` — update fields spanning both tables atomically
- `list-users-excludes-deleted` — soft-deleted user does not appear in list
- `bulk-soft-delete-targets-auth-users` — bulk delete sets `deleted_at` on `auth_users`, not `users`

### Data Flow: Update Path (after fix)

```
HTTP PUT /admin/users/:id
  → update-entity-handler
  → service/update-entity
      split-table? → yes
      split-update-data → {:primary-fields {name, role, ...}
                            :secondary-fields {email, active}}
      tx:
        execute-update! {:update :users ...}      ;; primary
        execute-update! {:update :auth_users ...} ;; secondary
      return {:updated-at ...}
  → render form with success flash
```

### Data Flow: Bulk Delete (after fix)

```
HTTP DELETE /admin/users (bulk)
  → bulk-delete-entities-handler
  → service/bulk-delete-entities
      soft-delete? → yes (because :soft-delete true now present)
      soft-delete-table → :auth_users
      execute-update! {:update :auth_users :set {:deleted_at now} ...}
```

### Error Handling

`execute-update!` raises on DB errors (constraint violations, missing column). Silent nil return eliminated. Existing `try/catch` in HTTP handlers surfaces errors as 500 with error flash.

No new error-handling code required — fixing the wrong function call restores correct behavior.

### Testing Strategy

- New tests tagged `^:integration` — run against H2 in-memory DB via `:db/h2` alias
- Schema must have `auth_users` and `users` tables — confirmed present in migration files
- Use `boundary.test.db-helpers/with-test-db` fixture pattern (matches existing integration tests)

### Out of Scope

- MFA enrollment flow — controlled separately, not via admin edit form
- Password reset in admin — delegated to user module
- `wrap-method-override` audit — HTML forms work (confirmed by existing tests); not a blocker

## Acceptance Criteria

1. Admin list page shows only non-deleted users
2. Updating `:name`/`:role` persists to `users` table
3. Updating `:email`/`:active` persists to `auth_users` table  
4. Bulk delete soft-deletes via `auth_users.deleted_at`
5. All new integration tests pass
6. No regressions in existing admin tests
