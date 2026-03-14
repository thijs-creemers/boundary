# Cross-Platform Testing Guide

**Purpose**: Manual testing checklist for validating Boundary Starter on Linux and Windows.  
**Date**: 2026-03-14  
**Sprint**: 4 (Day 16)

---

## Overview

This guide provides a comprehensive checklist for testing Boundary Starter on different platforms. While the code is written in Clojure (which is cross-platform), platform differences can appear in:
- File path handling (Windows backslashes vs Unix forward slashes)
- Environment variable syntax
- Line endings (CRLF vs LF)
- Shell behavior (PowerShell, CMD, Bash)
- Java installation paths

---

## Prerequisites by Platform

### macOS (Reference Platform - Already Tested)
```bash
# Babashka
brew install borkdude/brew/babashka

# Java 17+
brew install openjdk@17

# Verify
bb --version  # Should show 1.x.x
java -version # Should show 17+
```

### Linux (Debian/Ubuntu)
```bash
# Babashka
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)

# Java 17+
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk

# Verify
bb --version
java -version
echo $SHELL  # Note which shell (bash/zsh/fish)
```

### Linux (RHEL/Fedora/CentOS)
```bash
# Babashka
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)

# Java 17+
sudo dnf install -y java-17-openjdk java-17-openjdk-devel

# Verify
bb --version
java -version
```

### Windows
```powershell
# Install Scoop first (if not installed)
Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
irm get.scoop.sh | iex

# Babashka
scoop install babashka

# Java 17+
scoop bucket add java
scoop install openjdk17

# Verify
bb --version
java -version
echo $env:SHELL  # Note shell (PowerShell/CMD/Git Bash)
```

---

## Test Suite 1: Basic Functionality

### Test 1.1: Help Display
**Platforms**: Linux, Windows  
**Expected**: Help text displays correctly, no encoding issues

```bash
# All platforms
bb setup --help
```

**Success Criteria**:
- [ ] Help text displays (not garbled characters)
- [ ] ANSI colors render correctly (or degrade gracefully)
- [ ] Box drawing characters display (or fall back to ASCII)
- [ ] No error messages

**Platform Notes**:
- Windows CMD: May not support ANSI colors (use PowerShell or Windows Terminal)
- Windows Git Bash: Should support ANSI colors
- Linux: Should support ANSI colors in most terminals

---

### Test 1.2: Minimal Template (Interactive)
**Platforms**: Linux, Windows  
**Expected**: Interactive wizard works, project generates successfully

```bash
bb setup
# Select: 1 (minimal)
# Name: test-minimal
# Database: 1 (SQLite)
# Directory: /tmp/test-minimal (Linux) or C:\Temp\test-minimal (Windows)
# Confirm: y
```

**Success Criteria**:
- [ ] Interactive prompts display correctly
- [ ] Input accepted without issues
- [ ] File paths resolved correctly (forward/backslash)
- [ ] Project generated (9 files, 7 directories)
- [ ] Success message displays
- [ ] JWT_SECRET shown in output

**Platform-Specific Checks**:

**Linux**:
```bash
ls -la /tmp/test-minimal
file /tmp/test-minimal/deps.edn  # Should show "ASCII text"
cat /tmp/test-minimal/.gitignore | file -  # Check line endings (LF)
```

**Windows (PowerShell)**:
```powershell
Get-ChildItem C:\Temp\test-minimal
Get-Content C:\Temp\test-minimal\deps.edn -Raw | Format-Hex | Select-Object -First 1  # Check encoding
```

---

### Test 1.3: Minimal Template (Non-Interactive)
**Platforms**: Linux, Windows  
**Expected**: CLI args work, paths resolve correctly

**Linux**:
```bash
bb setup --template minimal --name cli-test --output /tmp --db sqlite
ls -la /tmp/cli-test
```

**Windows (PowerShell)**:
```powershell
bb setup --template minimal --name cli-test --output C:\Temp --db sqlite
Get-ChildItem C:\Temp\cli-test
```

