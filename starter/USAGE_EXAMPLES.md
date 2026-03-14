# Usage Examples

**Purpose**: Real-world scenarios demonstrating Boundary Starter capabilities.  
**Date**: 2026-03-14  
**Sprint**: 4 (Day 17)

---

## Overview

This guide provides practical examples for common use cases. Each example includes:
- Scenario description
- Step-by-step commands
- Expected output
- What gets generated
- Next steps after generation

---

## Example 1: Learning Boundary Framework

**Scenario**: You're new to Boundary and want to learn the framework without overwhelming complexity.

**Goal**: Minimal working project with just core infrastructure.

**Template**: Minimal (3 libraries: core, observability, platform)

### Commands

```bash
# Interactive mode (recommended for first-time users)
cd /path/to/boundary/starter
bb setup
# Select: 1 (minimal)
# Name: boundary-learning
# Database: 1 (SQLite - zero config)
# Directory: ~/projects
# Confirm: y
```

### What You Get

```
boundary-learning/
├── deps.edn                    # 3 Boundary libs (1.4 KB)
├── resources/conf/dev/
│   ├── config.edn             # HTTP + DB config only
│   └── system.clj              # Minimal Integrant system
├── src/boundary/app.clj        # Placeholder app
└── .env.example                # 5 environment variables
```

**Key Features**:
- HTTP server (port 3000)
- SQLite database (zero-config)
- Structured logging
- Development REPL

### Next Steps

```bash
cd ~/projects/boundary-learning

# Start REPL
clojure -M:repl-clj

# In REPL
(require '[integrant.repl :as ig-repl])
(ig-repl/go)
;; → HTTP server starts on http://localhost:3000

# Explore
(require '[boundary.core.validation :as v])
(v/validate [:string] "hello")  # Test validation

# Make changes and reload
(ig-repl/reset)
```

**Learning Path**:
1. Explore `resources/conf/dev/config.edn` (understand Aero config)
2. Read `resources/conf/dev/system.clj` (understand Integrant system)
3. Try validation examples from `boundary-core` docs
4. Add your first HTTP endpoint

---

## Example 2: Building a RESTful API

**Scenario**: You're building a mobile backend that needs JWT authentication, no web UI.

**Goal**: Stateless JSON API with authentication and API keys.

**Template**: API-Only (4 libraries: core, observability, platform, user)

### Commands

```bash
# Non-interactive mode (faster for experienced users)
bb setup --template api-only --name mobile-backend --db postgres

# Alternative: SQLite for local dev
bb setup --template api-only --name mobile-backend --db sqlite
```

### What You Get

```
mobile-backend/
├── deps.edn                    # 4 Boundary libs (1.5 KB)
├── resources/conf/dev/
│   ├── config.edn             # HTTP + DB + JWT + CORS + Rate limiting
│   └── system.clj              # Integrant system with auth
├── migrations/
│   ├── 001_users.sql           # User accounts
│   └── 002_api_keys.sql        # API key management
└── .env.example                # JWT_SECRET, CORS origins, rate limits
```

**Key Features**:
- JWT authentication (stateless, no sessions)
- API key management (for M2M auth)
- CORS configuration (for web/mobile clients)
- Rate limiting (protection against abuse)
- JSON-only responses (no HTML rendering)

### Next Steps

```bash
cd mobile-backend

# Set JWT secret (required)
export JWT_SECRET="your-secret-32-chars-minimum-length"

# Run migrations
clojure -M:migrate migrate

# Start server
clojure -M:repl-clj
# (ig-repl/go)

# Test endpoints
curl http://localhost:3000/api/health
# {"status": "ok"}

# Register user
curl -X POST http://localhost:3000/api/users \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"secure123"}'

# Login (get JWT)
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"secure123"}'
# {"token":"eyJ..."}

# Authenticated request
curl http://localhost:3000/api/users/me \
  -H "Authorization: Bearer eyJ..."
```

**Integration Tips**:
1. Update CORS origins in `config.edn` (allow your mobile/web app)
2. Adjust rate limits based on your traffic patterns
3. Add custom endpoints in `src/boundary/api/routes.clj`
4. Deploy to cloud (Heroku, Fly.io, Railway)

