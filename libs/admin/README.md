# boundary/admin

**Status:** In Development  
**Version:** 0.1.0-SNAPSHOT

Auto-generated CRUD admin interface with database schema introspection.

## Installation

```clojure
{:deps {boundary/admin {:mvn/version "0.1.0"}}}
```

## Features

- **Auto-CRUD**: Automatic CRUD interface from database schema
- **Schema Introspection**: Discover database structure at runtime
- **Filtering & Sorting**: Built-in UI controls
- **Permissions**: Role-based access control
- **Shared UI Components**: Reusable Hiccup components and icons

## Quick Start

```clojure
;; config.edn
{:boundary/admin
 {:enabled? true
  :base-path "/web/admin"
  :require-role :admin
  :entities [:users :products]}}
```

```clojure
(ns myapp.main
  (:require [boundary.admin.shell.module-wiring]
            [boundary.platform.system.wiring :as wiring]))
```

## License

See root [LICENSE](../../LICENSE)