**Windows (CMD)**:
```cmd
bb setup --template minimal --name cli-test --output C:\Temp --db sqlite
dir C:\Temp\cli-test
```

**Success Criteria**:
- [ ] Command executes without errors
- [ ] Project created in correct directory
- [ ] Paths use platform-appropriate separators
- [ ] All files have correct line endings (LF on Linux, CRLF on Windows)

---

## Test Suite 2: Custom Template Wizard

### Test 2.1: Custom Template Creation
**Platforms**: Linux, Windows  
**Expected**: Interactive library selection works

```bash
bb setup
# Select: 6 (custom template)
# Select: 1 (Create new custom template)
# Libraries: Select core, user, admin (1, 4, 5)
# Done: 0
# Confirm: y
# Save: y
# Name: my-custom
```

**Success Criteria**:
- [ ] Library selection menu displays
- [ ] Multi-selection works (space-separated input)
- [ ] Dependency resolution succeeds
- [ ] Template saved to `saved-templates/my-custom.edn`
- [ ] Template file has correct format (EDN)

**Platform-Specific Checks**:

**Linux**:
```bash
ls -la saved-templates/
cat saved-templates/my-custom.edn
clojure -M -e "(require 'clojure.edn) (println (clojure.edn/read-string (slurp \"saved-templates/my-custom.edn\")))"
```

**Windows**:
```powershell
Get-ChildItem saved-templates\
Get-Content saved-templates\my-custom.edn
# EDN parsing requires Clojure CLI
```

---

### Test 2.2: Load Saved Template
**Platforms**: Linux, Windows  
**Expected**: Saved templates load and generate projects

```bash
bb setup
# Select: 6 (custom template)
# Select: 1 (my-custom)
# Name: custom-project
# Database: 1 (SQLite)
# Directory: /tmp/custom-project (Linux) or C:\Temp\custom-project (Windows)
# Confirm: y
```

**Success Criteria**:
- [ ] Saved template appears in menu
- [ ] Template loads successfully
- [ ] Project generates with selected libraries
- [ ] deps.edn includes only selected Boundary libs

---

### Test 2.3: Edit/Duplicate/Rename Operations
**Platforms**: Linux, Windows  
**Expected**: Template CRUD operations work

```bash
bb setup
# Select: 6 (custom template)
# Select: 4 (Edit existing template)
# Select: 1 (my-custom)
# Libraries: Add storage (9), remove admin (5)
# Done: 0
# Confirm: y
```

**Success Criteria**:
- [ ] Edit menu displays
- [ ] Library modification works
- [ ] Template file updated
- [ ] Metadata preserved (created-at, updated-at)

**Duplicate Test**:
```bash
# Select: 5 (Duplicate existing template)
# Source: my-custom
# New name: my-custom-v2
```

**Rename Test**:
```bash
# Select: 6 (Rename existing template)
# Template: my-custom-v2
# New name: production-template
```

---

## Test Suite 3: Environment Variables

### Test 3.1: BOUNDARY_REPO_PATH (Custom Path)
**Platforms**: Linux, Windows  
**Expected**: Custom repo path respected

**Linux/macOS**:
```bash
export BOUNDARY_REPO_PATH=/path/to/custom/boundary
bb setup --template minimal --name env-test --output /tmp --db sqlite
```

**Windows (PowerShell)**:
```powershell
$env:BOUNDARY_REPO_PATH = "C:\path\to\custom\boundary"
bb setup --template minimal --name env-test --output C:\Temp --db sqlite
```

**Windows (CMD)**:
```cmd
set BOUNDARY_REPO_PATH=C:\path\to\custom\boundary
bb setup --template minimal --name env-test --output C:\Temp --db sqlite
```

**Success Criteria**:
- [ ] Environment variable recognized
- [ ] Git SHA fetched from custom path
- [ ] Fallback works if path doesn't exist

