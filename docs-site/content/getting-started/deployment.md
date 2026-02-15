---
title: "Production Deployment"
weight: 20
---

# Production Deployment

**Time:** 30 minutes  
**Goal:** Deploy Boundary Framework to production with proper configuration, database migrations, and monitoring.

## Deployment Overview

Boundary supports multiple deployment strategies:

1. **Uberjar Deployment** - Single JAR file (recommended)
2. **Docker Containerization** - Containerized deployment
3. **Kubernetes/Cloud** - Orchestrated cloud deployment

This guide covers the uberjar approach with Docker containerization.

---

## Prerequisites

- Completed [5-Minute Quickstart](../guides/quickstart)
- Production database (PostgreSQL recommended)
- Production server or cloud platform
- Domain name and SSL certificate (recommended)

---

## Step 1: Configure Environment

### Create Production Configuration

Create `.env.production`:

```bash
# Application
BND_ENV=production
BND_PORT=3000

# Database (PostgreSQL)
DB_HOST=your-db-host.com
DB_PORT=5432
DB_NAME=boundary_prod
DB_USERNAME=boundary_prod
DB_PASSWORD=your-secure-password-here

# Security (REQUIRED)
JWT_SECRET=your-production-jwt-secret-minimum-32-characters-long

# Optional: External Services
REDIS_URI=redis://your-redis-host:6379
SENTRY_DSN=https://your-sentry-dsn
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USERNAME=your-smtp-user
SMTP_PASSWORD=your-smtp-password
```

### Security Best Practices

**JWT Secret Generation:**

```bash
# Generate secure random secret (32+ characters)
openssl rand -base64 32
```

**Environment Variable Management:**

- ✅ **DO:** Use secret management (AWS Secrets Manager, Vault)
- ✅ **DO:** Rotate secrets regularly
- ✅ **DO:** Use different secrets per environment
- ❌ **DON'T:** Commit secrets to version control
- ❌ **DON'T:** Share secrets via email or Slack
- ❌ **DON'T:** Use development secrets in production

See [Secrets Management](../architecture/secrets-management) for details.

---

## Step 2: Database Setup

### Create Production Database

```bash
# Connect to PostgreSQL
psql -h your-db-host -U postgres

# Create database and user
CREATE DATABASE boundary_prod;
CREATE USER boundary_prod WITH PASSWORD 'your-secure-password';
GRANT ALL PRIVILEGES ON DATABASE boundary_prod TO boundary_prod;

# Exit psql
\q
```

### Run Migrations

```bash
# Set environment
export DB_HOST=your-db-host.com
export DB_PORT=5432
export DB_NAME=boundary_prod
export DB_USERNAME=boundary_prod
export DB_PASSWORD=your-secure-password

# Apply all migrations
for migration in migrations/*.sql; do
  echo "Applying $migration..."
  psql -h $DB_HOST -U $DB_USERNAME -d $DB_NAME -f $migration
done
```

**Migration Order Matters:**
- Migrations are numbered (001, 002, 003...)
- Apply in sequential order
- Never modify existing migrations (create new ones)

### Verify Database Schema

```bash
# List tables
psql -h $DB_HOST -U $DB_USERNAME -d $DB_NAME -c "\dt"

# Expected tables:
# - users
# - sessions
# - user_audit_log
# - items (if inventory module enabled)
# - posts (if blog module created)
```

---

## Step 3: Build Production Artifact

### Build Uberjar

```bash
# Clean previous builds
clojure -T:build clean

# Build uberjar
clojure -T:build uber

# Verify artifact
ls -lh target/*.jar
```

**Expected output:**
```
-rw-r--r--  1 user  staff   45M Jan  5 19:00 target/boundary-0.1.0-standalone.jar
```

### Test Locally

```bash
# Set environment variables
export JWT_SECRET="test-secret-32-chars-minimum"
export DB_HOST=localhost
export DB_NAME=boundary_dev
export DB_USERNAME=boundary_dev
export DB_PASSWORD=dev_password

# Run uberjar
java -jar target/boundary-0.1.0-standalone.jar

# Test API
curl http://localhost:3000/health
```

**Expected response:**
```json
{
  "status": "healthy",
  "timestamp": "2026-01-05T19:00:00Z",
  "version": "0.1.0"
}
```

---

## Step 4: Docker Containerization

### Create Dockerfile

