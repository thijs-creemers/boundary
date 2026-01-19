# boundary/platform

**Status:** In Development  
**Version:** 0.1.0-SNAPSHOT

Core infrastructure for web applications: database, HTTP routing, pagination, and search.

## Installation

```clojure
{:deps {boundary/platform {:mvn/version "0.1.0"}
        ;; Choose your database driver
        org.postgresql/postgresql {:mvn/version "42.7.8"}}}
```

## Features

- **Multi-Database Support**: SQLite, PostgreSQL, MySQL, H2
- **HTTP Routing**: Reitit-based with interceptors
- **API Versioning**: Built-in versioning support
- **Pagination**: Offset and cursor-based pagination
- **Search**: Full-text search with ranking
- **Migrations**: Database migration management
- **System Lifecycle**: Integrant-based lifecycle management
- **Module System**: Dynamic module registration

## Quick Start

```clojure
(ns myapp.main
  (:require [boundary.platform.system.wiring :as wiring]
            [boundary.config :as config]))

(defn -main [& args]
  (let [system (wiring/start!)]
    ;; System running
    system))
```

## License

See root [LICENSE](../../LICENSE)
