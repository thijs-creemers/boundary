# Configuration

*Environment management and secrets handling in Boundary*

## Configuration Architecture

Boundary uses a layered configuration approach based on **Aero** for data-driven configuration and **Environ** for environment variable access. The configuration system follows the FC/IS pattern by keeping configuration concerns in the Shell layer while the Functional Core receives pure data.

### Configuration Sources (by precedence)

1. **Environment Variables** (highest precedence)
2. **Profile-specific EDN files** (`resources/conf/{profile}/config.edn`)
3. **Default values** (lowest precedence)

## File Structure

```
resources/conf/
├── dev/
│   └── config.edn        # Development configuration
├── staging/
│   └── config.edn        # Staging configuration
└── prod/
    └── config.edn        # Production configuration
```

## Development Configuration

### Current Dev Configuration

The development configuration uses SQLite for simplicity and includes basic settings:

```clojure path=/Users/thijscreemers/work/tcbv/boundary/resources/conf/dev/config.edn start=1
{
 :active
 {
  :boundary/settings
  {:name              "boundary-dev"
   :version           "0.1.0"
   :date-format       "yyyy-MM-dd"
   :date-time-format  "yyyy-MM-dd HH:mm:ss"
   :currency/iso-code "EUR"}}

 :boundary/sqlite
 {:db "dev-database.db"}

 :inactive
 {
  :boundary/postgresql
  {:host          #env "POSTGRES_HOST"
   :port          #env "POSTGRES_PORT"
   :dbname        #env "POSTGRES_DB"
   :user          #env "POSTGRES_USER"
   :password      #env "POSTGRES_PASSWORD"
   :auto-commit   true
   :max-pool-size 15}
  :boundary/logging {:level     :info
                  :console   true
                  :appenders [{:appender       :rolling-file
                               :file           "logs/web-service.log"
                               :rolling-policy {:type        :time-based
                                                :max-history 30
                                                :max-size    5000000 ; bytes
                                                :pattern     ".%d{yyyy-MM-dd}.%i.gz"}}]}}}
```

### Environment Variables for Development

Create a `.env` file in the project root for development (never commit this file):

```bash
# Database configuration (when using PostgreSQL)
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=boundary_dev
POSTGRES_USER=dev_user
POSTGRES_PASSWORD=dev_password

# HTTP configuration
HTTP_PORT=8080
HTTP_HOST=localhost

# Logging level
LOG_LEVEL=debug

# Feature flags
BND_FEATURE_USER_MODULE=true
BND_FEATURE_BILLING_MODULE=false
BND_FEATURE_WORKFLOW_MODULE=true
```

### Setting up Development Environment

1. **Environment Variables** (recommended approach):

```zsh
# Set environment variables in your shell
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432  
export POSTGRES_DB=boundary_dev
export POSTGRES_USER=dev_user
export POSTGRES_PASSWORD=dev_password

# Or use direnv with .envrc file
echo 'export POSTGRES_HOST=localhost' >> .envrc
echo 'export POSTGRES_PORT=5432' >> .envrc
echo 'export POSTGRES_DB=boundary_dev' >> .envrc
direnv allow
```

2. **Using .env files** (with dotenv library):

```zsh
# Create .env file (gitignored)
cat > .env << EOF
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=boundary_dev
POSTGRES_USER=dev_user
POSTGRES_PASSWORD=dev_password
EOF
```

## Configuration Usage

### Reading Configuration

Configuration is read through the `boundary.config` namespace:

```clojure path=null start=null
(ns your-namespace
  (:require [boundary.config :as config]))

;; Read configuration for current profile
(def app-config (config/read-config "dev"))

;; Access specific configuration sections
(def db-config (:boundary/postgresql app-config))
(def app-settings (:boundary/settings app-config))
```

### Profile-based Loading

Different profiles load different configurations:

```clojure path=null start=null
;; Development
(config/read-config "dev")     ; loads resources/conf/dev/config.edn

;; Staging
(config/read-config "staging") ; loads resources/conf/staging/config.edn

;; Production  
(config/read-config "prod")    ; loads resources/conf/prod/config.edn
```

## Configuration Patterns

### Environment Variable Overrides

Use the `#env` reader tag in EDN files for environment-specific values:

```clojure path=null start=null
{:database {:host #env "DATABASE_HOST"                    ; Required env var
            :port #env ["DATABASE_PORT" "5432"]           ; Optional with default
            :name #env ["DATABASE_NAME" "boundary_dev"]      ; Optional with default
            :password #env "DATABASE_PASSWORD"}}           ; Required env var
```