Create `Dockerfile` in project root:

```dockerfile
# Build stage
FROM clojure:temurin-21-tools-deps AS builder

WORKDIR /app

# Copy dependency files first (better caching)
COPY deps.edn build.clj /app/

# Download dependencies
RUN clojure -P -M:build

# Copy source code
COPY src /app/src
COPY resources /app/resources

# Build uberjar
RUN clojure -T:build clean && clojure -T:build uber

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy artifact from builder
COPY --from=builder /app/target/*-standalone.jar /app/boundary.jar

# Create non-root user
RUN useradd -r -u 1000 -s /bin/bash boundary && \
    chown -R boundary:boundary /app

USER boundary

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:3000/health || exit 1

EXPOSE 3000

# Run application
ENTRYPOINT ["java", "-jar", "/app/boundary.jar"]
```

### Create .dockerignore

```
# Version control
.git
.gitignore

# Build artifacts
target/
.cpcache/
.clj-kondo/
.lsp/

# Development
.env
.env.local
dev-database.db
*.log

# Documentation
docs/
*.md
!README.md

# Testing
test/
snapshots/
```

### Build Docker Image

```bash
# Build image
docker build -t boundary:latest .

# Verify image size
docker images boundary:latest
```

### Test Docker Image Locally

```bash
# Run container
docker run -d \
  --name boundary-test \
  -p 3000:3000 \
  -e JWT_SECRET="test-secret-32-chars-minimum" \
  -e DB_HOST=host.docker.internal \
  -e DB_NAME=boundary_dev \
  -e DB_USERNAME=boundary_dev \
  -e DB_PASSWORD=dev_password \
  boundary:latest

# Check logs
docker logs boundary-test

# Test API
curl http://localhost:3000/health

# Stop and remove
docker stop boundary-test && docker rm boundary-test
```

---

## Step 5: Cloud Deployment

### Option A: Docker on VPS (DigitalOcean, Linode, etc.)

**Deploy with Docker Compose:**

Create `docker-compose.prod.yml`:

```yaml
version: '3.8'

services:
  boundary:
    image: boundary:latest
    restart: always
    ports:
      - "3000:3000"
    environment:
      - BND_ENV=production
      - JWT_SECRET=${JWT_SECRET}
      - DB_HOST=${DB_HOST}
      - DB_PORT=${DB_PORT}
      - DB_NAME=${DB_NAME}
      - DB_USERNAME=${DB_USERNAME}
      - DB_PASSWORD=${DB_PASSWORD}
      - REDIS_URI=${REDIS_URI}
    depends_on:
      - postgres
      - redis
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:3000/health"]
      interval: 30s
      timeout: 3s
      retries: 3

  postgres:
    image: postgres:15-alpine
    restart: always
    environment:
      - POSTGRES_DB=${DB_NAME}
      - POSTGRES_USER=${DB_USERNAME}
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    ports:
      - "5432:5432"

  redis:
    image: redis:7-alpine
    restart: always
    volumes:
      - redis-data:/data
    ports:
      - "6379:6379"

  nginx:
    image: nginx:alpine
    restart: always
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - ./certs:/etc/nginx/certs:ro
    depends_on:
      - boundary

volumes:
  postgres-data:
  redis-data:
```

**Deploy:**

```bash
# Copy files to server
scp docker-compose.prod.yml .env.production user@your-server:/app/

# SSH to server
ssh user@your-server

# Navigate to app directory
cd /app

# Pull images
docker-compose -f docker-compose.prod.yml pull

# Start services
docker-compose -f docker-compose.prod.yml up -d

# Check status
docker-compose -f docker-compose.prod.yml ps

# View logs
docker-compose -f docker-compose.prod.yml logs -f boundary
```

### Option B: AWS ECS/Fargate

**Create ECS Task Definition:**

```json
{
  "family": "boundary-prod",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "containerDefinitions": [
    {
      "name": "boundary",
      "image": "your-ecr-repo/boundary:latest",
      "portMappings": [
        {
          "containerPort": 3000,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {"name": "BND_ENV", "value": "production"}
      ],
      "secrets": [
        {"name": "JWT_SECRET", "valueFrom": "arn:aws:secretsmanager:..."},
        {"name": "DB_PASSWORD", "valueFrom": "arn:aws:secretsmanager:..."}
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/boundary-prod",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "boundary"
        }
      },
      "healthCheck": {
        "command": ["CMD-SHELL", "curl -f http://localhost:3000/health || exit 1"],
        "interval": 30,
        "timeout": 5,
        "retries": 3,
        "startPeriod": 60
      }
    }
  ]
}
```

