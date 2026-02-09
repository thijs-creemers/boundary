# Task 6: Admin Module Tenant Integration - Implementation Plan

**Status**: ⏸️ DEPRIORITIZED (Awaiting User Decision)  
**Estimated Effort**: 2-4 hours  
**Risk Level**: HIGH (breaking changes to existing admin functionality)  
**Priority**: P3 (Nice-to-have, not critical for Phase 8)

---

## Overview

Add tenant-scoped filtering to the admin module so that CRUD operations automatically respect tenant boundaries in multi-tenant applications.

**Current State**: Admin module has no tenant awareness
- List operations show all records across all tenants
- Get/Update/Delete operations can access any tenant's data
- No tenant selector in UI

**Desired State**: Admin module respects tenant context
- List operations filtered to current tenant
- CRUD operations enforce tenant boundaries
- Optional: Super-admin can switch tenants via UI

---

## Required Changes

### 1. Service Layer - Tenant Context Injection

**File**: `libs/admin/src/boundary/admin/shell/service.clj`

**Changes Needed**:

```clojure
;; BEFORE (current)
(defn list-entities [this entity-name options]
  (let [query (build-query entity-name options)]
    (db/execute! db-ctx query)))

;; AFTER (with tenant filtering)
(defn list-entities [this entity-name options]
  (let [tenant-id (:tenant-id options)  ; Extract from options
        query (build-query entity-name options)
        ; Add WHERE tenant_id = ? clause
        tenant-query (if tenant-id
                      (assoc query :where [:and (:where query) [:= :tenant-id tenant-id]])
                      query)]
    (db/execute! db-ctx tenant-query)))
```

**Impact**: 
- All 7 CRUD operations need modification
- ~150 lines of changes across service.clj
- Risk: Breaking existing single-tenant deployments

---

### 2. HTTP Layer - Tenant Extraction

**File**: `libs/admin/src/boundary/admin/shell/http.clj`

**Changes Needed**:

```clojure
;; Add tenant middleware integration
(defn list-entities-handler [admin-service]
  (fn [request]
    (let [entity-name (get-in request [:path-params :entity])
          tenant-id (get-in request [:tenant :id])  ; From tenant middleware
          options (merge (parse-query-params request)
                        {:tenant-id tenant-id})]
      (list-entities admin-service entity-name options))))
```

**Impact**:
- All 8 HTTP handlers need modification
- Dependency on tenant middleware (already exists)
- ~80 lines of changes

---

### 3. UI Layer - Tenant Display

**File**: `libs/admin/src/boundary/admin/core/ui.clj`

**Changes Needed** (Optional):

```clojure
;; Add tenant indicator to header
(defn render-admin-header [tenant]
  [:header.admin-header
   [:div.tenant-info
    [:span.tenant-label "Current Tenant: "]
    [:span.tenant-name (:name tenant)]]
   ; ... existing header content
   ])

;; Super-admin: Add tenant selector dropdown
(defn render-tenant-selector [tenants current-tenant-id]
  [:select {:name "tenant-id"
            :hx-get "/web/admin"
            :hx-target "#main-content"}
   (for [tenant tenants]
     [:option {:value (:id tenant)
               :selected (= (:id tenant) current-tenant-id)}
      (:name tenant)])])
```

**Impact**:
- ~50 lines of UI changes
- Requires tenant service integration
- Design decision: Always show tenant? Only for super-admin?

---

### 4. Schema Repository - Tenant Schema Switching

**File**: `libs/admin/src/boundary/admin/shell/schema_repository.clj`

**Changes Needed** (PostgreSQL only):

```clojure
(require '[boundary.tenant.shell.provisioning :as provisioning])

(defn fetch-table-metadata [this table-name]
  (if-let [tenant-schema (:tenant-schema context)]
    ; Query from tenant schema
    (provisioning/with-tenant-schema db-ctx tenant-schema
      (fn [tenant-ctx]
        (introspect-table tenant-ctx table-name)))
    ; Query from public schema (non-tenant)
    (introspect-table db-ctx table-name)))
```

**Impact**:
- Schema introspection needs tenant awareness
- ~30 lines of changes
- PostgreSQL-specific behavior

---

### 5. Configuration - Tenant Mode Flag

