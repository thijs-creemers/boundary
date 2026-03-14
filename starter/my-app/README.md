# my-app

**Template**: Minimal Boundary Project
**Description**: Bare minimum to run a Boundary application - HTTP server, database, and health checks only

---

## Features

- ✅ Functional Core / Imperative Shell architecture
- ✅ Integrant-based system lifecycle
- ✅ Database connection pooling
- ✅ HTTP server with health checks
- ✅ Zero-config SQLite database
- ✅ HTTP health check endpoint
- ✅ REPL-driven development with Integrant

---

## Quick Start

```bash
# Set environment variables
export BND_ENV=development
export JWT_SECRET="eaqI4KYnhxYcPgxC8qHWjjyJWbzUwiak"

# Start REPL
clojure -M:repl-clj

# In REPL
(require '[integrant.repl :as ig-repl])
(require 'conf.dev.system)
(ig-repl/go)

# Visit http://localhost:3000
```

---

## Next Steps

1. Start the REPL with `clojure -M:repl-clj`
2. Start the system with `(ig-repl/go)`
3. Visit http://localhost:3000/health
4. Add your first module with `bb scaffold`
5. Explore the codebase structure in `src/boundary/app.clj`
6. Run tests with `clojure -M:test:db/h2`

---

## Testing

`starter` includes two kinds of tests:
- Script-level starter tests (Babashka-driven) in this repository.
- Generated app tests (project dependencies required) in your generated project.

If you run generated app tests with bare `bb -e/load-file`, dependency namespaces like `integrant.core` may fail to resolve.
Use the project test alias instead:

```bash
# Run all tests
clojure -M:test:db/h2

# Run with watch
clojure -M:test:db/h2 --watch

# Run specific test
clojure -M:test:db/h2 --focus your-test-ns
```

---

## Build

```bash
# Build uberjar
clojure -T:build clean
clojure -T:build uber

# Run standalone
java -jar target/my-app-*.jar
```

---

## Project Structure

```
my-app/
├── src/boundary/          # Application code (FC/IS pattern)
├── test/boundary/         # Tests
├── resources/
│   ├── conf/dev/          # Configuration files
│   └── public/            # Static assets
├── deps.edn               # Dependencies
├── build.clj              # Build configuration
└── README.md              # This file
```

---

## Documentation

- [Boundary Framework](https://github.com/thijs-creemers/boundary)
- [AGENTS.md](https://github.com/thijs-creemers/boundary/blob/main/AGENTS.md) - Commands and conventions

---

**Generated with Boundary Framework**