**Deploy to ECS:**

```bash
# Build and push to ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin your-ecr-repo
docker tag boundary:latest your-ecr-repo/boundary:latest
docker push your-ecr-repo/boundary:latest

# Update service
aws ecs update-service \
  --cluster boundary-prod \
  --service boundary-service \
  --force-new-deployment
```

### Option C: Kubernetes

**Create Kubernetes Manifests:**

`k8s/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: boundary
  namespace: production
spec:
  replicas: 3
  selector:
    matchLabels:
      app: boundary
  template:
    metadata:
      labels:
        app: boundary
    spec:
      containers:
      - name: boundary
        image: your-registry/boundary:latest
        ports:
        - containerPort: 3000
        env:
        - name: BND_ENV
          value: "production"
        - name: JWT_SECRET
          valueFrom:
            secretKeyRef:
              name: boundary-secrets
              key: jwt-secret
        - name: DB_HOST
          value: "postgres-service"
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: boundary-secrets
              key: db-password
        livenessProbe:
          httpGet:
            path: /health
            port: 3000
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /health
            port: 3000
          initialDelaySeconds: 10
          periodSeconds: 5
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"
---
apiVersion: v1
kind: Service
metadata:
  name: boundary-service
  namespace: production
spec:
  selector:
    app: boundary
  ports:
  - port: 80
    targetPort: 3000
  type: LoadBalancer
```

**Deploy:**

```bash
# Apply manifests
kubectl apply -f k8s/

# Check deployment
kubectl get pods -n production
kubectl get svc -n production

# View logs
kubectl logs -f deployment/boundary -n production
```

---

## Step 6: Monitoring and Observability

### Health Checks

**Application Health Endpoint:**

```bash
curl https://your-domain.com/health
```

**Response:**
```json
{
  "status": "healthy",
  "timestamp": "2026-01-05T19:00:00Z",
  "version": "0.1.0",
  "database": "connected",
  "cache": "connected"
}
```

### Logging

**Configure Structured Logging:**

```clojure
;; resources/conf/prod/config.edn
{:logging
 {:provider :datadog
  :level :info
  :service "boundary-prod"
  :environment "production"}}
```

**View Logs:**

```bash
# Docker
docker logs -f boundary-prod

# Kubernetes
kubectl logs -f deployment/boundary -n production

# CloudWatch (AWS)
aws logs tail /ecs/boundary-prod --follow
```

### Metrics

**Enable Metrics Collection:**

```clojure
;; resources/conf/prod/config.edn
{:metrics
 {:provider :datadog
  :api-key (System/getenv "DATADOG_API_KEY")
  :namespace "boundary"
  :tags {:environment "production"
         :service "boundary"}}}
```

**Key Metrics to Monitor:**

- **Request Rate:** HTTP requests per second
- **Response Time:** p50, p95, p99 latencies
- **Error Rate:** 4xx and 5xx responses
- **Database:** Connection pool usage, query latency
- **Cache:** Hit rate, evictions
- **Background Jobs:** Queue depth, processing time

### Error Reporting

**Configure Sentry:**

```clojure
;; resources/conf/prod/config.edn
{:error-reporting
 {:provider :sentry
  :dsn (System/getenv "SENTRY_DSN")
  :environment "production"
  :release "0.1.0"}}
```

---

## Step 7: SSL/TLS Configuration

### Using Nginx as Reverse Proxy

Create `nginx.conf`:

```nginx
upstream boundary {
    server boundary:3000;
}

server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate /etc/nginx/certs/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    location / {
        proxy_pass http://boundary;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### Using Let's Encrypt

```bash
# Install certbot
apt-get install certbot python3-certbot-nginx

# Obtain certificate
certbot --nginx -d your-domain.com

# Auto-renewal (cron)
0 0 * * * certbot renew --quiet
```

---

## Step 8: Backup and Recovery

### Database Backups

**Daily Automated Backups:**

```bash
#!/bin/bash
# backup-db.sh

BACKUP_DIR="/backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="$BACKUP_DIR/boundary_$TIMESTAMP.sql"

