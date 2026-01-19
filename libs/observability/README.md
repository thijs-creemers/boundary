# boundary/observability

**Status:** In Development  
**Version:** 0.1.0-SNAPSHOT

Unified observability stack with pluggable adapters for logging, metrics, and error reporting.

## Installation

```clojure
{:deps {boundary/observability {:mvn/version "0.1.0"}}}
```

## Features

- **Logging**: Structured logging with adapters (no-op, stdout, slf4j, datadog)
- **Metrics**: Metrics collection with adapters (no-op, datadog)
- **Error Reporting**: Error tracking with adapters (no-op, sentry)
- **Protocol-based**: Easy to implement custom adapters

## Quick Start

```clojure
(ns myapp.core
  (:require [boundary.observability.logging.core :as log]
            [boundary.observability.metrics.core :as metrics]))

;; Logging
(log/info logger "User logged in" {:user-id user-id})

;; Metrics
(metrics/increment metrics-emitter "user.login" {:tags {:status "success"}})
```

## License

See root [LICENSE](../../LICENSE)