---

## Example 3: Microservice for Kubernetes

**Scenario**: You're building an internal service that runs in Kubernetes, needs health checks and metrics.

**Goal**: Lightweight containerized service with observability.

**Template**: Microservice (3 libraries: core, observability, platform)

### Commands

```bash
bb setup --template microservice --name payment-processor --db postgres --output ~/microservices
```

### What You Get

```
payment-processor/
├── deps.edn                    # 3 Boundary libs (1.4 KB)
├── resources/conf/dev/
│   ├── config.edn             # 12-factor config, health checks, metrics
│   └── system.clj              # Minimal system
├── .env.example                # 12 environment variables
└── README.md                   # Deployment instructions
```

**Key Features**:
- Health check endpoints (`/health`, `/ready`, `/live`)
- Prometheus metrics (`/metrics`)
- 12-factor app (all config via env vars)
- Graceful shutdown (configurable timeout)
- Optional database (disabled by default)
- Distributed tracing ready (Jaeger/Zipkin)
- Structured JSON logging

### Next Steps

```bash
cd ~/microservices/payment-processor

# Configure service
cat > .env <<EOF
SERVICE_NAME=payment-processor
ENVIRONMENT=development
APP_VERSION=1.0.0
DATABASE_ENABLED=false
LOG_LEVEL=info
PORT=8080
SHUTDOWN_TIMEOUT_MS=30000
EOF

# Start service
source .env
clojure -M:repl-clj
# (ig-repl/go)

# Test health checks
curl http://localhost:8080/health
# {"status":"healthy","service":"payment-processor","version":"1.0.0"}

curl http://localhost:8080/ready
# {"ready":true}

# Check metrics (Prometheus format)
curl http://localhost:8080/metrics
# http_requests_total{method="GET",path="/health"} 1
```

**Kubernetes Deployment**:

```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payment-processor
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: payment-processor
        image: my-registry/payment-processor:1.0.0
        ports:
        - containerPort: 8080
        env:
        - name: SERVICE_NAME
          value: payment-processor
        - name: ENVIRONMENT
          value: production
        - name: DATABASE_ENABLED
          value: "false"
        livenessProbe:
          httpGet:
            path: /live
            port: 8080
          initialDelaySeconds: 30
        readinessProbe:
          httpGet:
            path: /ready
            port: 8080
          initialDelaySeconds: 5
```

---

## Example 4: Web Application with Admin UI

**Scenario**: You're building a web app that needs user authentication and an admin dashboard.

**Goal**: Full-featured web application with auth and CRUD admin UI.

**Template**: Web-App (5 libraries: core, observability, platform, user, admin)

### Commands

```bash
bb setup --template web-app --name task-manager --db postgres
```

### What You Get

```
task-manager/
├── deps.edn                    # 5 Boundary libs (1.6 KB)
├── resources/conf/dev/
│   ├── config.edn             # HTTP + DB + JWT + Admin UI + Sessions
│   └── system.clj              # Full Integrant system
├── migrations/
│   ├── 001_users.sql           # User accounts
│   ├── 002_sessions.sql        # Session management
│   └── 003_api_keys.sql        # API keys
└── .env.example                # JWT_SECRET, session config
```

**Key Features**:
- User registration/login (email + password)
- Session management (cookies)
- Auto-generated admin UI (CRUD operations)
- Role-based access control
- Email verification (optional)
- Password reset (optional)

### Next Steps

```bash
cd task-manager

# Set secrets
export JWT_SECRET="production-secret-32-chars-min"
export SESSION_SECRET="another-secret-32-chars-min"

# Run migrations
clojure -M:migrate migrate

# Start server
clojure -M:repl-clj
# (ig-repl/go)

# Visit http://localhost:3000
# → See login page

# Register first user (becomes admin)
# Visit: http://localhost:3000/register
# Email: admin@example.com
# Password: secure123

# Login and access admin UI
# Visit: http://localhost:3000/admin
# → Auto-generated CRUD for users
```

**Customization**:

Add your own entities to admin UI:

