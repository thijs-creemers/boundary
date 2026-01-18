# boundary/user

**Status:** In Development  
**Version:** 0.1.0-SNAPSHOT

Complete user management and authentication system with MFA support.

## Installation

```clojure
{:deps {boundary/user {:mvn/version "0.1.0"}}}
```

## Features

- **User Management**: CRUD operations for users
- **Authentication**: Password hashing (bcrypt) and JWT tokens
- **Sessions**: Session management and validation
- **Multi-Factor Auth**: TOTP-based MFA
- **Audit Logging**: Track user actions
- **Account Security**: Lockout and rate limiting
- **Web UI**: Pre-built user management interfaces

## Quick Start

```clojure
(ns myapp.main
  (:require [boundary.user.shell.module-wiring]  ; Auto-registers module
            [boundary.platform.system.wiring :as wiring]))

(defn -main [& args]
  (wiring/start!))
```

## License

See root [LICENSE](../../LICENSE)
