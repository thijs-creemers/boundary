# Realtime Module

**WebSocket-based real-time communication for Boundary Framework**

Similar to **Phoenix Channels** (Elixir) or **Socket.io** (Node.js), this module provides WebSocket support with:

- ✅ JWT-based authentication
- ✅ Point-to-point messaging
- ✅ Broadcast messaging
- ✅ Role-based routing
- ✅ Pure functional core (FC/IS pattern)
- ✅ Pluggable adapters
- ✅ Integration with boundary/user authentication

---

## Table of Contents

- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
- [Usage Examples](#usage-examples)
- [Configuration](#configuration)
- [Message Handlers](#message-handlers)
- [Monitoring](#monitoring)
- [Production Deployment](#production-deployment)
- [API Reference](#api-reference)

---

## Quick Start

**Coming soon** - See [ADR-003: WebSocket Architecture](../../docs/adr/ADR-003-websocket-architecture.md) for design details.

---

## Core Concepts

### Primary Use Cases

1. **Live Notifications**: Real-time alerts when events occur (new orders, messages, etc.)
2. **Dashboard Updates**: Live metrics and status updates without page refresh
3. **Job Progress**: Real-time progress updates for long-running operations

### Message Routing

- **Point-to-Point**: Send to specific user (all their connections)
- **Broadcast**: Send to all connected clients
- **Role-Based**: Send to users with specific role (e.g., all admins)
- **Connection-Specific**: Send to individual connection (for job tracking)

---

## Installation

**deps.edn**:
```clojure
{:deps {io.github.thijs-creemers/boundary-realtime {:mvn/version "0.1.0"}}}
```

**Leiningen**:
```clojure
[io.github.thijs-creemers/boundary-realtime "0.1.0"]
```

---

## Architecture

### Module Structure

```
libs/realtime/
├── src/boundary/realtime/
│   ├── core/               # Pure business logic
│   │   ├── connection.clj  # Connection state (pure)
│   │   ├── message.clj     # Message validation/routing (pure)
│   │   └── auth.clj        # JWT validation logic (pure)
│   ├── ports.clj           # Protocol definitions
│   ├── schema.clj          # Malli schemas
│   └── shell/              # I/O adapters
│       ├── service.clj     # Shell orchestration
│       ├── websocket_adapter.clj  # WebSocket I/O
│       └── connection_registry.clj # Connection store
└── test/                   # Tests
```

### Functional Core / Imperative Shell

- **Core**: Pure functions for connection management, message routing, auth validation
- **Shell**: WebSocket I/O, connection registry (atom), logging, metrics
- **Ports**: Protocols for dependency injection and testability

---

## Status

🚧 **In Development** - Phase 5 of roadmap

**Current Progress**:
- [x] Architecture design (ADR-003)
- [ ] Directory structure (Task 5.1)
- [ ] Core layer implementation
- [ ] Shell layer implementation
- [ ] Integration tests
- [ ] Documentation

---

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `malli` | 0.20.0 | Schema validation |
| `cheshire` | 6.1.0 | JSON encoding/decoding |
| `tools.logging` | 1.3.1 | Logging |

---

## Requirements

- Clojure 1.12+
- boundary/core
- boundary/observability
- boundary/platform
- boundary/user (for JWT authentication)

---

## Explicit Non-Goals (Initial Version)

- ❌ **No rooms/channels**: Use role-based or metadata-based routing instead
- ❌ **No presence tracking**: No "who's online" features initially
- ❌ **No Redis pub/sub**: Single-server deployment (multi-server in future)
- ❌ **No complex message formats**: JSON only (EDN may be added later)

---

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