```clojure
;; src/boundary/task_manager/models.clj
(ns boundary.task-manager.models
  (:require [boundary.admin.schema :as admin-schema]))

(def task-schema
  [:map
   [:id :uuid]
   [:title :string]
   [:description {:optional true} :string]
   [:status [:enum "pending" "in-progress" "done"]]
   [:user-id :uuid]
   [:created-at inst?]])

(admin-schema/register-entity!
  :tasks
  {:schema task-schema
   :display-name "Tasks"
   :list-fields [:title :status :created-at]
   :form-fields [:title :description :status]})
```

**Result**: `/admin/tasks` now has full CRUD UI automatically generated!

---

## Example 5: Multi-Tenant SaaS Platform

**Scenario**: You're building a SaaS product with multiple customers, each needing isolated data.

**Goal**: Production-ready SaaS with multi-tenancy, background jobs, email, and file storage.

**Template**: SaaS (10 libraries: core, observability, platform, user, admin, tenant, jobs, email, storage, cache)

### Commands

```bash
bb setup --template saas --name crm-platform --db postgres
```

### What You Get

```
crm-platform/
├── deps.edn                    # 10 Boundary libs (2.0 KB)
├── resources/conf/dev/
│   ├── config.edn             # Full SaaS config (multi-tenant, jobs, email, S3)
│   └── system.clj              # Complete Integrant system
├── migrations/
│   ├── 001_users.sql           # User accounts
│   ├── 002_sessions.sql        # Sessions
│   ├── 003_tenants.sql         # Tenant management
│   ├── 004_tenant_users.sql    # User-tenant relationships
│   ├── 005_jobs.sql            # Background jobs
│   └── 006_storage.sql         # File metadata
└── .env.example                # 20+ environment variables
```

**Key Features**:
- Multi-tenancy (PostgreSQL schema-per-tenant isolation)
- Background job processing (async tasks, retries, dead letter queue)
- Email sending (SMTP, async via jobs, templates)
- File storage (local + S3, image processing, signed URLs)
- Redis caching (distributed, tenant-scoped)
- Admin UI for all entities
- Tenant provisioning/lifecycle
- User invitation system
- Subscription management hooks

### Next Steps

```bash
cd crm-platform

# Configure (minimal for local dev)
cat > .env <<EOF
# Core
JWT_SECRET="dev-secret-32-chars-minimum-length"
SESSION_SECRET="another-secret-32-chars-length"
DATABASE_URL="postgresql://localhost/crm_dev"

# Multi-tenancy
TENANT_PROVISIONING_ENABLED=true

# Jobs
JOBS_ENABLED=true
JOBS_WORKER_COUNT=2

# Email (local SMTP for dev)
EMAIL_ENABLED=true
SMTP_HOST=localhost
SMTP_PORT=1025  # Use MailHog for local dev
SMTP_FROM=noreply@crm-platform.test

# Storage (local for dev)
STORAGE_TYPE=local
STORAGE_LOCAL_PATH=./uploads

# Cache (in-memory for dev)
CACHE_TYPE=memory
EOF

# Run migrations
source .env
clojure -M:migrate migrate

# Start MailHog (local email testing)
docker run -d -p 1025:1025 -p 8025:8025 mailhog/mailhog

# Start server
clojure -M:repl-clj
# (ig-repl/go)

# Create first tenant
curl -X POST http://localhost:3000/api/tenants \
  -H "Content-Type: application/json" \
  -d '{"name":"Acme Corp","slug":"acme"}'
# → Tenant schema created in DB

# Invite user to tenant
curl -X POST http://localhost:3000/api/tenants/acme/users/invite \
  -H "Content-Type: application/json" \
  -d '{"email":"user@acme.com","role":"admin"}'
# → Invitation email sent (check http://localhost:8025)

# User accepts invitation and creates account
# → User added to tenant "acme"
# → All queries scoped to tenant schema

# Upload file (tenant-scoped)
curl -X POST http://localhost:3000/api/files \
  -H "X-Tenant-ID: acme" \
  -F "file=@document.pdf"
# → File stored in uploads/acme/...
```

