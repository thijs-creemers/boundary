# Phase 3 Task 5: Format Standardization Issues

**Status**: Issues Identified  
**Date**: 2026-02-15  
**Files Analyzed**: 18 migrated files

---

## Summary of Issues

### Critical Issues (Must Fix)

1. **Unlabeled Code Blocks**: 350+ code blocks missing language tags
   - Affects: All 14 markdown files
   - Impact: Syntax highlighting disabled, poor readability
   - Fix: Add language tags (```bash, ```clojure, ```json, etc.)

2. **Title Case Headings**: 72+ headings using Title Case instead of sentence case
   - Affects: 10 of 14 markdown files
   - Impact: Inconsistent style across documentation
   - Fix: Convert to sentence case (e.g., "Getting Started Guide" → "Getting started guide")

### Minor Issues (Should Fix)

3. **MFA Terminology**: Inconsistent references (Multi-Factor, multi-factor, 2FA)
   - Affects: authentication.md, mfa.md
   - Impact: Minor terminology inconsistency
   - Fix: Standardize to "Multi-Factor Authentication (MFA)" on first use, "MFA" thereafter

---

## Detailed Findings

### Getting Started (2 files)

#### quickstart.md
- ⚠️ **9 Title Case headings** (e.g., "Prerequisites", "Quick Start", "Step-by-Step Setup")
- ⚠️ **36 unlabeled code blocks**
- ✅ No terminology issues

#### tutorial.md
- ⚠️ **64 unlabeled code blocks**
- ✅ No Title Case issues
- ✅ No terminology issues

---

### Guides (8 files)

#### operations.adoc ✅
- ✅ Clean (AsciiDoc format already consistent)

#### ide-setup.md
- ⚠️ **5 Title Case headings**
- ⚠️ **19 unlabeled code blocks**
- ✅ No terminology issues

#### authentication.md
- ⚠️ **14 unlabeled code blocks**
- ℹ️ **4 MFA terminology variations**
- ✅ No Title Case issues

#### database-setup.md
- ⚠️ **5 Title Case headings**
- ⚠️ **12 unlabeled code blocks**
- ✅ No terminology issues

#### testing.md
- ⚠️ **12 Title Case headings**
- ⚠️ **25 unlabeled code blocks**
- ✅ No terminology issues

#### admin-testing.md
- ⚠️ **6 Title Case headings**
- ⚠️ **34 unlabeled code blocks**
- ✅ No terminology issues

#### security-setup.md
- ⚠️ **11 Title Case headings**
- ⚠️ **9 unlabeled code blocks**
- ✅ No terminology issues

#### tenant-migration.md
- ⚠️ **28 unlabeled code blocks**
- ✅ No Title Case issues
- ✅ No terminology issues

---

### API (3 files)

#### search.md
- ⚠️ **5 Title Case headings**
- ⚠️ **31 unlabeled code blocks**
- ✅ No terminology issues

#### mfa.md
- ⚠️ **22 unlabeled code blocks**
- ℹ️ **3 MFA terminology variations**
- ✅ No Title Case issues

#### pagination.md
- ⚠️ **6 Title Case headings**
- ⚠️ **33 unlabeled code blocks**
- ✅ No terminology issues

---

### Reference (1 file)

#### publishing.md
- ⚠️ **13 Title Case headings**
- ⚠️ **23 unlabeled code blocks**
- ✅ No terminology issues

---

## Prioritization

### High Priority (User-Facing Impact)
1. **Code block language tags** (350+ blocks) - Affects syntax highlighting
2. **Title Case headings** (72+ headings) - Inconsistent style

### Medium Priority (Minor Polish)
3. **MFA terminology** (7 variations) - Minor inconsistency

---

## Fix Strategy

### Approach 1: Automated Script (Recommended)
- Create Python/Bash script to:
  - Detect common code block patterns (bash, clojure, json, yaml, xml, sql)
  - Convert Title Case headings to sentence case
  - Standardize MFA terminology
- **Pros**: Fast, consistent, repeatable
- **Cons**: May need manual review for edge cases
- **Time**: 30-60 minutes

### Approach 2: Manual File-by-File (Safer)
- Review and fix each file individually
- **Pros**: Precise control, catches context-specific issues
- **Cons**: Time-consuming, potential for human error
- **Time**: 2-3 hours

### Approach 3: Hybrid (Balanced)
- Use automated script for code block language tags (easy to detect)
- Manually review Title Case headings (context-dependent)
- Manually fix MFA terminology (only 2 files)
- **Pros**: Balance speed and accuracy
- **Cons**: Requires both automation and manual work
- **Time**: 1-1.5 hours

---

## Recommendation

**Use Approach 3 (Hybrid)**:

1. **Phase A (Automated)**: Fix code block language tags with script
   - Time: 15 minutes
   - Risk: Low (easy to verify)

2. **Phase B (Manual)**: Fix Title Case headings file-by-file
   - Time: 45 minutes
   - Risk: Low (visual inspection)

3. **Phase C (Manual)**: Standardize MFA terminology
   - Time: 10 minutes
   - Risk: Minimal (only 2 files)

**Total Time**: ~1 hour

---

## Next Steps

1. Create automated script for code block language tags
2. Run script on all 14 markdown files
3. Verify output with git diff
4. Manually fix Title Case headings (10 files)
5. Manually standardize MFA terminology (2 files)
6. Update PHASE3_PROGRESS_REPORT.md
7. Mark Task 5 complete

---

## Language Tag Mapping (for automation)

```bash
# Command-line examples
export, curl, git, docker, npm, psql, clojure → bash

# Clojure code
(def, (defn, (ns, (require → clojure

# Configuration files
{, [, :key → json (if compact), yaml (if verbose), edn (if Clojure syntax)

# SQL queries
SELECT, INSERT, UPDATE, DELETE, CREATE → sql

# XML/HTML
<, <?xml → xml

# Environment variables
KEY=value → bash

# REPL examples
user=>, boundary.user=> → clojure
```

---

**Last Updated**: 2026-02-15  
**Author**: Phase 3 Documentation Cleanup