### Feature Flags

Configure module and feature toggles through configuration:

```clojure path=null start=null
{:features {:user-module #env ["BND_USER_MODULE" "true"]
            :billing-module #env ["BND_BILLING_MODULE" "false"]
            :workflow-module #env ["BND_WORKFLOW_MODULE" "true"]
            :experimental-features #env ["BND_EXPERIMENTAL" "false"]}}
```

### Database Configuration

Support for multiple database types:

```clojure path=null start=null
;; SQLite (development)
{:active {:boundary/sqlite {:db "dev-database.db"}}}

;; PostgreSQL (staging/production)  
{:active {:boundary/postgresql {:host #env "POSTGRES_HOST"
                             :port #env ["POSTGRES_PORT" "5432"]  
                             :dbname #env "POSTGRES_DB"
                             :user #env "POSTGRES_USER"
                             :password #env "POSTGRES_PASSWORD"
                             :max-pool-size #env ["DB_POOL_SIZE" "15"]}}}
```

## Production Configuration

### Staging Configuration Example

```clojure path=null start=null
{:active
 {:boundary/settings {:name "boundary-staging"
                   :version "0.1.0"}
  
  :boundary/postgresql {:host #env "POSTGRES_HOST"
                     :port #env ["POSTGRES_PORT" "5432"]
                     :dbname #env "POSTGRES_DB" 
                     :user #env "POSTGRES_USER"
                     :password #env "POSTGRES_PASSWORD"
                     :max-pool-size #env ["DB_POOL_SIZE" "15"]
                     :ssl-mode "require"}
  
  :boundary/logging {:level #env ["LOG_LEVEL" "info"]
                  :console false
                  :structured true}
                  
  :features {:user-module true
             :billing-module true
             :workflow-module true
             :experimental-features false}}}
```

### Production Configuration Example

```clojure path=null start=null  
{:active
 {:boundary/settings {:name "boundary-production"
                   :version "0.1.0"}
  
  :boundary/postgresql {:host #env "POSTGRES_HOST"
                     :port #env ["POSTGRES_PORT" "5432"]
                     :dbname #env "POSTGRES_DB"
                     :user #env "POSTGRES_USER" 
                     :password #env "POSTGRES_PASSWORD"
                     :max-pool-size #env ["DB_POOL_SIZE" "25"]
                     :ssl-mode "require"
                     :connection-timeout #env ["DB_TIMEOUT" "5000"]}
  
  :boundary/logging {:level #env ["LOG_LEVEL" "warn"]
                  :console false
                  :structured true
                  :telemetry true}
                  
  :features {:user-module true
             :billing-module #env ["BILLING_ENABLED" "true"]
             :workflow-module true
             :experimental-features false}}}
```

## Secrets Management

### Rules for Secrets

✅ **DO**:
- Use environment variables for all secrets
- Use external secret management systems (AWS Secrets Manager, HashiCorp Vault, etc.)
- Use file-based secrets for Docker/Kubernetes deployments
- Validate required secrets at startup

❌ **DON'T**:
- Store secrets in EDN configuration files
- Commit secrets to version control
- Log secrets in application logs
- Pass secrets as command-line arguments

### Environment Variable Secrets

```zsh
# Database credentials
export POSTGRES_PASSWORD="$(vault kv get -field=password secret/boundary/db)"
export API_KEY="$(vault kv get -field=api-key secret/boundary/external)"

# JWT signing key
export JWT_SECRET="$(openssl rand -base64 32)"

# External service credentials  
export STRIPE_SECRET_KEY="$(vault kv get -field=secret-key secret/boundary/stripe)"
```

### File-based Secrets (Docker/Kubernetes)

```clojure path=null start=null
{:database {:password #env "DATABASE_PASSWORD_FILE"}  ; Points to file path
 :jwt {:secret #env "JWT_SECRET_FILE"}
 :external-apis {:api-key #env "API_KEY_FILE"}}
```

### Secret Validation

Validate required secrets at system startup:

```clojure path=null start=null
(defn validate-secrets! [config]
  (let [required-secrets [:database/password :jwt/secret :external-apis/api-key]]
    (doseq [secret-path required-secrets]
      (when (nil? (get-in config secret-path))
        (throw (ex-info "Required secret missing" 
                        {:secret secret-path
                         :config-profile (:profile config)}))))))
```

## Testing Configuration

### Test Configuration Overrides

