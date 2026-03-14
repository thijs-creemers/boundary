# Troubleshooting Guide

**Purpose**: Common errors, solutions, and debugging strategies for Boundary Starter.  
**Date**: 2026-03-14  
**Sprint**: 4 (Day 17)

---

## Quick Navigation

- [Common Errors](#common-errors)
- [Platform-Specific Issues](#platform-specific-issues)
- [Template Errors](#template-errors)
- [Database Errors](#database-errors)
- [REPL Issues](#repl-issues)
- [Debugging Strategies](#debugging-strategies)
- [Getting Help](#getting-help)

---

## Common Errors

### Error: "Invalid project name"

**Symptom**:
```
❌ Invalid project name: MyProject
Project name must be kebab-case (lowercase, hyphens only)
Examples: my-app, task-manager, crm-platform
```

**Cause**: Project name contains uppercase letters, underscores, or spaces.

**Solution**:
```bash
# ❌ WRONG
bb setup --template minimal --name MyProject
bb setup --template minimal --name my_project
bb setup --template minimal --name "my project"

# ✅ CORRECT
bb setup --template minimal --name my-project
bb setup --template minimal --name myproject
```

**Why kebab-case?**: Clojure namespaces use kebab-case by convention. Your project name becomes a namespace prefix (`boundary.my-project.*`).

---

### Error: "Template not found"

**Symptom**:
```
❌ Template not found: web-application
Available templates: minimal, api-only, microservice, web-app, saas, custom
```

**Cause**: Template name typo or non-existent template.

**Solution**:
```bash
# Check available templates
bb setup --help

# Use exact template name
bb setup --template web-app --name my-app  # Not "web-application"
```

**Valid Templates**:
- `minimal` (3 libraries)
- `api-only` (4 libraries)
- `microservice` (3 libraries)
- `web-app` (5 libraries)
- `saas` (10 libraries)
- `custom` (interactive wizard)

---

### Error: "Directory already exists"

**Symptom**:
```
❌ Directory already exists: /tmp/my-app
Overwrite? (y/n):
```

**Cause**: Output directory contains existing files.

**Solutions**:

**Option 1: Choose different name**
```bash
bb setup --template minimal --name my-app-v2
```

**Option 2: Remove existing directory**
```bash
rm -rf /tmp/my-app
bb setup --template minimal --name my-app
```

**Option 3: Use --yes flag (non-interactive)**
```bash
bb setup --template minimal --name my-app --yes
```

**Warning**: `--yes` overwrites without confirmation. Use carefully.

---

### Error: "JWT_SECRET not set"

**Symptom**:
```
java.lang.IllegalStateException: JWT_SECRET environment variable not set
```

**Cause**: Authentication-enabled templates (api-only, web-app, saas) require `JWT_SECRET` for token signing.

**Solution**:

**macOS/Linux (Bash/Zsh)**:
```bash
export JWT_SECRET="dev-secret-32-chars-minimum-length"
clojure -M:repl-clj
```

**Windows (PowerShell)**:
```powershell
$env:JWT_SECRET="dev-secret-32-chars-minimum-length"
clojure -M:repl-clj
```

**Windows (CMD)**:
```cmd
set JWT_SECRET=dev-secret-32-chars-minimum-length
clojure -M:repl-clj
```

**Permanent Setup (.env file)**:
```bash
# In project directory
cat > .env <<EOF
JWT_SECRET="production-secret-32-chars-minimum"
DATABASE_URL="postgresql://localhost/mydb"
EOF

# Load .env before starting REPL
source .env  # macOS/Linux
```

**Security Note**: Use a strong, random secret in production. Minimum 32 characters.

**Generate Random Secret**:
```bash
# macOS/Linux
openssl rand -base64 32

# Output: e.g., "4K2xZb+Tj9N/qR5sW8vL3..."
```

---

### Error: "Babashka not found"

**Symptom**:
```bash
bb setup
# bash: bb: command not found
```

**Cause**: Babashka not installed or not in PATH.

**Solutions**:

**macOS**:
```bash
brew install borkdude/brew/babashka
bb --version  # Verify
```

**Linux**:
```bash
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
bb --version
```

**Windows (PowerShell)**:
```powershell
scoop install babashka
bb --version
```

**Manual Installation**: See [Babashka Installation](https://github.com/babashka/babashka#installation)

---

### Error: "Could not locate integrant/core.bb" (or similar) when running `bb -e`

**Symptom**:
```bash
bb -e "(load-file \"test/boundary/app_test.clj\") ..."
# Could not locate integrant/core.bb, integrant/core.clj or integrant/core.cljc on classpath.
```

**Cause**: `bb -e/load-file` is running without the generated project's full dependency classpath.

**Solutions**:

**Option 1: Use the project test alias (recommended)**
```bash
# In generated project directory
clojure -M:test
```

**Option 2: If you must use `bb -e`, pass the Clojure classpath explicitly**
```bash
bb --classpath "$(clojure -Spath)" -e "(load-file \"test/boundary/app_test.clj\") ..."
```

**When to use what**:
- Use `bb -e` for starter script tests under `starter/test/helpers` and `starter/test/custom_templates`.
- Use `clojure -M:test` for generated app tests that require project dependencies.

---

### Error: "Java not found"

**Symptom**:
```bash
clojure -M:repl-clj
# clojure: command not found
# OR
# Error: JAVA_HOME is not set
```

**Cause**: Java or Clojure CLI not installed.

**Solutions**:

**macOS**:
```bash
brew install openjdk@17 clojure/tools/clojure
java -version
clojure --version
```

**Linux (Debian/Ubuntu)**:
```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk

# Clojure CLI
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh
```

**Windows (PowerShell)**:
```powershell
scoop bucket add java
scoop install openjdk17

scoop bucket add scoop-clojure
scoop install clojure
```

---

## Platform-Specific Issues

### Windows: Path Too Long Error

**Symptom**:
```
java.io.IOException: The filename, directory name, or volume label syntax is incorrect
```

**Cause**: Windows has a 260-character path limit (MAX_PATH) by default.

**Solutions**:

**Option 1: Enable Long Paths (Windows 10 1607+)**
```powershell
# Run as Administrator
New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" `
  -Name "LongPathsEnabled" -Value 1 -PropertyType DWORD -Force
```

**Option 2: Use Shorter Output Path**
```bash
# ❌ LONG PATH
bb setup --template saas --name my-saas-platform --output "C:\Users\JohnDoe\Documents\Projects\Web\Clojure\Applications"

# ✅ SHORT PATH
bb setup --template saas --name my-saas --output "C:\projects"
```

**Option 3: Use Shorter Project Name**
```bash
bb setup --template saas --name app  # Instead of "my-long-project-name-2024"
```

---

### Windows: ANSI Colors Not Showing

**Symptom**: Progress indicators show escape codes like `[32m` instead of colors.

**Cause**: Old CMD.exe doesn't support ANSI escape codes.

**Solutions**:

**Option 1: Use PowerShell (Recommended)**
```powershell
# PowerShell 5.1+ supports ANSI colors
bb setup
```

**Option 2: Use Windows Terminal (Best)**
```powershell
# Install Windows Terminal from Microsoft Store
# Then run bb setup in Windows Terminal
```

**Option 3: Use Git Bash**
```bash
# Git Bash (included with Git for Windows) supports colors
bb setup
```

**Note**: Colors are cosmetic. Old CMD still works (just without colors).

---

### Linux: Permission Denied on Babashka Install Script

**Symptom**:
```bash
bash: ./install: Permission denied
```

**Cause**: Install script not executable.

**Solution**:
```bash
# Download
curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install

# Make executable
chmod +x install

# Run
./install
```

**Alternative (One-Liner)**:
```bash
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
```

---

### macOS: "bb" Cannot Be Opened (Security Warning)

**Symptom**:
```
"bb" cannot be opened because it is from an unidentified developer.
```

**Cause**: macOS Gatekeeper blocks unsigned binaries.

**Solution**:

**Option 1: Allow in System Preferences**
1. System Preferences → Security & Privacy
2. Click "Allow Anyway" next to bb message
3. Run `bb --version` again

**Option 2: Remove Quarantine Flag**
```bash
xattr -d com.apple.quarantine $(which bb)
```

**Option 3: Install via Homebrew (Recommended)**
```bash
brew install borkdude/brew/babashka  # Homebrew handles security
```

---

## Template Errors

### Error: "Dependency resolution failed"

**Symptom**:
```
Error building classpath. Could not find artifact boundary-core:boundary-core:jar:1.0.0
```

**Cause**: Template references non-existent library version or git SHA.

**Solutions**:

**Option 1: Check BOUNDARY_REPO_PATH**
```bash
# Ensure BOUNDARY_REPO_PATH points to valid Boundary clone
export BOUNDARY_REPO_PATH=/path/to/boundary
ls $BOUNDARY_REPO_PATH/libs/core  # Should exist

bb setup --template minimal --name my-app
```

**Option 2: Use Default Detection**
```bash
# Place starter as sibling to boundary/
cd /path/to/projects
git clone https://github.com/boundary-app/boundary
git clone https://github.com/boundary-app/boundary-starter
cd boundary-starter
bb setup  # Auto-detects ../boundary
```

**Option 3: Update Git SHA**
```bash
# If using custom template with outdated SHA
cd boundary
git log --oneline | head -1  # Get latest SHA

# Update template file
vim saved-templates/my-template.edn
# Update :git/sha to latest
```

---

### Error: "Missing required library"

**Symptom**:
```
Custom template validation failed:
Missing required libraries: core, observability, platform
```

**Cause**: Custom template doesn't include foundation libraries (core, observability, platform).

**Solution**:

Foundation libraries are **always required**. When using custom template wizard:

```bash
bb setup
# Select: 6 (custom template)
# Select: 1 (Create new custom template)

# Foundation libraries (required - auto-included):
#   ✅ core
#   ✅ observability
#   ✅ platform

# Select additional libraries:
# Select libraries (space-separated): 4 5  # e.g., user, admin
```

**Manually Editing Template**:
```clojure
;; saved-templates/my-template.edn
{:name "my-template"
 :libraries [:core          ;; ✅ REQUIRED
             :observability  ;; ✅ REQUIRED
             :platform       ;; ✅ REQUIRED
             :user           ;; Optional
             :admin]         ;; Optional
 ...}
```

---

### Error: "Dependency conflict detected"

**Symptom**:
```
Warning: Library 'calendar' requires 'storage', but 'storage' is not selected.
Auto-adding dependency: storage
```

**Cause**: Selected library depends on another library not in selection.

**Solution**:

**This is automatic** — dependencies are added automatically with a warning. No action needed.

**Example**:
```bash
# Select calendar (requires storage)
bb setup
# Select: 6 (custom)
# Select: 1 (Create new)
# Select libraries: 17  # calendar

# Output:
# Warning: Library 'calendar' requires 'storage'
# Auto-adding dependency: storage

# Final selection:
# ✅ core, observability, platform (foundation)
# ✅ calendar (selected)
# ✅ storage (dependency added)
```

**Manual Resolution** (if editing template file):
```clojure
;; Before (missing dependency)
{:libraries [:core :observability :platform :calendar]}

;; After (dependency added)
{:libraries [:core :observability :platform :storage :calendar]}
```

---

## Database Errors

### Error: "Database connection failed"

**Symptom**:
```
Exception: org.postgresql.util.PSQLException: Connection refused
```

**Cause**: PostgreSQL not running or wrong connection details.

**Solutions**:

**Option 1: Use SQLite (No Setup Required)**
```bash
# SQLite requires no database server
bb setup --template minimal --name my-app --db sqlite

# In generated project
clojure -M:repl-clj
# Works immediately — no database setup needed
```

**Option 2: Start PostgreSQL**
```bash
# macOS
brew services start postgresql@14

# Linux (systemd)
sudo systemctl start postgresql

# Docker
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:14
```

**Option 3: Check Connection String**
```bash
# .env file
DATABASE_URL="postgresql://localhost/mydb"

# Test connection
psql -h localhost -U postgres -d mydb
```

---

### Error: "Migration failed"

**Symptom**:
```
java.sql.SQLException: relation "users" already exists
```

**Cause**: Migrations already applied, attempting to re-run.

**Solutions**:

**Option 1: Fresh Database**
```bash
# PostgreSQL
psql -U postgres
DROP DATABASE mydb;
CREATE DATABASE mydb;
\q

# Re-run migrations
clojure -M:migrate migrate
```

**Option 2: Check Migration Status**
```bash
clojure -M:migrate list
# Shows which migrations are applied
```

**Option 3: Rollback**
```bash
clojure -M:migrate rollback
# Rolls back last migration
```

---

### Error: "SQLite database locked"

**Symptom**:
```
org.sqlite.SQLiteException: [SQLITE_BUSY] The database file is locked
```

**Cause**: Another process has the database file open (common during development).

**Solutions**:

**Option 1: Close Other REPL Sessions**
```bash
# Find processes using database
lsof dev-database.db  # macOS/Linux
# Kill stale REPL processes
```

**Option 2: Wait and Retry**
```clojure
;; SQLite automatically retries with backoff
;; Usually resolves within seconds
```

**Option 3: Use PostgreSQL for Production**
```bash
# SQLite is great for development, but PostgreSQL handles concurrency better
bb setup --template web-app --name my-app --db postgres
```

---

## REPL Issues

### Error: "Port 7888 already in use"

**Symptom**:
```
java.net.BindException: Address already in use
```

**Cause**: Another REPL session already running on port 7888.

**Solutions**:

**Option 1: Stop Existing REPL**
```bash
# Find process using port 7888
lsof -i :7888  # macOS/Linux
netstat -ano | findstr :7888  # Windows

# Kill process (macOS/Linux)
kill -9 <PID>

# Kill process (Windows)
taskkill /PID <PID> /F
```

**Option 2: Use Different Port**
```bash
# Start REPL on different port
JVM_OPTS="-Dnrepl.port=7889" clojure -M:repl-clj
```

**Option 3: Connect to Existing REPL**
```bash
# If REPL already running, just connect
# (Don't start new REPL)
```

---

### Error: "Namespace not found"

**Symptom**:
```clojure
(require '[boundary.user.core.user :as user])
;; Error: Could not locate boundary/user/core/user.clj
```

**Cause**: Library not included in template or wrong namespace path.

**Solutions**:

**Option 1: Check Library Included**
```bash
# In project directory
cat deps.edn | grep "boundary/user"
# Should show: boundary/user {:git/url ... :deps/root "libs/user"}
```

**Option 2: Verify Namespace Path**
```bash
# Check file exists
ls -la $(cat deps.edn | grep boundary-user | grep -oP '/.*boundary') # Linux/macOS

# Should show: libs/user/src/boundary/user/core/user.clj
```

**Option 3: Restart REPL**
```clojure
;; Sometimes namespace cache stale
(integrant.repl/halt)
(System/exit 0)

;; Restart REPL
;; clojure -M:repl-clj
```

---

### Error: "System failed to start"

**Symptom**:
```clojure
(require '[integrant.repl :as ig-repl])
(ig-repl/go)
;; Exception: Key :boundary/db-context failed
```

**Cause**: Missing environment variable or configuration error.

**Solutions**:

**Step 1: Check Required Env Vars**
```bash
# Minimal template
echo $DATABASE_URL  # Should be set

# API-Only / Web-App / SaaS templates
echo $JWT_SECRET    # Should be set (32+ chars)
```

**Step 2: Check Configuration File**
```bash
# resources/conf/dev/config.edn
cat resources/conf/dev/config.edn

# Look for #env tags
# Ensure corresponding env vars set
```

**Step 3: Check Database Connection**
```bash
# SQLite
ls dev-database.db  # Should exist or be created

# PostgreSQL
psql -h localhost -U postgres -d mydb  # Should connect
```

**Step 4: Read Error Message**
```clojure
(ig-repl/go)
;; Read full stack trace
;; Often shows specific missing config key
```

---

## Debugging Strategies

### Strategy 1: Dry-Run First

Before generating files, preview configuration:

```bash
bb setup --template web-app --name my-app --dry-run
```

**Output Shows**:
- Template configuration
- Selected libraries
- Database choice
- Environment variables needed
- Files that will be created

**No files created** — safe to experiment.

---

### Strategy 2: Start Small

Use minimal template first, add complexity gradually:

```bash
# Step 1: Minimal template
bb setup --template minimal --name test-app --db sqlite

# Verify it works
cd test-app
clojure -M:repl-clj
# (integrant.repl/go)

# Step 2: Add features incrementally
bb setup --template api-only --name test-api --db sqlite
# Test authentication...

# Step 3: Full template
bb setup --template saas --name test-saas --db postgres
```

---

### Strategy 3: Check Logs

Enable verbose logging:

```bash
# Babashka verbose mode
bb -v setup --template minimal --name my-app

# Clojure verbose mode
clojure -Sverbose -M:repl-clj
```

---

### Strategy 4: Isolate the Problem

**Template Issue?**
```bash
# Try different template
bb setup --template minimal --name test  # Does this work?
```

**Database Issue?**
```bash
# Try SQLite instead of PostgreSQL
bb setup --template web-app --name test --db sqlite
```

**Environment Issue?**
```bash
# Try minimal environment
unset JWT_SECRET DATABASE_URL  # Clear all vars
export JWT_SECRET="test-secret-32-chars-minimum"
bb setup --template minimal --name test
```

---

### Strategy 5: Read the Source

All code is in `starter/scripts/`:

```bash
cd /path/to/boundary/starter

# Template loading
cat scripts/helpers.clj

# File generation
cat scripts/file_generators.clj

# CLI logic
cat scripts/setup.clj
```

**Look for**:
- Error messages (search for your error text)
- Validation logic
- Environment variable handling

---

## Getting Help

### Before Asking

1. **Check this guide** — Search for error message
2. **Try dry-run** — `bb setup --dry-run` to preview
3. **Check logs** — Run with `-v` flag
4. **Isolate problem** — Try minimal template first

### When Reporting Issues

Include:

```markdown
**Platform**: macOS 13.5 / Ubuntu 22.04 / Windows 11
**Shell**: Bash 5.2 / PowerShell 7 / CMD
**Babashka Version**: bb --version
**Java Version**: java -version

**Command**:
bb setup --template web-app --name my-app --db postgres

**Error Message**:
[Paste full error output]

**Expected**:
[What you expected to happen]

**Context**:
- [ ] First time using Boundary Starter?
- [ ] Environment variables set? (JWT_SECRET, etc.)
- [ ] Database running? (for PostgreSQL templates)
- [ ] Tried dry-run first?
```

### Resources

- **README**: [starter/README.md](README.md) — Quick start and overview
- **Usage Examples**: [USAGE_EXAMPLES.md](USAGE_EXAMPLES.md) — 8 real-world scenarios
- **Platform Testing**: [CROSS_PLATFORM_TESTING_GUIDE.md](CROSS_PLATFORM_TESTING_GUIDE.md) — Platform-specific tests
- **Platform Differences**: [PLATFORM_DIFFERENCES.md](PLATFORM_DIFFERENCES.md) — Known platform behaviors
- **Template Comparison**: `TEMPLATE_COMPARISON.md` — Which template to choose?
- **Boundary Framework**: [../README.md](../README.md) — Framework documentation
- **Boundary AGENTS.md**: [../AGENTS.md](../AGENTS.md) — Technical reference

---

## FAQ

### Q: My template uses git dependencies — will this work offline?

**A**: Git dependencies require network access on first use. After initial download, dependencies are cached in `~/.gitlibs/`.

**Workaround for offline**:
```bash
# Generate once while online
bb setup --template minimal --name cache-test
cd cache-test
clojure -Spath  # Downloads all dependencies

# Now works offline (uses cache)
clojure -M:repl-clj
```

---

### Q: How do I update Boundary libraries in generated project?

**A**: Update git SHA in `deps.edn`:

```clojure
;; deps.edn (before)
{:deps {boundary/core
        {:git/url "https://github.com/thijs-creemers/boundary"
         :git/sha "abc123..."
         :deps/root "libs/core"}}}

;; Get latest SHA
;; cd /path/to/boundary
;; git pull
;; git log --oneline | head -1  # Get SHA

;; deps.edn (after)
{:deps {boundary/core
        {:git/url "https://github.com/thijs-creemers/boundary"
         :git/sha "def456..."  ;; ← Update SHA
         :deps/root "libs/core"}}}
```

---

### Q: Can I mix SQLite and PostgreSQL in same project?

**A**: Yes, use `--db both`:

```bash
bb setup --template web-app --name my-app --db both
```

**Generated deps.edn includes**:
```clojure
{:deps {org.xerial/sqlite-jdbc {:mvn/version "3.44.1.0"}
        org.postgresql/postgresql {:mvn/version "42.7.1"}}}
```

**Switch at runtime** via `DATABASE_URL`:
```bash
# Use SQLite
export DATABASE_URL="jdbc:sqlite:./dev-database.db"

# Use PostgreSQL
export DATABASE_URL="postgresql://localhost/mydb"
```

---

### Q: Generated project has wrong line endings

**A**: Configure Git:

```bash
# Force LF on checkout (recommended)
git config --global core.autocrlf input  # Linux/macOS
git config --global core.autocrlf true   # Windows

# Re-checkout files
git rm --cached -r .
git reset --hard
```

See [PLATFORM_DIFFERENCES.md](PLATFORM_DIFFERENCES.md) for details.

---

**Last Updated**: 2026-03-14  
**Sprint**: 4 (Day 17)  
**Status**: Complete  
**See Also**: [USAGE_EXAMPLES.md](USAGE_EXAMPLES.md), [CROSS_PLATFORM_TESTING_GUIDE.md](CROSS_PLATFORM_TESTING_GUIDE.md)