---

### Test 3.2: Auto-Detection (Parent Directory)
**Platforms**: Linux, Windows  
**Expected**: Auto-detects parent directory if `starter` is in `boundary/starter`

```bash
# Assume boundary-starter cloned inside boundary repo
cd /path/to/boundary/starter  # Linux
cd C:\path\to\boundary\starter  # Windows

bb setup --template minimal --name autodetect-test --output /tmp --db sqlite
```

**Success Criteria**:
- [ ] Detects `../` as Boundary repo
- [ ] Verifies `../libs/` and `../deps.edn` exist
- [ ] Uses parent directory SHA

---

### Test 3.3: Auto-Detection (Sibling Directory)
**Platforms**: Linux, Windows  
**Expected**: Auto-detects sibling directory if `boundary` is next to `starter`

```bash
# Assume directory structure:
# parent/
#   boundary/
#   boundary-starter/

cd /path/to/boundary-starter  # Linux
cd C:\path\to\boundary-starter  # Windows

bb setup --template minimal --name sibling-test --output /tmp --db sqlite
```

**Success Criteria**:
- [ ] Detects `../boundary/` as Boundary repo
- [ ] Uses sibling directory SHA

---

## Test Suite 4: File System Edge Cases

### Test 4.1: Spaces in Paths
**Platforms**: Linux, Windows  
**Expected**: Handles spaces in directory names

**Linux**:
```bash
mkdir -p "/tmp/my project"
bb setup --template minimal --name space-test --output "/tmp/my project" --db sqlite
ls -la "/tmp/my project/space-test"
```

**Windows**:
```powershell
New-Item -ItemType Directory -Path "C:\Temp\My Project" -Force
bb setup --template minimal --name space-test --output "C:\Temp\My Project" --db sqlite
Get-ChildItem "C:\Temp\My Project\space-test"
```

**Success Criteria**:
- [ ] Paths with spaces handled correctly
- [ ] No "file not found" errors
- [ ] Project generates successfully

---

### Test 4.2: Long Paths (Windows Only)
**Platform**: Windows  
**Expected**: Handles long paths (Windows MAX_PATH = 260 chars)

```powershell
# Create deep directory structure
$longPath = "C:\Temp\" + ("a" * 200)
New-Item -ItemType Directory -Path $longPath -Force
bb setup --template minimal --name long-test --output $longPath --db sqlite
```

**Success Criteria**:
- [ ] Project generates without "path too long" errors
- [ ] Files created successfully

**Note**: May require Windows 10+ with long path support enabled:
```powershell
# Enable long paths (run as Administrator)
New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" -Name "LongPathsEnabled" -Value 1 -PropertyType DWORD -Force
```

---

### Test 4.3: Special Characters in Project Names
**Platforms**: Linux, Windows  
**Expected**: Rejects invalid characters, accepts kebab-case

```bash
# Should FAIL (invalid chars)
bb setup --template minimal --name "my_project" --db sqlite
bb setup --template minimal --name "MyProject" --db sqlite
bb setup --template minimal --name "my.project" --db sqlite

# Should SUCCEED (kebab-case)
bb setup --template minimal --name "my-project-123" --db sqlite
```

**Success Criteria**:
- [ ] Validation error for invalid names
- [ ] Clear error message explaining kebab-case requirement
- [ ] Accepts valid kebab-case names

---

## Test Suite 5: Database Drivers

### Test 5.1: SQLite (Default)
**Platforms**: Linux, Windows  
**Expected**: SQLite driver included in deps.edn

```bash
bb setup --template minimal --name sqlite-test --output /tmp --db sqlite
grep -i sqlite /tmp/sqlite-test/deps.edn  # Linux
Select-String -Pattern "sqlite" C:\Temp\sqlite-test\deps.edn  # Windows
```

**Success Criteria**:
- [ ] `org.xerial/sqlite-jdbc` in deps.edn
- [ ] No PostgreSQL driver present