**File**: `resources/conf/dev/config.edn`

**New Configuration**:

```clojure
:boundary/admin
{:enabled? true
 :base-path "/web/admin"
 :require-role :admin
 
 ; NEW: Tenant configuration
 :multi-tenant? true               ; Enable tenant filtering
 :tenant-mode :single-tenant       ; :single-tenant or :super-admin
 ; :single-tenant - User sees only their tenant
 ; :super-admin - User can switch tenants via UI
 
 :entities {...}}
```

**Impact**:
- Backward compatible (default: `false`)
- ~10 lines of config

---

## Testing Requirements

### Unit Tests (New)

```clojure
;; Test tenant filtering in service layer
(deftest test-list-entities-with-tenant-filter
  (let [tenant-a-records (create-test-records :users tenant-a 3)
        tenant-b-records (create-test-records :users tenant-b 2)]
    
    ; List with tenant-a context
    (let [result (list-entities service :users {:tenant-id tenant-a})]
      (is (= 3 (count (:records result))))
      (is (every? #(= tenant-a (:tenant-id %)) (:records result))))
    
    ; List with tenant-b context
    (let [result (list-entities service :users {:tenant-id tenant-b})]
      (is (= 2 (count (:records result))))
      (is (every? #(= tenant-b (:tenant-id %)) (:records result))))))
```

**Coverage**:
- List with tenant filter (5 tests)
- Get with tenant validation (3 tests)
- Update with tenant boundary check (3 tests)
- Delete with tenant boundary check (3 tests)
- Cross-tenant access prevention (4 tests)

**Total**: ~18 new tests, ~120 assertions

---

### Integration Tests (New)

```clojure
;; Test HTTP endpoints with tenant context
(deftest test-admin-list-entities-tenant-isolation
  (with-test-tenants [tenant-a tenant-b]
    (with-test-data :users [{:tenant-id tenant-a :name "Alice"}
                            {:tenant-id tenant-b :name "Bob"}]
      
      ; Request as tenant-a user
      (let [response (app (-> (request :get "/web/admin/users")
                              (assoc-in [:tenant :id] tenant-a)))]
        (is (= 200 (:status response)))
        (is (= 1 (count-users-in-response response))))
      
      ; Request as tenant-b user
      (let [response (app (-> (request :get "/web/admin/users")
                              (assoc-in [:tenant :id] tenant-b)))]
        (is (= 200 (:status response)))
        (is (= 1 (count-users-in-response response)))))))
```

**Coverage**:
- HTTP handlers with tenant context (8 tests)
- UI rendering with tenant display (3 tests)
- Tenant switching (super-admin mode) (2 tests)

**Total**: ~13 new tests, ~80 assertions

---

## Implementation Steps

### Phase 1: Service Layer (1-2 hours)

1. ✅ Add `tenant-id` parameter to all service methods
2. ✅ Modify query builders to include tenant filter
3. ✅ Add validation: reject operations on wrong tenant
4. ✅ Update unit tests
5. ✅ Verify backward compatibility (tenant-id optional)

### Phase 2: HTTP Layer (30 minutes)

1. ✅ Extract tenant from request context
2. ✅ Pass tenant-id to service methods
3. ✅ Update integration tests
4. ✅ Test with and without tenant middleware

### Phase 3: UI Layer (30 minutes)

1. ✅ Add tenant indicator to header
2. ✅ (Optional) Add tenant selector for super-admin
3. ✅ Update UI tests
4. ✅ Verify responsive design

### Phase 4: Schema Repository (30 minutes)

1. ✅ Add tenant schema switching (PostgreSQL)
2. ✅ Test with tenant schemas
3. ✅ Verify fallback for non-PostgreSQL

### Phase 5: Integration Testing (1 hour)

1. ✅ Run full admin test suite
2. ✅ Manual testing with 2+ tenants
3. ✅ Performance testing (ensure no N+1 queries)
4. ✅ Verify existing functionality intact

---

## Risks & Mitigation

### Risk 1: Breaking Existing Single-Tenant Deployments

**Likelihood**: HIGH  
**Impact**: CRITICAL

**Mitigation**:
- Make tenant filtering opt-in via config flag
- Default: `multi-tenant? false` (backward compatible)
- Comprehensive regression testing

---

