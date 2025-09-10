# Quick Start

*Get Elara running in minutes*

## Prerequisites

### Required Software

**Java Development Kit**:
- **macOS**: `brew install openjdk@17`  
- **Linux**: `sudo apt-get install openjdk-17-jdk` (Ubuntu/Debian) or `sudo dnf install java-17-openjdk` (Fedora/RHEL)
- **Verify**: `java -version` (should show Java 17+)

**Clojure CLI**:
- **macOS**: `brew install clojure/tools/clojure`
- **Linux**: 
  ```zsh
  curl -L -O https://download.clojure.org/install/linux-install-1.11.1.1413.sh
  chmod +x linux-install-1.11.1.1413.sh  
  sudo ./linux-install-1.11.1.1413.sh
  ```
- **Verify**: `clojure --version`

### Optional but Recommended

**Development Tools**:
```zsh
# macOS
brew install direnv rlwrap git

# Linux (Ubuntu/Debian)
sudo apt-get install direnv rlwrap git

# Linux (Fedora/RHEL) 
sudo dnf install direnv rlwrap git
```

## Getting Started

### 1. Clone and Setup

```zsh
# Clone the repository
git clone https://github.com/your-org/elara.git
cd elara

# Verify project structure
ls -la
# Should see: deps.edn, src/, test/, resources/, docs/
```

### 2. Run Tests (First Validation)

```zsh
# Run all tests to verify setup
clojure -M:test

# Expected output:
# Running tests...
# Passed: X, Failed: 0, Errors: 0
```

### 3. Start Development REPL

```zsh
# Start REPL with development dependencies
clojure -M:repl-clj

# You should see:
# Clojure 1.12.1
# user=> 
```

### 4. Load and Start the System

In the REPL:

```clojure
;; Load the system
(require '[integrant.repl :as ig-repl])

;; Start the development system
(ig-repl/go)

;; System should start successfully
;; => :initiated
```

### 5. Verify System is Running

```clojure
;; Check system status
(ig-repl/system)
;; => Shows running system components

;; Try a simple operation (if user module is active)
(require '[elara.user.core :as user])
;; => nil (successful load)
```

## Development Workflow

### Basic REPL Operations

```clojure
;; Start system
(ig-repl/go)

;; Stop system  
(ig-repl/halt)

;; Restart system (reload code + restart)
(ig-repl/reset)

;; Clear system state
(ig-repl/clear)
```

### Running Tests

```zsh
# Run all tests
clojure -M:test

# Run tests in watch mode  
clojure -M:test --watch

# Run specific module tests
clojure -M:test --focus :user

# Run with different verbosity
clojure -M:test --reporter documentation
```

### Code Quality

```zsh
# Lint code
clojure -M:clj-kondo --lint src test

# Check for outdated dependencies
clojure -M:outdated
```

## Environment Setup

### Database Configuration

The system uses **SQLite by default** for development (zero setup required):

```clojure
;; Check current database config
(require '[elara.config :as config])
(config/read-config "dev")
;; => {:elara/sqlite {:db "dev-database.db"}, ...}
```

### Optional: PostgreSQL Setup

If you need PostgreSQL for testing integrations:

```zsh
# macOS
brew install postgresql
brew services start postgresql  
createdb elara_dev

# Linux
sudo apt-get install postgresql postgresql-contrib
sudo systemctl start postgresql
sudo -u postgres createdb elara_dev

# Set environment variables
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB=elara_dev
export POSTGRES_USER=$(whoami)
export POSTGRES_PASSWORD=dev_password
```

## Building and Deployment

### Create Uberjar

```zsh
# Build deployable JAR
clojure -T:build uber

# Check build output
ls -la target/
# => elara-0.1.0-standalone.jar
```

### Run Built Application

```zsh
# Run the built JAR
java -jar target/elara-0.1.0-standalone.jar

# With environment-specific profile
ELARA_PROFILE=staging java -jar target/elara-0.1.0-standalone.jar
```

## Troubleshooting

### Common Issues

**1. Port already in use**:
```zsh
# Find what's using the port
lsof -i :8080

# Kill the process if needed
kill -9 <PID>
```

**2. Dependencies not resolving**:
```zsh
# Clear dependency cache
rm -rf ~/.m2/repository/
clojure -P  # Re-download dependencies
```

**3. REPL won't start**:
```zsh
# Check Java version
java -version  # Should be 11+

# Verify Clojure CLI
clojure --version

# Try with verbose output
clojure -M:repl-clj -v
```

**4. Tests failing**:
```zsh
# Check if database file exists and is writable
ls -la dev-database.db

# Run single test namespace for debugging
clojure -M:test --namespace elara.user.core-test
```

### Getting Help

**Check system status**:
```clojure
;; In REPL
(ig-repl/system)         ; Show system components
(require '[clojure.repl :refer [doc source]])
(doc ig-repl/reset)      ; Get function documentation
```

**View logs**:
```zsh
# Application logs (if file logging is enabled)
tail -f logs/web-service.log

# Or check console output in REPL session
```

**Validate configuration**:
```clojure
;; Load and inspect config
(require '[elara.config :as config])
(require '[clojure.pprint :refer [pprint]])
(pprint (config/read-config "dev"))
```

## Next Steps

### Development Environment

1. **Configure your editor** for Clojure development:
   - **VS Code**: Install Calva extension
   - **IntelliJ**: Install Cursive plugin  
   - **Emacs**: Install CIDER
   - **Vim**: Install Conjure or Fireplace

2. **Set up direnv** for automatic environment loading:
   ```zsh
   echo 'export ELARA_PROFILE=dev' > .envrc
   direnv allow
   ```

3. **Explore the codebase**:
   ```clojure
   ;; In REPL - explore modules
   (require '[elara.user.core :as user])
   (require '[elara.billing.core :as billing])
   (require '[elara.workflow.core :as workflow])
   ```

### Key Concepts to Learn

1. **Functional Core/Imperative Shell** architecture
2. **Module structure** and boundaries  
3. **Configuration management** with profiles
4. **Testing strategy** across layers
5. **System lifecycle** with Integrant

### Useful Resources

- **Architecture**: `docs/architecture/` - Detailed architectural documentation
- **PRD**: `docs/PRD-IMPROVEMENT-SUMMARY.adoc` - Project requirements and goals
- **Module Examples**: `src/elara/{user,billing,workflow}/` - Reference implementations

## Development Commands Summary

```zsh
# Project setup
git clone <repo> && cd elara
clojure -M:test                    # Verify installation

# Development cycle  
clojure -M:repl-clj                # Start REPL
# (ig-repl/go)                     # Start system
# (ig-repl/reset)                  # Reload and restart

# Testing
clojure -M:test                    # All tests
clojure -M:test --watch            # Watch mode
clojure -M:test --focus :user      # Module-specific

# Code quality
clojure -M:clj-kondo --lint src test   # Lint
clojure -M:outdated                     # Check dependencies

# Build
clojure -T:build uber              # Create uberjar
```

---
*Happy coding! 🚀*

*Next: Read [warp.md](../../../warp.md) for comprehensive development guidance*