---

### Test 5.2: PostgreSQL
**Platforms**: Linux, Windows  
**Expected**: PostgreSQL driver included

```bash
bb setup --template minimal --name postgres-test --output /tmp --db postgres
grep -i postgres /tmp/postgres-test/deps.edn
```

**Success Criteria**:
- [ ] `org.postgresql/postgresql` in deps.edn
- [ ] No SQLite driver present

---

### Test 5.3: Both Databases
**Platforms**: Linux, Windows  
**Expected**: Both drivers included

```bash
bb setup --template minimal --name both-test --output /tmp --db both
grep -E "sqlite|postgres" /tmp/both-test/deps.edn
```

**Success Criteria**:
- [ ] Both drivers in deps.edn
- [ ] Config allows switching via environment variable

---

## Test Suite 6: Template Files

### Test 6.1: All Templates Generate
**Platforms**: Linux, Windows  
**Expected**: All 5 templates + custom work

```bash
# Minimal
bb setup --template minimal --name t1 --output /tmp --db sqlite --yes

# API-Only
bb setup --template api-only --name t2 --output /tmp --db sqlite --yes

# Microservice
bb setup --template microservice --name t3 --output /tmp --db sqlite --yes

# Web-App
bb setup --template web-app --name t4 --output /tmp --db sqlite --yes

# SaaS
bb setup --template saas --name t5 --output /tmp --db postgres --yes
```

**Success Criteria**:
- [ ] All 5 templates generate without errors
- [ ] Each has correct number of Boundary libs
- [ ] deps.edn size increases with template complexity

---

### Test 6.2: Generated Files Are Valid
**Platforms**: Linux, Windows  
**Expected**: All generated files are syntactically valid

**Linux**:
```bash
# Test EDN files
clojure -M -e "(require 'clojure.edn) (clojure.edn/read-string (slurp \"/tmp/t1/deps.edn\"))"
clojure -M -e "(require 'clojure.edn) (clojure.edn/read-string (slurp \"/tmp/t1/resources/conf/dev/config.edn\"))"

# Test Clojure files
clojure -M -e "(require 'clojure.core) (load-file \"/tmp/t1/src/boundary/app.clj\")"
```

**Windows**: Similar, but adjust paths to `C:\Temp\...`

**Success Criteria**:
- [ ] All EDN files parse without errors
- [ ] All Clojure files load without syntax errors
- [ ] No missing closing parens/brackets

---

## Test Suite 7: Automated Tests

### Test 7.1: Unit Tests
**Platforms**: Linux, Windows  
**Expected**: All unit tests pass

```bash
cd /path/to/boundary/starter
bb -e "(load-file \"test/helpers/helpers_test.clj\") (clojure.test/run-tests 'helpers-test)"
```

**Success Criteria**:
- [ ] 18 tests pass
- [ ] 132 assertions pass
- [ ] 0 failures, 0 errors

---

### Test 7.2: Integration Tests
**Platforms**: Linux, Windows  
**Expected**: All integration tests pass

```bash
bb -e "(load-file \"test/integration/integration_test.clj\") (clojure.test/run-tests 'integration-test)"
```

**Success Criteria**:
- [ ] 6 tests pass
- [ ] 93 assertions pass
- [ ] 0 failures, 0 errors

---

### Test 7.3: Custom Template Tests
**Platforms**: Linux, Windows  
**Expected**: All custom template tests pass

```bash
bb -e "(load-file \"test/custom_templates/custom_template_test.clj\") (clojure.test/run-tests 'custom-template-test)"
bb -e "(load-file \"test/custom_templates/integration_test.clj\") (clojure.test/run-tests 'integration-test)"
bb -e "(load-file \"test/custom_templates/metadata_config_test.clj\") (clojure.test/run-tests 'metadata-config-test)"
bb -e "(load-file \"test/custom_templates/editing_test.clj\") (clojure.test/run-tests 'editing-test)"
```

