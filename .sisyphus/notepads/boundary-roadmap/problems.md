# Problems - Boundary Roadmap

## [2026-01-26T19:27] Session Start
Unresolved blockers requiring attention.


## 2026-01-27 - Phase 2.1 - Filesystem Dependency Issue

**Problem**: Subagent incorrectly added filesystem protocol abstractions to platform library when implementing `boundary new` command.

**Root Cause**: 
- Subagent created `boundary.platform.shell.adapters.filesystem.protocols` and `.core`
- Scaffolder library doesn't depend on platform library
- These abstractions were unnecessary - `clojure.java.io` is sufficient for simple file operations

**Impact**:
- Compilation errors blocked all tests
- Multiple files needed fixing: `service.clj`, `cli_entry.clj`, `service_test.clj`

**Fix Applied**:
1. Removed all `fs-ports` references from `service.clj`
2. Replaced with direct `clojure.java.io` usage
3. Updated `create-scaffolder-service` to not require file-system parameter
4. Removed filesystem files from platform library
5. Fixed test file to use dry-run mode instead of filesystem checks
6. Fixed `cli_entry.clj` to not create filesystem adapter

**Lesson Learned**: 
- Always verify dependency graph before adding `require` statements
- Simple operations don't need protocol abstractions
- Scaffolder is a dev tool - direct file I/O is acceptable here
- When fixing compilation errors, check ALL files that might import the broken namespace

**Time Lost**: ~2 hours of fixing (across multiple files and test adjustments)

**Prevention**: 
- Subagents should check `deps.edn` before adding cross-library dependencies
- Consider adding linting rule to detect cross-library requires not in deps
