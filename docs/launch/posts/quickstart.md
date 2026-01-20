Title: Boundary Quickstart: from scaffold to MFA to deploy

Prerequisites
- Java 17+
- Clojure CLI tools (`clojure` on PATH)
- Optional: Docker (for containerized deploy)

1) Create a new project
- Clone the starter:
  - git clone https://github.com/thijs-creemers/boundary-starter
  - cd boundary-starter
- Set environment variables for development:
  - export BND_ENV=development
  - export JWT_SECRET="dev-secret-minimum-32-characters"
- Install dependencies (first run):
  - clojure -M:repl-clj
  - In the REPL:
    - (require '[integrant.repl :as ig-repl])
    - (ig-repl/go)

2) Run the app locally
- Start the REPL:
  - clojure -M:repl-clj
  - (require '[integrant.repl :as ig-repl])
  - (ig-repl/go)
- The server listens on port 3000 (auto-fallbacks 3001–3099 if busy).
- Make a change and reload:
  - (ig-repl/reset)

3) Add a CRUD module (scaffold)
- Generate a “product” module with required fields:
  - clojure -M -m boundary.scaffolder.shell.cli-entry generate \
    --module-name product \
    --entity Product \
    --field name:string:required \
    --field sku:string:required:unique \
    --field price:decimal:required
- Run database migrations:
  - clojure -M:migrate up
- Run tests (fast feedback with H2 in-memory):
  - clojure -M:test:db/h2 --watch --focus-meta :unit
  - clojure -M:test:db/h2
- Start/reload the system:
  - (ig-repl/reset)
- Visit the admin UI and confirm your new entity is listed and usable.

4) Enable MFA (Multi-Factor Authentication)
- From a terminal (substitute a real token):
  - curl -X POST http://localhost:3000/api/auth/mfa/setup \
    -H "Authorization: Bearer <token>"
  - curl -X POST http://localhost:3000/api/auth/mfa/enable \
    -H "Authorization: Bearer <token>" \
    -d '{"secret": "...", "verificationCode": "123456"}'
- Log in with MFA:
  - curl -X POST http://localhost:3000/api/auth/login \
    -d '{"email": "user@example.com", "password": "...", "mfa-code": "123456"}'
- Tip: Put JWT_SECRET and any DB credentials in your environment, not in code.

5) Observability out of the box
- Service and persistence operations run inside interceptors that provide:
  - Structured logs and metrics with operation names and typed errors.
  - Pluggable providers (no‑op for development, Datadog, Sentry where configured).
- You get consistent telemetry without per‑endpoint boilerplate.

6) Deploy: Uberjar
- Build:
  - clojure -T:build clean && clojure -T:build uber
- Run:
  - java -jar target/boundary-*.jar server
- Set production env:
  - export BND_ENV=production
  - export JWT_SECRET="replace-with-strong-secret"
  - export DB_PASSWORD="..." (and other DB env as needed)

7) Deploy: Docker (minimal)
- Example Dockerfile (place at the project root):
  - FROM eclipse-temurin:17-jre
  - WORKDIR /app
  - COPY target/boundary-*.jar app.jar
  - ENV BND_ENV=production
  - EXPOSE 3000
  - CMD ["java","-jar","/app/app.jar","server"]
- Build and run:
  - clojure -T:build clean && clojure -T:build uber
  - docker build -t your-org/boundary-app .
  - docker run -e JWT_SECRET=replace-with-strong-secret -p 3000:3000 your-org/boundary-app

Troubleshooting tips
- REPL not picking up changes to defrecord instances: (ig-repl/halt) then (ig-repl/go).
- Unbalanced parentheses: use clj-paren-repair <file>.
- Tests slow/failing: run unit-only first: clojure -M:test:db/h2 --focus-meta :unit.

Next steps
- Explore the admin UI entity config under resources/conf/dev/admin/.
- Add more entities with the scaffolder and migrate.
- Integrate your observability provider if you run beyond development.