**Success Criteria**:
- [ ] 52 total tests pass
- [ ] 272 total assertions pass
- [ ] 0 failures, 0 errors

---

## Test Suite 8: Line Endings

### Test 8.1: Generated Files Have Correct Line Endings
**Platforms**: Linux, Windows  
**Expected**: LF on Unix, CRLF on Windows (or normalize to LF)

**Linux**:
```bash
file /tmp/t1/deps.edn  # Should show "ASCII text"
file /tmp/t1/README.md # Should show "ASCII text"
```

**Windows (PowerShell)**:
```powershell
# Check for CRLF (0D 0A) vs LF (0A)
$content = Get-Content C:\Temp\t1\deps.edn -Raw
$content.Contains("`r`n")  # True = CRLF, False = LF
```

**Success Criteria**:
- [ ] Files use platform-appropriate line endings
- [ ] .gitignore includes `* text=auto` (normalize on commit)

---

## Platform-Specific Issues (Known)

### Windows
1. **Path separators**: Babashka handles `\` vs `/` automatically via `io/file`
2. **ANSI colors**: Require Windows Terminal or PowerShell 5.1+ (CMD may not support)
3. **Box drawing**: May fall back to ASCII `+`, `-`, `|` in CMD
4. **Long paths**: Requires Windows 10+ with long path support enabled
5. **Case sensitivity**: File system is case-insensitive (may cause issues with `kebab-case` validation)

### Linux
1. **Line endings**: Git may convert CRLF → LF on checkout (expected behavior)
2. **Permissions**: Generated scripts should be executable (`chmod +x`)
3. **Shell differences**: Bash vs Zsh vs Fish (all should work with Babashka)

### macOS (Reference)
1. **File system**: Case-insensitive by default (like Windows)
2. **Line endings**: LF (like Linux)
3. **Homebrew paths**: Java may be in `/usr/local/opt/` or `/opt/homebrew/`

---

## Reporting Issues

If you encounter platform-specific issues, please document:

1. **Platform**: OS name + version (e.g., "Ubuntu 22.04", "Windows 11")
2. **Shell**: Which shell you used (Bash, PowerShell, CMD, Zsh)
3. **Java version**: Output of `java -version`
4. **Babashka version**: Output of `bb --version`
5. **Error message**: Full error output (copy-paste)
6. **Command**: Exact command that failed
7. **Expected**: What should have happened
8. **Actual**: What actually happened

**Report to**: Create issue in `boundary-starter` GitHub repository with label `platform-specific`.

---

## Summary Checklist

### Linux Testing
- [ ] Test Suite 1: Basic Functionality (3 tests)
- [ ] Test Suite 2: Custom Template Wizard (3 tests)
- [ ] Test Suite 3: Environment Variables (3 tests)
- [ ] Test Suite 4: File System Edge Cases (3 tests)
- [ ] Test Suite 5: Database Drivers (3 tests)
- [ ] Test Suite 6: Template Files (2 tests)
- [ ] Test Suite 7: Automated Tests (3 test suites)
- [ ] Test Suite 8: Line Endings (1 test)

**Total**: 21 test scenarios

### Windows Testing
- [ ] Test Suite 1: Basic Functionality (3 tests)
- [ ] Test Suite 2: Custom Template Wizard (3 tests)
- [ ] Test Suite 3: Environment Variables (3 tests)
- [ ] Test Suite 4: File System Edge Cases (3 tests)
- [ ] Test Suite 5: Database Drivers (3 tests)
- [ ] Test Suite 6: Template Files (2 tests)
- [ ] Test Suite 7: Automated Tests (3 test suites)
- [ ] Test Suite 8: Line Endings (1 test)

**Total**: 21 test scenarios

---

**Created**: 2026-03-14  
**Sprint**: 4 (Day 16)  
**Status**: Ready for manual testing  
**Next**: Collect test results, document platform-specific issues, create fixes
