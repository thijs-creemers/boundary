# Phase 1 Checklist - Extract boundary/core Library

**Duration**: 3 days (estimated)  
**Branch**: `feat/split-phase1`  
**Previous Phase**: Phase 0 (Monorepo Infrastructure) ✅  
**Next Phase**: Phase 2 (Extract observability)

---

## Overview

**Goal**: Extract `boundary.shared` into the independent `boundary/core` library.

**Scope**:
- Move `src/boundary/shared/` → `libs/core/src/boundary/core/`
- Rename namespace: `boundary.shared.core` → `boundary.core`
- Migrate all tests
- Update all references in main codebase
- Verify zero external dependencies (except clojure.core, malli, etc.)

---

## Pre-Phase Verification

- [ ] Phase 0 complete and merged to main
- [ ] Working directory clean: `git status` shows no uncommitted changes
- [ ] On correct branch: `git checkout -b feat/split-phase1`
- [ ] Build tools working: `clojure -T:build status`
- [ ] REPL starts: `clojure -M:dev:repl-clj`

---

## Step 1: Analyze Current Structure (Day 1)

### 1.1 Identify Files to Move

```bash
# List all files in boundary.shared
find src/boundary/shared -type f -name "*.clj" | sort

# Count files
find src/boundary/shared -type f -name "*.clj" | wc -l
```

**Expected directories**:
- [ ] `src/boundary/shared/core/validation/`
- [ ] `src/boundary/shared/core/utils/`
- [ ] `src/boundary/shared/core/interceptor.clj`
- [ ] `src/boundary/shared/core/protocols/`
- [ ] `src/boundary/shared/tools/` (may stay for now)

**Task**: Document exact file list in this checklist.

### 1.2 Identify Test Files

```bash
# List test files
find test/boundary/shared -type f -name "*_test.clj" | sort
```

**Task**: Document exact test file list.

### 1.3 Find All References

```bash
# Find all references to boundary.shared in codebase
rg "boundary\.shared" src/ test/ --type clojure | wc -l

# Get detailed list
rg "boundary\.shared" src/ test/ --type clojure > /tmp/shared-references.txt
```

**Task**: Review reference count and patterns.

### 1.4 Check for External Dependencies

```bash
# Analyze what boundary.shared depends on
rg "^\s*\(:require" src/boundary/shared -A 20 | rg "boundary\." | grep -v "boundary.shared"
```

**Expected**: Should be ZERO dependencies on other boundary namespaces.

**Task**: Verify independence.

---

## Step 2: Move Files (Day 1)

### 2.1 Create Target Directory Structure

```bash
# Create directory structure (if not exists)
mkdir -p libs/core/src/boundary/core/{validation,utils,protocols}
mkdir -p libs/core/test/boundary/core/{validation,utils,protocols}
mkdir -p libs/core/resources
```