For testing, override configuration to use in-memory or test-specific resources:

```clojure path=null start=null
;; test/resources/conf/test/config.edn
{:active
 {:boundary/settings {:name "boundary-test"}
  
  :boundary/sqlite {:db ":memory:"}  ; In-memory SQLite for tests
  
  :boundary/logging {:level :error   ; Reduce noise in tests
                  :console false}
                  
  :features {:user-module true
             :billing-module false    ; Disable non-essential modules
             :workflow-module false
             :experimental-features true}}}
```

### Test Environment Setup

```clojure path=null start=null
(ns boundary.test-helpers
  (:require [boundary.config :as config]))

(defn with-test-config [test-fn]
  (binding [config/*profile* "test"]
    (let [test-config (config/read-config "test")]
      (with-redefs [config/current-config test-config]
        (test-fn)))))

;; Use in tests
(use-fixtures :once with-test-config)
```

## System Integration

### Integrant System Configuration

Configuration is wired through the Integrant system:

```clojure path=null start=null
(ns boundary.shell.system.wiring
  (:require [boundary.config :as config]
            [integrant.core :as ig]))

(defn system-config [profile]
  (let [app-config (config/read-config profile)]
    ;; Build Integrant system configuration from app config
    {:boundary/database    (:boundary/postgresql app-config)
     :boundary/http-server (:boundary/http app-config)
     :boundary/logging     (:boundary/logging app-config)}))

(defn start-system! [profile]
  (-> (system-config profile)
      (ig/init)))
```

### Configuration Validation

Validate configuration at startup using Malli schemas:

```clojure path=null start=null
(def DatabaseConfig
  [:map
   [:host :string]
   [:port :int]
   [:dbname :string]
   [:user :string]
   [:password :string]
   [:max-pool-size {:optional true} :int]])

(defn validate-config! [config]
  (when-not (m/validate DatabaseConfig (:boundary/postgresql config))
    (throw (ex-info "Invalid database configuration" 
                    {:errors (m/explain DatabaseConfig (:boundary/postgresql config))}))))
```

## Common Configuration Tasks

### Switching Profiles

```zsh
# Set profile via environment variable
export BND_PROFILE=staging

# Or pass as system property
clojure -J-Dboundary.profile=staging -M:run

# Or programmatically
(config/read-config (or (System/getenv "BND_PROFILE") "dev"))
```

### Adding New Configuration Keys

1. **Add to base configuration**:
```clojure path=null start=null
;; resources/conf/dev/config.edn
{:new-service {:enabled #env ["NEW_SERVICE_ENABLED" "false"]
               :endpoint #env "NEW_SERVICE_ENDPOINT"
               :timeout #env ["NEW_SERVICE_TIMEOUT" "30000"]}}
```

2. **Create Malli schema**:
```clojure path=null start=null
(def NewServiceConfig
  [:map
   [:enabled :boolean]
   [:endpoint :string]
   [:timeout :int]])
```

3. **Update system wiring**:
```clojure path=null start=null
{:new-service/client (:new-service app-config)}
```

### Environment-specific Overrides

```clojure path=null start=null
;; Development: use local services
{:external-service {:endpoint "http://localhost:9000"}}

;; Staging: use staging endpoints  
{:external-service {:endpoint #env "STAGING_SERVICE_ENDPOINT"}}

;; Production: use production endpoints
{:external-service {:endpoint #env "PROD_SERVICE_ENDPOINT"}}
```

## Troubleshooting Configuration

### Common Issues

1. **Missing environment variables**:
```zsh
# Check if variable is set
echo $POSTGRES_PASSWORD

# Set missing variables
export POSTGRES_PASSWORD="your-password"
```

2. **Configuration not loading**:
```clojure path=null start=null
;; Debug configuration loading
(config/read-config "dev")  ; Should not throw exception
```

3. **Profile not found**:
```zsh
# Check if config file exists
ls -la resources/conf/dev/config.edn
```

### Configuration Debugging

```clojure path=null start=null
(ns boundary.config.debug
  (:require [boundary.config :as config]
            [clojure.pprint :as pprint]))

(defn debug-config [profile]
  (let [config (config/read-config profile)]
    (println "=== Configuration Debug ===")
    (println "Profile:" profile)
    (println "Loaded configuration:")
    (pprint/pprint config)
    config))

;; Usage in REPL
(debug-config "dev")
```

---
*Last Updated: 2025-01-10 18:45*
*Based on: Existing config files, architecture docs, and FC/IS patterns*