**Production Deployment**:

```bash
# Update .env for production
STORAGE_TYPE=s3
S3_BUCKET=crm-platform-uploads
S3_REGION=us-east-1
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...

CACHE_TYPE=redis
REDIS_URL=redis://production-redis:6379

SMTP_HOST=smtp.sendgrid.net
SMTP_PORT=587
SMTP_USER=apikey
SMTP_PASSWORD=SG....

# Build uberjar
clojure -T:build uber

# Run with production config
java -jar target/crm-platform.jar server
```

---

## Example 6: Custom Template (Choosing Your Own Libraries)

**Scenario**: None of the pre-built templates match your needs exactly. You want workflow automation + calendar features, but not multi-tenancy or jobs.

**Goal**: Custom template with only the libraries you need.

**Template**: Custom

### Commands

```bash
bb setup
# Select: 6 (custom template)
# Select: 1 (Create new custom template)

# Interactive library selection:
# Available libraries:
#   1. core (required - always included)
#   2. observability (required - always included)
#   3. platform (required - always included)
#   4. user (authentication)
#   5. admin (auto-generated CRUD UI)
#   6. storage (file uploads)
#   7. scaffolder (code generator)
#   8. cache (Redis/in-memory)
#   9. jobs (background processing)
#   10. email (SMTP sending)
#   11. tenant (multi-tenancy)
#   12. realtime (WebSockets)
#   13. workflow (state machines)
#   14. search (full-text search)
#   15. external (Stripe, Twilio, etc.)
#   16. reports (PDF/Excel/Word)
#   17. calendar (recurring events, iCal)
#   18. geo (geocoding, distance)

# Select libraries (space-separated): 4 5 13 17
# → Selected: user, admin, workflow, calendar

# Dependencies automatically added:
# → storage (required by calendar for attachments)

# Final selection:
# ✅ core (required foundation)
# ✅ observability (required foundation)
# ✅ platform (required foundation)
# ✅ user (selected)
# ✅ admin (selected)
# ✅ storage (dependency of calendar)
# ✅ workflow (selected)
# ✅ calendar (selected)

# Confirm: y

# Save template?
# Save: y
# Name: workflow-calendar
# → Template saved to saved-templates/workflow-calendar.edn

# Continue with project creation?
# Continue: y
# Name: project-manager
# Database: 1 (PostgreSQL)
# Directory: ~/projects
# Confirm: y
```

### What You Get

```
project-manager/
├── deps.edn                    # 8 Boundary libs (1.8 KB)
├── resources/conf/dev/
│   ├── config.edn             # HTTP + DB + Auth + Workflow + Calendar
│   └── system.clj              # Custom Integrant system
├── migrations/
│   ├── 001_users.sql
│   ├── 002_workflow_definitions.sql
│   ├── 003_workflow_instances.sql
│   ├── 004_calendar_events.sql
│   └── 005_calendar_recurrence.sql
└── saved-templates/
    └── workflow-calendar.edn   # Reusable template
```

**Using Saved Template**:

Next time you need the same setup:

```bash
bb setup
# Select: 6 (custom template)
# Select: 1 (workflow-calendar)  ← Load saved template
# Name: another-project
# → Same library configuration instantly!
```

**Editing Template**:

Change library selection later:

```bash
bb setup
# Select: 6 (custom template)
# Select: 4 (Edit existing template)
# Select: 1 (workflow-calendar)

# Current libraries:
# ✅ core, observability, platform, user, admin, storage, workflow, calendar

# Add/remove libraries:
# Add: 12 (realtime - for live updates)
# Remove: 6 (storage - if you don't need file uploads)

# Updated template saved
# → Next generation includes realtime, excludes storage
```

---

## Example 7: Quick Prototype (Dry-Run Mode)

**Scenario**: You want to see what gets generated before committing.

**Goal**: Preview configuration without creating files.

**Template**: Any

### Commands

```bash
# Dry-run shows what would be generated
bb setup --template web-app --name my-prototype --db sqlite --dry-run
```

### Output

