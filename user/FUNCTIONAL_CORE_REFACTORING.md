# Functional Core / Shell Architecture Refactoring

## Overview

This document describes the completed refactoring of the user module from a traditional service architecture to a **Functional Core / Shell** architecture (also known as Ports & Adapters or Hexagonal Architecture with functional emphasis).

## Architecture Changes

### Before: Traditional Service Architecture
```
Controllers → Service Layer → Repository Layer → Database
                    ↓
            (Mixed business logic + I/O)
```

### After: Functional Core / Shell Architecture
```
Controllers → Shell Service (I/O orchestration) → Functional Core (pure business logic)
                    ↓                                        ↓
              External Systems                         No side effects
              (DB, Time, Logs)                        (deterministic)
```

## New Directory Structure

```
boundary/user/
├── core/                    # NEW: Pure functional core
│   ├── user.clj            # Pure user business logic
│   └── session.clj         # Pure session business logic
├── shell/
│   └── service.clj         # REFACTORED: I/O orchestration shell
├── infrastructure/         # EXISTING: Database implementations
├── ports.clj               # UPDATED: Extended repository interfaces
└── schema.clj              # UPDATED: Added session schemas
```

## Core Principles Implemented

### Functional Core (`boundary.user.core.*`)
- **Pure functions only** - no I/O, no side effects
- **Deterministic** - same input always produces same output
- **No external dependencies** - time, UUIDs, tokens passed as parameters
- **Immutable data structures** only
- **Business logic and rules** - all domain knowledge lives here

### Shell Layer (`boundary.user.shell.service`)
- **I/O orchestration** - coordinates between core and external systems
- **External dependency management** - generates time, UUIDs, tokens
- **Side effect handling** - database calls, logging, events
- **Thin layer** - minimal logic, delegates to core functions

## Key Refactoring Changes

### 1. Pure User Core Functions (`boundary.user.core.user`)

**Business Logic Functions:**
- `validate-user-creation-request` - Schema validation
- `check-user-uniqueness` - Duplicate email business rule
- `prepare-user-for-creation` - Entity preparation with defaults
- `validate-user-update-request` - Update validation
- `calculate-user-changes` - Change detection
- `validate-user-business-rules` - Business rule enforcement
- `can-delete-user?` - Deletion permission rules
- `prepare-user-for-soft-deletion` - Soft delete preparation

**Analysis Functions:**
- `enrich-user-with-computed-fields` - Profile completeness, age
- `calculate-profile-completeness` - Scoring algorithm
- `analyze-user-activity-patterns` - Activity analysis
- `filter-users-by-criteria` - Pure filtering logic
- `sort-users` - Pure sorting logic

### 2. Pure Session Core Functions (`boundary.user.core.session`)

**Session Lifecycle:**
- `validate-session-creation-request` - Session creation validation
- `calculate-session-expiry` - Expiry time calculation
- `prepare-session-for-creation` - Complete session entity prep
- `is-session-valid?` - Multi-criteria validation
- `prepare-session-for-invalidation` - Logout preparation

**Security Functions:**
- `validate-session-security-context` - IP/User-agent validation
- `calculate-session-security-score` - Risk assessment algorithm
- `should-cleanup-session?` - Cleanup policy evaluation

**Extension Logic:**
- `should-extend-session?` - Extension policy evaluation
- `calculate-extended-session-expiry` - New expiry calculation
- `prepare-session-for-extension` - Extension preparation

### 3. Refactored Shell Service (`boundary.user.shell.service`)

**Helper Functions:**
- `generate-secure-token` - Cryptographic token generation
- `generate-user-id` - UUID generation
- `current-timestamp` - Time dependency

**Orchestration Methods:**
- `create-user` - Validates → Checks uniqueness → Prepares → Persists
- `update-user` - Validates → Checks rules → Prepares → Persists  
- `create-session` - Validates → Generates deps → Prepares → Persists
- `find-session-by-token` - Fetches → Validates → Updates access → Returns
- `invalidate-session` - Finds → Prepares invalidation → Persists

**New Advanced Methods:**
- `validate-session` - Security context validation
- `extend-session-if-needed` - Automatic session extension
- `cleanup-expired-sessions` - Policy-based cleanup

## Benefits Achieved

### 1. **Testability**
- Core functions are pure - easy to unit test
- No mocking needed for business logic tests  
- Deterministic behavior guarantees reliable tests

### 2. **Maintainability**
- Business logic separated from I/O concerns
- Core functions are self-documenting
- Clear separation of responsibilities

### 3. **Reliability**
- Pure functions cannot have hidden side effects
- Explicit dependency injection prevents surprises
- Immutable data prevents accidental mutations

### 4. **Reusability** 
- Core functions can be called from multiple contexts
- Business logic independent of delivery mechanism
- Easy to compose complex operations from simple functions

### 5. **Performance**
- Pure functions are easier to optimize
- Can be safely parallelized
- Enable advanced compiler optimizations

## Extended Repository Interfaces

Added new methods to `IUserSessionRepository`:
- `update-session` - For access time updates and extensions
- `find-all-sessions` - For cleanup operations  
- `delete-session` - For permanent deletion

## Schema Enhancements

Added `CreateSessionRequest` schema for proper session validation:
```clojure
(def CreateSessionRequest
  [:map {:title "Create Session Request"}
   [:user-id :uuid]
   [:tenant-id :uuid] 
   [:ip-address {:optional true} :string]
   [:user-agent {:optional true} :string]
   [:device-info {:optional true} [:map ...]]])
```

## Future Enhancements

With this architecture in place, future enhancements become much easier:

1. **New Business Rules** - Add to core functions, shell orchestrates
2. **Different I/O Systems** - Replace shell implementation, core unchanged  
3. **Performance Optimization** - Core functions are pure, easy to optimize
4. **Integration Testing** - Test shell orchestration separately from business logic
5. **Event Sourcing** - Core functions naturally support event generation

## Testing Strategy

### Core Functions
```clojure
;; Pure function tests - no mocks needed
(deftest test-user-validation
  (is (= {:valid? true :data user-data}
         (user-core/validate-user-creation-request user-data))))
```

### Shell Layer  
```clojure
;; Integration tests with real or test repositories
(deftest test-create-user-orchestration
  (with-test-repositories [user-repo session-repo]
    (let [service (create-user-service user-repo session-repo)]
      (is (= expected-user
             (.create-user service user-request))))))
```

This architecture provides a solid foundation for scalable, maintainable, and testable user management functionality while preserving all existing behavior and extending capabilities with new features like session security and lifecycle management.