**Checklist**:
- [ ] Directories created
- [ ] `.gitkeep` files removed (since we're adding real files)

### 2.2 Move Source Files

**Move validation files**:
```bash
# Example (adjust based on Step 1.1 findings)
mv src/boundary/shared/core/validation/ libs/core/src/boundary/core/validation/
```

**Move other core files**:
```bash
mv src/boundary/shared/core/utils/ libs/core/src/boundary/core/utils/
mv src/boundary/shared/core/interceptor.clj libs/core/src/boundary/core/interceptor.clj
mv src/boundary/shared/core/protocols/ libs/core/src/boundary/core/protocols/
```

**Checklist**:
- [ ] All core files moved
- [ ] Original `src/boundary/shared/core/` directory empty (or only non-core files remain)
- [ ] Verify with: `ls -la src/boundary/shared/core/`

### 2.3 Move Test Files

```bash
# Move test files
mv test/boundary/shared/core/validation/ libs/core/test/boundary/core/validation/
mv test/boundary/shared/core/utils/ libs/core/test/boundary/core/utils/
# ... etc for all test files
```

**Checklist**:
- [ ] All test files moved
- [ ] Test directory structure mirrors source structure

### 2.4 Move Resource Files (if any)

```bash
# Check for resources
ls resources/boundary/shared/ 2>/dev/null

# Move if exists
mv resources/boundary/shared/* libs/core/resources/ 2>/dev/null || echo "No resources"
```

**Checklist**:
- [ ] Resources moved (or confirmed none exist)

---

## Step 3: Update Namespace Declarations (Day 1-2)

### 3.1 Update Namespaces in Moved Files

**For each file in `libs/core/src/boundary/core/`**:

```clojure
;; OLD
(ns boundary.shared.core.validation.engine ...)

;; NEW
(ns boundary.core.validation.engine ...)
```

**Tools to use**:
```bash
# Find all namespace declarations
rg "^\(ns boundary\.shared\.core\." libs/core/src/ -l

# For each file, use Edit tool to update namespace
```

**Checklist**:
- [ ] All `ns` declarations updated in source files
- [ ] All `ns` declarations updated in test files
- [ ] Verify: `rg "boundary\.shared" libs/core/` returns zero matches

### 3.2 Update Require Statements in Moved Files

**Within moved files, update internal requires**:

```clojure
;; OLD
(:require [boundary.shared.core.validation.engine :as engine])

;; NEW
(:require [boundary.core.validation.engine :as engine])
```

**Checklist**:
- [ ] All internal `:require` statements updated
- [ ] Run: `clj-paren-repair libs/core/src/**/*.clj` to fix any syntax issues

### 3.3 Update Test Namespaces and Requires

**In test files**:

```clojure
;; OLD
(ns boundary.shared.core.validation.engine-test
  (:require [boundary.shared.core.validation.engine :as sut]))

;; NEW
(ns boundary.core.validation.engine-test
  (:require [boundary.core.validation.engine :as sut]))
```

**Checklist**:
- [ ] All test `ns` declarations updated
- [ ] All test `:require` statements updated
- [ ] Verify: `rg "boundary\.shared" libs/core/test/` returns zero matches

---

## Step 4: Update Main Codebase References (Day 2)

### 4.1 Find All References in Main Codebase

```bash
# Get complete list of files referencing boundary.shared
rg "boundary\.shared\.core" src/ test/ -l > /tmp/files-to-update.txt

# Review count
wc -l /tmp/files-to-update.txt
```

**Expected**: 50-200 files (estimate)

### 4.2 Update References in Source Files

**For each file in main codebase**:

```clojure
;; OLD
(:require [boundary.shared.core.validation.engine :as validation])

;; NEW
(:require [boundary.core.validation.engine :as validation])
```

**Strategy**:
1. Sort files by module (user, admin, platform, etc.)
2. Update module by module
3. Test after each module

**Checklist**:
- [ ] User module updated
- [ ] Admin module updated
- [ ] Platform module updated
- [ ] Storage module updated
- [ ] External module updated
- [ ] Scaffolder module updated
- [ ] Verify: `rg "boundary\.shared\.core" src/` returns zero matches

### 4.3 Update References in Test Files

```bash
# Update test file references
rg "boundary\.shared\.core" test/ -l
```

**Checklist**:
- [ ] All test file references updated
- [ ] Verify: `rg "boundary\.shared\.core" test/` returns zero matches

### 4.4 Update deps.edn References

**Update main `deps.edn`** (now that core is extracted):

```clojure
;; Add to :deps
boundary/core {:local/root "libs/core"}
```

**Checklist**:
- [ ] Main `deps.edn` includes `boundary/core` dependency
- [ ] Other library deps.edn files updated (observability, platform, etc.)

---

## Step 5: Verify and Test (Day 2-3)

### 5.1 Fix Syntax Errors

```bash
# Fix any parenthesis issues
find libs/core/src -name "*.clj" -exec clj-paren-repair {} \;

# Lint
clojure -M:clj-kondo --lint libs/core/src libs/core/test
```

**Checklist**:
- [ ] No syntax errors
- [ ] No linting errors (or documented as acceptable)

### 5.2 Test Core Library Independently

```bash
# Test core library in isolation
clojure -M:test-core

# Or via build tool
clojure -T:build test-lib :lib core
```

**Expected**: All core tests pass (>80% coverage)

**Checklist**:
- [ ] All unit tests pass
- [ ] All integration tests pass (if any)
- [ ] Test coverage meets threshold

### 5.3 Test Main Codebase

```bash
# Run user module tests (depends on core)
clojure -M:test:db/h2 --focus-meta :user

# Run platform tests
clojure -M:test:db/h2 --focus-meta :platform

# Run all tests
clojure -M:test:db/h2
```

**Expected**: All existing tests still pass

**Checklist**:
- [ ] User module tests pass
- [ ] Platform module tests pass
- [ ] Admin module tests pass
- [ ] All other module tests pass
- [ ] Full test suite passes

### 5.4 Test REPL Integration

```bash
# Start REPL
clojure -M:dev:repl-clj
```

**In REPL**:
```clojure
;; Test loading core namespace
(require '[boundary.core.validation.engine :as validation])

;; Test validation functions work
(validation/validate [:string] "test")

;; Test system startup
(require '[integrant.repl :as ig-repl])
(ig-repl/go)
```

**Checklist**:
- [ ] Core namespaces load without error
- [ ] Validation functions work
- [ ] System starts successfully
- [ ] No warnings about missing namespaces

### 5.5 Build Core JAR

```bash
# Build core library JAR
clojure -T:build jar :lib core

# Verify JAR created
ls -lh libs/core/target/core-*.jar
```

**Checklist**:
- [ ] JAR builds successfully
- [ ] JAR contains expected classes
- [ ] JAR size reasonable (~50-200KB expected)

---

## Step 6: Update Documentation (Day 3)

### 6.1 Update README Files

**Update** `libs/core/README.md`:
- [ ] Add migration notes (namespace changes)
- [ ] Update examples to use new namespaces
- [ ] Document what was moved

**Update** main `README.md`:
- [ ] Reference new library structure
- [ ] Update dependency examples

### 6.2 Update API Documentation

**If API docs exist**:
- [ ] Update namespace references
- [ ] Regenerate API docs (if automated)

### 6.3 Update Migration Guide

**Create** `docs/MIGRATION_SHARED_TO_CORE.md`:
- [ ] Document all namespace changes
- [ ] Provide search/replace patterns
- [ ] List breaking changes

---

## Step 7: Code Review and Cleanup (Day 3)

### 7.1 Self-Review

```bash
# Review all changes
git diff main --stat
git diff main src/boundary/shared/ libs/core/
```

**Checklist**:
- [ ] No unexpected file deletions
- [ ] No code accidentally left in old location
- [ ] All moved files have updated namespaces
- [ ] No debugging code left in (println, etc.)

### 7.2 Run Full Quality Check

```bash
# Lint entire codebase
clojure -M:clj-kondo --lint src test libs

# Run all tests
clojure -T:build test-all

# Check for references to old namespaces
rg "boundary\.shared\.core" src/ test/ libs/

# Build all jars (smoke test)
clojure -T:build jar-all
```

**Checklist**:
- [ ] No linting errors
- [ ] All tests pass
- [ ] No old namespace references remain
- [ ] All JARs build successfully

### 7.3 Update Changelog

**Update** `CHANGELOG.md`:

```markdown
## [Unreleased]

### Added
- New `boundary/core` library with validation, utils, and interceptors

### Changed
- **BREAKING**: Renamed `boundary.shared.core` → `boundary.core`
  - See [MIGRATION_SHARED_TO_CORE.md](docs/MIGRATION_SHARED_TO_CORE.md)

### Migration Guide
- Replace all `boundary.shared.core` → `boundary.core` in your requires
```

**Checklist**:
- [ ] CHANGELOG updated
- [ ] Breaking changes documented
- [ ] Migration path clear

---

## Step 8: Commit and Push (Day 3)

### 8.1 Stage Changes

```bash
# Check status
git status

# Stage all changes
git add libs/core/
git add src/boundary/shared/  # If removing files
git add test/boundary/shared/  # If removing files
git add deps.edn
git add docs/
git add CHANGELOG.md
```

**Checklist**:
- [ ] All new files staged
- [ ] All modified files staged
- [ ] No unintended files staged

### 8.2 Commit

```bash
git commit -m "Phase 1: Extract boundary/core library

- Move boundary.shared.core → boundary/core
- Update namespace declarations across codebase
- Migrate all validation, utils, and interceptor code
- Update all tests to use new namespaces
- Build JAR for core library
- Update documentation and migration guide

Breaking Changes:
- boundary.shared.core.* → boundary.core.*

See docs/MIGRATION_SHARED_TO_CORE.md for details."
```

**Checklist**:
- [ ] Commit message clear and complete
- [ ] Breaking changes documented in message
- [ ] Reference to migration guide included

### 8.3 Push

```bash
# Push to remote
git push origin feat/split-phase1
```

**Checklist**:
- [ ] Pushed successfully
- [ ] CI pipeline triggered
- [ ] CI pipeline passing

---

## Step 9: Create Pull Request

### 9.1 PR Details

**Title**: `Phase 1: Extract boundary/core Library`

**Description**:

```markdown
## Summary

Extracts the `boundary.shared.core` namespace into an independent `boundary/core` library as part of the library split initiative.

## Changes

- **New Library**: `boundary/core` (libs/core/)
  - Validation engine and utilities
  - Core utilities (case conversion, hashing, etc.)
  - Interceptor system
  - Protocol definitions

- **Namespace Migration**: `boundary.shared.core.*` → `boundary.core.*`
  - Updated 150+ files across codebase
  - All tests updated and passing

- **Documentation**:
  - Migration guide: `docs/MIGRATION_SHARED_TO_CORE.md`
  - Updated library README
  - CHANGELOG updated

## Breaking Changes

⚠️ **Namespace rename**: `boundary.shared.core.*` → `boundary.core.*`

See [Migration Guide](docs/MIGRATION_SHARED_TO_CORE.md) for upgrade instructions.

## Testing

- ✅ Core library tests: `clojure -M:test-core` (all passing)
- ✅ Full test suite: `clojure -M:test:db/h2` (all passing)
- ✅ Core JAR builds: `clojure -T:build jar :lib core`
- ✅ Linting: `clojure -M:clj-kondo --lint libs/core` (no errors)

## Checklist

- [x] All files moved to libs/core/
- [x] All namespaces updated
- [x] All references updated in main codebase
- [x] All tests passing
- [x] Documentation updated
- [x] Migration guide created
- [x] CHANGELOG updated
- [x] JAR builds successfully

## Related

- Implements Phase 1 of [Library Split Implementation Plan](docs/LIBRARY_SPLIT_IMPLEMENTATION_PLAN.md)
- See [ADR-001: Library Split](docs/adr/ADR-001-library-split.md) for context
- Previous: Phase 0 (Monorepo Infrastructure)
- Next: Phase 2 (Extract observability)

## Reviewers

@thijs-creemers
```

**Checklist**:
- [ ] PR created with title and description
- [ ] Labels added: `library-split`, `breaking-change`, `phase-1`
- [ ] Reviewers assigned
- [ ] Linked to project board (if exists)

---

## Post-Merge Tasks

### After PR Merged

```bash
# Switch to main
git checkout main

# Pull latest
git pull origin main

# Delete phase branch
git branch -d feat/split-phase1

# Verify system still works
clojure -M:dev:repl-clj
# In REPL: (ig-repl/go)
```

**Checklist**:
- [ ] Merged to main
- [ ] Branch deleted locally
- [ ] System verified working
- [ ] Ready for Phase 2

---

## Rollback Plan (If Issues Found)

If critical issues discovered:

```bash
# Revert the merge commit
git revert <merge-commit-sha>

# Or hard reset (if not pushed)
git reset --hard HEAD~1

# Document issue
echo "Phase 1 rollback: <reason>" >> docs/ROLLBACK_LOG.md
```

**Checklist**:
- [ ] Issue documented
- [ ] Rollback committed
- [ ] Team notified
- [ ] Issue analysis scheduled

---

## Success Criteria

- [ ] **Independence**: Core library has zero dependencies on other boundary namespaces
- [ ] **Testing**: All tests pass (>80% coverage maintained)
- [ ] **Building**: JAR builds successfully
- [ ] **Integration**: Main codebase works with extracted library
- [ ] **Documentation**: Migration guide complete and accurate
- [ ] **No Regressions**: All existing functionality preserved

---

## Time Tracking

| Task | Estimated | Actual | Notes |
|------|-----------|--------|-------|
| Analyze structure | 4h | - | |
| Move files | 2h | - | |
| Update namespaces (core) | 4h | - | |
| Update references (main) | 8h | - | |
| Testing & fixes | 6h | - | |
| Documentation | 2h | - | |
| Code review | 2h | - | |
| PR & merge | 2h | - | |
| **Total** | **30h (3 days)** | - | |

---

## Notes & Issues Log

**Use this section to document any issues encountered**:

```
[Date] [Issue] [Resolution]
[2026-01-19] Example issue: Found circular dependency in validation module
             Resolution: Moved shared types to protocols namespace
```

---

## Appendix: Automated Scripts

### Script 1: Find All References

```bash
#!/bin/bash
# find-shared-references.sh
echo "Finding all boundary.shared references..."
rg "boundary\.shared" src/ test/ --type clojure -l | sort > /tmp/shared-refs.txt
echo "Found $(wc -l < /tmp/shared-refs.txt) files"
cat /tmp/shared-refs.txt
```

### Script 2: Update Namespace in File

```bash
#!/bin/bash
# update-namespace.sh
# Usage: ./update-namespace.sh <file>
FILE=$1
sed -i '' 's/boundary\.shared\.core/boundary.core/g' "$FILE"
echo "Updated: $FILE"
```

### Script 3: Verify No Old References

```bash
#!/bin/bash
# verify-migration.sh
echo "Checking for old namespace references..."
if rg "boundary\.shared\.core" libs/core/ src/ test/ --type clojure; then
  echo "❌ Found old namespace references!"
  exit 1
else
  echo "✅ No old namespace references found"
  exit 0
fi
```

---

**Checklist Version**: 1.0  
**Last Updated**: 2026-01-18  
**Phase Status**: 🟡 Ready to Start  
**Next Phase**: Phase 2 - Extract observability