### Risk 2: Performance Degradation

**Likelihood**: MEDIUM  
**Impact**: MEDIUM

**Issue**: Extra WHERE clause on every query

**Mitigation**:
- Ensure `tenant_id` column is indexed
- Benchmark before/after (target: < 5% overhead)
- Use query plan analysis

---

### Risk 3: Tenant Leakage (Security)

**Likelihood**: LOW  
**Impact**: CRITICAL

**Issue**: Bug in filtering logic allows cross-tenant access

**Mitigation**:
- Comprehensive integration tests
- Manual penetration testing
- Code review with security focus
- Audit logging of all admin operations

---

### Risk 4: Complex UI for Super-Admin Mode

**Likelihood**: MEDIUM  
**Impact**: MEDIUM

**Issue**: Tenant selector adds complexity to admin UI

**Mitigation**:
- Implement single-tenant mode first (simpler)
- Super-admin mode as optional enhancement
- Clear UX design before implementation

---

## Alternative Approaches

### Option 1: Separate Admin Per Tenant (Recommended for MVP)

**Pros**:
- Simpler implementation (no tenant switching)
- Clearer security boundary
- Easier to test

**Cons**:
- Super-admin must log in to each tenant separately
- No cross-tenant reporting

**Recommendation**: Start with this for Phase 8

---

### Option 2: Super-Admin with Tenant Selector

**Pros**:
- Single login for all tenants
- Convenient for support teams
- Cross-tenant operations possible

**Cons**:
- More complex UI
- Higher security risk
- Harder to audit

**Recommendation**: Add in Phase 9 (post-MVP)

---

### Option 3: Dual Mode (Both Single-Tenant and Super-Admin)

**Pros**:
- Flexibility for different use cases
- Can switch modes via config

**Cons**:
- Most complex to implement
- More code to maintain
- More test coverage needed

**Recommendation**: Add only if strong user demand

---

## Decision Required

**Questions for User**:

1. **Is admin tenant integration critical for Phase 8?**
   - If NO → Keep deprioritized, address in Phase 9
   - If YES → Which approach? (Option 1 recommended)

2. **What is the admin use case?**
   - Tenant admins manage own data? → Option 1 (Single-Tenant)
   - Platform support team? → Option 2 (Super-Admin)
   - Both? → Option 3 (Dual Mode)

3. **What is the timeline?**
   - Need now? → Implement Option 1 (2-3 hours)
   - Can wait? → Defer to Phase 9

4. **What is the risk tolerance?**
   - Low risk → Keep deprioritized, validate with E2E tests first
   - High urgency → Accept risk, implement with comprehensive tests

---

## Recommendation

**KEEP DEPRIORITIZED** for Phase 8 because:

1. ✅ **Not Blocking**: Multi-tenancy works without admin integration
   - Tenant provisioning: ✅ Working
   - Jobs module: ✅ Tenant-scoped
   - Cache module: ✅ Tenant-isolated
   - User operations: ✅ Tenant-aware

2. ✅ **High Risk**: Breaking changes to critical admin module
   - All CRUD operations affected
   - Complex testing matrix
   - Potential tenant leakage bugs

3. ✅ **Alternative Exists**: Tenant-scoped database access
   - Use `with-tenant-schema` for manual admin queries
   - REPL-based admin operations per tenant
   - Wait for E2E test infrastructure before adding complexity

4. ✅ **Better in Phase 9**: After E2E tests stabilized
   - Test infrastructure in place
   - More time for careful implementation
   - User feedback on multi-tenancy patterns

---

## If User Decides to Proceed

**Checklist Before Starting**:
- [ ] User explicitly approves risk of breaking admin module
- [ ] Decision made on approach (Option 1, 2, or 3)
- [ ] Time allocated (2-4 hours minimum)
- [ ] Rollback plan in place
- [ ] Backup of current working state

**First Steps**:
1. Create feature branch: `feature/admin-tenant-integration`
2. Start with Option 1 (Single-Tenant mode)
3. Implement service layer changes first
4. Run full test suite after each change
5. Manual testing with 2 tenants minimum

---

**Document Version**: 1.0  
**Date**: 2026-02-09  
**Author**: Boundary AI Agent  
**Status**: Awaiting user decision on whether to proceed
