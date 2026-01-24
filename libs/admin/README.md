# boundary/admin

[![Status](https://img.shields.io/badge/status-in%20development-yellow)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()

Auto-generated CRUD admin interface with database schema introspection, filtering, sorting, and role-based access control.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {io.github.thijs-creemers/boundary-admin {:mvn/version "0.1.0"}}}
```

**Leiningen**:
```clojure
[io.github.thijs-creemers/boundary-admin "0.1.0"]
```

## Features

| Feature | Description |
|---------|-------------|
| **Auto-CRUD** | Automatic CRUD interface from database schema |
| **Schema Introspection** | Discover database structure at runtime |
| **Filtering** | Dynamic filter builder with multiple operators |
| **Sorting** | Click-to-sort column headers |
| **Pagination** | Built-in pagination for large datasets |
| **Role-based Access** | Restrict access by user role |
| **Field Ordering** | Configure preferred field order in forms |
| **Field Grouping** | Group related fields with section headings |
| **Shared UI Components** | Reusable Hiccup components and Lucide icons |
| **HTMX Integration** | Dynamic updates without full page reloads |

## Requirements

- Clojure 1.12+
- boundary/platform
- boundary/user

## Quick Start

### Module Registration

```clojure
(ns myapp.main
  (:require [boundary.admin.shell.module-wiring]  ; Auto-registers module
            [boundary.user.shell.module-wiring]   ; Required for auth
            [boundary.platform.system.wiring :as wiring]))

(defn -main [& args]
  (wiring/start!))
```

### Configuration

```clojure
;; config.edn
{:boundary/admin
 {:enabled? true
  :base-path "/web/admin"
  :require-role :admin
  
  ;; Entity configurations
  :entities
  {:users
   {:label "Users"
    :list-fields [:email :name :role :created-at]
    :search-fields [:email :name]
    :hide-fields #{:password-hash :deleted-at}
    :readonly-fields #{:id :created-at :updated-at}}
   
   :products
   {:label "Products"
    :list-fields [:name :sku :price :active]
    :search-fields [:name :sku :description]
    :field-types {:price :decimal
                  :active :boolean}}}
  
  :pagination
  {:default-limit 25
   :max-limit 100}}}
```

### Entity Configuration Options

| Option | Type | Description |
|--------|------|-------------|
| `:label` | string | Display name for the entity |
| `:list-fields` | vector | Fields to show in list view |
| `:search-fields` | vector | Fields to include in search |
| `:hide-fields` | set | Fields to never show |
| `:readonly-fields` | set | Fields that cannot be edited |
| `:field-types` | map | Override field type detection |
| `:field-labels` | map | Custom labels for fields |
| `:field-order` | vector | Preferred order of fields in forms |
| `:field-groups` | vector | Group fields with section headings |
| `:ui` | map | Entity-level UI config overrides |
| `:actions` | vector | Custom actions (`:view`, `:edit`, `:delete`) |

### Field Ordering

Control the order of fields in forms and detail views:

```clojure
{:users
 {:field-order [:email :name :role :active :created-at :updated-at]}}
```

Fields listed in `:field-order` appear first in the specified order. Fields not listed appear after in their original order.

### Field Grouping

Group related fields together with section headings:

```clojure
{:users
 {:field-groups
  [{:id :identity :label "Identity" :fields [:email :name]}
   {:id :access :label "Access" :fields [:role :active]}
   {:id :meta :label "Metadata" :fields [:created-at :updated-at]}]}}
```

Fields not assigned to any group appear in an "Other" section. Customize this label:

```clojure
;; Global configuration (in :boundary/admin)
{:ui {:field-grouping {:other-label "Additional Fields"}}}

;; Or per-entity override
{:users
 {:ui {:field-grouping {:other-label "Advanced"}}}}
```

**Note**: Groups only contain editable fields. Empty groups are automatically excluded.

### Custom Actions

```clojure
;; Add custom actions to entities
{:users
 {:actions [:view :edit :delete
            {:id :impersonate
             :label "Impersonate"
             :icon :user-check
             :handler 'myapp.admin/impersonate-user
             :confirm? true}]}}
```

### Programmatic Access

```clojure
(ns myapp.admin-custom
  (:require [boundary.admin.ports :as admin-ports]))

;; List entities with filtering
(admin-ports/list-entities admin-service :users
  {:filters [{:field :role :op := :value "admin"}]
   :sort {:field :created-at :direction :desc}
   :limit 20})

;; Get single entity
(admin-ports/get-entity admin-service :users user-id)

;; Update entity
(admin-ports/update-entity admin-service :users user-id
  {:name "Updated Name"})

;; Delete entity
(admin-ports/delete-entity admin-service :users user-id)
```

## Web Routes

| Path | Method | Description |
|------|--------|-------------|
| `/web/admin` | GET | Dashboard |
| `/web/admin/:entity` | GET | List entities |
| `/web/admin/:entity/new` | GET | Create form |
| `/web/admin/:entity/new` | POST | Create entity |
| `/web/admin/:entity/:id` | GET | View entity |
| `/web/admin/:entity/:id/edit` | GET | Edit form |
| `/web/admin/:entity/:id` | PUT | Update entity |
| `/web/admin/:entity/:id` | DELETE | Delete entity |

## UI Components

### Shared Components

```clojure
(ns myapp.ui
  (:require [boundary.shared.ui.core.components :as ui]
            [boundary.shared.ui.core.icons :as icons]))

;; Buttons
(ui/button {:variant :primary :size :md} "Save")
(ui/button {:variant :danger :confirm? true} "Delete")

;; Icons (Lucide)
(icons/icon :users {:size 24})
(icons/icon :edit {:size 16 :class "text-blue"})

;; Tables
(ui/table {:columns [:name :email :role]
           :rows users
           :sortable? true})

;; Forms
(ui/form-field {:name :email :type :email :required? true})
(ui/form-field {:name :role :type :select :options roles})
```

### Available Icons

50+ Lucide icons are included. Common icons:
- Navigation: `:home`, `:menu`, `:arrow-left`, `:arrow-right`
- Actions: `:edit`, `:trash`, `:plus`, `:check`, `:x`
- Objects: `:user`, `:users`, `:file`, `:folder`, `:settings`
- Indicators: `:alert-triangle`, `:info`, `:check-circle`

## Module Structure

```
src/boundary/
├── admin/
│   ├── core/
│   │   ├── ui.clj              # Admin UI components
│   │   └── schema-introspection.clj
│   ├── ports.clj               # Admin service protocol
│   └── shell/
│       ├── service.clj         # Admin service
│       ├── http.clj            # HTTP handlers
│       └── module-wiring.clj   # Integrant config
└── shared/
    └── ui/
        └── core/
            ├── components.clj   # Reusable UI components
            ├── icons.clj        # Lucide icon definitions
            └── layout.clj       # Page layouts
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `boundary/platform` | 0.1.0 | HTTP, database |
| `boundary/user` | 0.1.0 | Authentication |
| `hiccup` | 2.0.0 | HTML generation |

## Styling

The admin interface uses:
- **Pico CSS** for base styling
- **CSS custom properties** for theming
- **Dark mode** support via `prefers-color-scheme`

Custom styling can be added via `resources/public/css/admin.css`.

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│              boundary/admin             │
│      (auto-CRUD, schema introspection)  │
└───────────┬─────────────┬───────────────┘
            │             │
            ▼             ▼
┌───────────────┐ ┌───────────────────────┐
│ boundary/user │ │   boundary/platform   │
└───────┬───────┘ └───────────┬───────────┘
        │                     │
        └──────────┬──────────┘
                   ▼
┌─────────────────────────────────────────┐
│  boundary/observability + boundary/core │
└─────────────────────────────────────────┘
```

## Development

```bash
# Run tests
cd libs/admin
clojure -M:test

# Lint
clojure -M:clj-kondo --lint src test
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
