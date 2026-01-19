# boundary/core

**Status:** In Development  
**Version:** 0.1.0-SNAPSHOT

Foundation library providing validation, utilities, and interceptor framework.

## Installation

```clojure
{:deps {boundary/core {:mvn/version "0.1.0"}}}
```

## Features

- **Validation Framework**: Malli-based validation with behavior-driven testing
- **Case Conversion**: kebab-case ↔ snake_case ↔ camelCase utilities
- **Type Conversion**: UUID, Instant, BigDecimal conversions
- **PII Redaction**: Sensitive data handling for logs
- **Interceptor Pipeline**: Request/response interceptor framework
- **Feature Flags**: Runtime feature toggle configuration

## Quick Start

```clojure
(ns myapp.core
  (:require [boundary.core.validation :as validation]
            [boundary.core.utils.case-conversion :as case]))

;; Case conversion
(case/kebab-case->snake-case-map {:user-id 123 :first-name "John"})
;; => {:user_id 123 :first_name "John"}

;; Validation
(validation/validate user-schema user-data)
```

## Documentation

See [docs/](../../docs/) for detailed documentation.

## Development

```bash
cd libs/core
clojure -M:test
```

## License

See root [LICENSE](../../LICENSE)