```
[DRY RUN] Would generate project with:

Template: web-app
Project name: my-prototype
Database: SQLite
Output directory: /Users/thijs/projects/my-prototype

Libraries (5):
  - boundary-core (validation, utilities)
  - boundary-observability (logging, metrics)
  - boundary-platform (HTTP, database, CLI)
  - boundary-user (authentication, authorization)
  - boundary-admin (auto-generated CRUD UI)

Configuration highlights:
  - HTTP server: port 3000
  - Database: SQLite (./dev-database.db)
  - JWT authentication: enabled
  - Session management: enabled
  - Admin UI: enabled at /admin

Files that would be created:
  ✓ deps.edn (1.6 KB)
  ✓ resources/conf/dev/config.edn (850 bytes)
  ✓ resources/conf/dev/system.clj (450 bytes)
  ✓ src/boundary/app.clj (320 bytes)
  ✓ test/boundary/app_test.clj (180 bytes)
  ✓ .env.example (420 bytes)
  ✓ .gitignore (210 bytes)
  ✓ build.clj (380 bytes)
  ✓ README.md (1.2 KB)

Total: 9 files, 7 directories

[DRY RUN] No files were created.
Remove --dry-run to generate project.
```

**Decision Point**: Looks good? Run without `--dry-run`:

```bash
bb setup --template web-app --name my-prototype --db sqlite
```

---

## Example 8: Automated CI/CD Pipeline

**Scenario**: You want to generate projects in CI/CD scripts.

**Goal**: Non-interactive project generation for automation.

**Template**: Any

### Commands

```bash
#!/bin/bash
# ci/generate-project.sh

set -e  # Exit on error

PROJECT_NAME="${1:-my-project}"
TEMPLATE="${2:-minimal}"
DB="${3:-sqlite}"

echo "Generating Boundary project: $PROJECT_NAME"

# Non-interactive mode
bb setup \
  --template "$TEMPLATE" \
  --name "$PROJECT_NAME" \
  --db "$DB" \
  --output "./generated" \
  --yes  # Overwrite if exists

echo "Project generated: ./generated/$PROJECT_NAME"

# Verify generation
cd "./generated/$PROJECT_NAME"
clojure -Spath > /dev/null  # Verify deps resolve
echo "✅ Dependencies resolved"

# Run tests (if any)
if [ -f "test/boundary/app_test.clj" ]; then
  clojure -M:test
  echo "✅ Tests passed"
fi

echo "✅ Project ready for deployment"
```

**GitHub Actions Workflow**:

```yaml
# .github/workflows/generate-project.yml
name: Generate Boundary Project

on:
  workflow_dispatch:
    inputs:
      project_name:
        description: 'Project name (kebab-case)'
        required: true
      template:
        description: 'Template (minimal, api-only, microservice, web-app, saas)'
        required: true
        default: 'minimal'

jobs:
  generate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Install Babashka
        run: bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
      
      - name: Generate Project
        run: |
          bb setup \
            --template ${{ github.event.inputs.template }} \
            --name ${{ github.event.inputs.project_name }} \
            --db postgres \
            --output ./output \
            --yes
      
      - name: Upload Generated Project
        uses: actions/upload-artifact@v3
        with:
          name: ${{ github.event.inputs.project_name }}
          path: ./output/${{ github.event.inputs.project_name }}
```

---

## Summary

| Example | Template | Use Case | Complexity |
|---------|----------|----------|------------|
| 1. Learning | Minimal | Learn Boundary | Low |
| 2. REST API | API-Only | Mobile backend | Medium |
| 3. Microservice | Microservice | K8s service | Medium |
| 4. Web App | Web-App | Auth + admin UI | High |
| 5. SaaS | SaaS | Multi-tenant platform | Very High |
| 6. Custom | Custom | Tailored setup | Variable |
| 7. Dry-Run | Any | Preview before commit | N/A |
| 8. CI/CD | Any | Automated generation | N/A |

**Next Steps**: See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for common issues and solutions.

---

**Created**: 2026-03-14  
**Sprint**: 4 (Day 17)  
**Status**: Complete  
**See Also**: [README.md](README.md), `TEMPLATE_COMPARISON.md`