# Create backup
pg_dump -h $DB_HOST -U $DB_USERNAME $DB_NAME > $BACKUP_FILE

# Compress
gzip $BACKUP_FILE

# Upload to S3 (optional)
aws s3 cp $BACKUP_FILE.gz s3://your-backup-bucket/

# Clean old backups (keep 30 days)
find $BACKUP_DIR -name "boundary_*.sql.gz" -mtime +30 -delete
```

**Schedule with cron:**

```bash
0 2 * * * /scripts/backup-db.sh
```

### Disaster Recovery

**Restore from Backup:**

```bash
# Download from S3
aws s3 cp s3://your-backup-bucket/boundary_20260105.sql.gz .

# Decompress
gunzip boundary_20260105.sql.gz

# Restore
psql -h $DB_HOST -U $DB_USERNAME $DB_NAME < boundary_20260105.sql
```

---

## Deployment Checklist

### Pre-Deployment

- [ ] Environment variables configured
- [ ] JWT secret generated (32+ characters)
- [ ] Production database created
- [ ] All migrations applied
- [ ] Uberjar built and tested locally
- [ ] Docker image built and tested
- [ ] SSL certificate obtained
- [ ] Monitoring configured (logs, metrics, errors)

### Deployment

- [ ] Deploy to production environment
- [ ] Verify health check endpoint
- [ ] Test key API endpoints
- [ ] Check database connectivity
- [ ] Verify cache connectivity (if using Redis)
- [ ] Test authentication flow
- [ ] Run smoke tests

### Post-Deployment

- [ ] Monitor error rates
- [ ] Check application logs
- [ ] Verify metrics reporting
- [ ] Test user-facing features
- [ ] Configure database backups
- [ ] Document rollback procedure
- [ ] Update team on deployment status

---

## Troubleshooting

### Application Won't Start

**Check logs:**
```bash
docker logs boundary-prod
```

**Common issues:**
- Missing JWT_SECRET environment variable
- Database connection failure
- Port 3000 already in use

### Database Connection Errors

**Verify connectivity:**
```bash
psql -h $DB_HOST -U $DB_USERNAME -d $DB_NAME -c "SELECT 1"
```

**Check credentials:**
- DB_HOST, DB_PORT correct
- DB_USERNAME, DB_PASSWORD correct
- Database exists
- User has permissions

### High Memory Usage

**Check JVM settings:**
```bash
# Set max heap size
java -Xmx1g -jar boundary.jar
```

**Monitor with Docker:**
```bash
docker stats boundary-prod
```

### SSL Certificate Issues

**Verify certificate:**
```bash
openssl s_client -connect your-domain.com:443
```

**Check expiry:**
```bash
echo | openssl s_client -connect your-domain.com:443 2>/dev/null | openssl x509 -noout -dates
```

---

## Security Hardening

### Application Security

- ✅ Use HTTPS only (redirect HTTP to HTTPS)
- ✅ Set secure JWT secret (32+ characters)
- ✅ Enable CORS restrictions
- ✅ Use rate limiting (via interceptors)
- ✅ Validate all inputs (Malli schemas)
- ✅ Sanitize outputs
- ✅ Use prepared statements (HoneySQL)

### Infrastructure Security

- ✅ Run container as non-root user
- ✅ Scan images for vulnerabilities
- ✅ Use private networks for database
- ✅ Enable firewall rules
- ✅ Rotate secrets regularly
- ✅ Use secret management service
- ✅ Enable audit logging

### Monitoring Security

- Monitor failed login attempts
- Alert on unusual API activity
- Track privilege escalation
- Monitor database queries
- Alert on error spikes

---

## Next Steps

✅ **Deployed to Production!**

Now enhance your production system:

1. **[Background Jobs](../guides/background-jobs)** - Process async work reliably
2. **[Distributed Caching](../guides/caching)** - Improve performance
3. **[Multi-Factor Authentication](../guides/mfa-setup)** - Enhance security
4. **[Full-Text Search](../guides/search)** - Add search capabilities
5. **[API Pagination](../guides/pagination)** - Handle large datasets

**Operational Guides:**

- [Monitoring and Observability](../architecture/error-handling-observability)
- [Troubleshooting](../guides/troubleshooting)
- [Configuration Management](../architecture/configuration-and-env)

---

**Congratulations!** Your Boundary application is now running in production. 🚀
