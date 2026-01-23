# Boundary Starter

Tagline: Batteries included, boundaries enforced

A minimal starter template to try Boundary quickly.

## Quickstart

```
export BND_ENV=development
export JWT_SECRET="dev-secret-minimum-32-characters"
clojure -M:repl-clj
(require '[integrant.repl :as ig-repl])
(require 'conf.dev.system)
(ig-repl/go)
# Visit http://localhost:3000
```

## Add a module (scaffold)

```
clojure -M -m boundary.scaffolder.shell.cli-entry generate \
  --module-name product \
  --entity Product \
  --field name:string:required \
  --field sku:string:required:unique \
  --field price:decimal:required
clojure -M:migrate up
```

## Tests

```
clojure -M:test:db/h2 --watch --focus-meta :unit
clojure -M:test:db/h2
```

## Deploy

```
clojure -T:build clean && clojure -T:build uber
java -jar target/boundary-*.jar server
```

## Notes
- Dev DB: SQLite, Tests: H2 in-memory, Prod: Postgres-ready
- SSR via Hiccup + HTMX (no frontend build step)
- FC/IS enforced: core (pure), shell (I/O/validation/adapters), ports (interfaces)
