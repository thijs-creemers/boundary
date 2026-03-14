# User Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Authentication and authorization domain: user lifecycle, credentials, sessions/tokens, and MFA flows.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.user.core.user` | Pure user-domain business logic |
| `boundary.user.core.mfa` | Pure MFA setup/verification logic |
| `boundary.user.shell.service` | Service-layer orchestration and validation |
| `boundary.user.shell.http` | Auth/user HTTP handlers |

## Security Features

### Multi-Factor Authentication (MFA)

**Status**: Production Ready

```bash
# Setup MFA
curl -X POST http://localhost:3000/api/auth/mfa/setup \
  -H "Authorization: Bearer <token>"

# Enable MFA
curl -X POST http://localhost:3000/api/auth/mfa/enable \
  -H "Authorization: Bearer <token>" \
  -d '{"secret": "...", "verificationCode": "123456"}'

# Login with MFA
curl -X POST http://localhost:3000/api/auth/login \
  -d '{"email": "user@example.com", "password": "...", "mfa-code": "123456"}'
```

**Details**: See [MFA Setup Guide](../../docs/modules/guides/pages/authentication.adoc) for complete MFA setup guide

## Gotchas

- Keep internal keys kebab-case; convert snake_case/camelCase only at DB/API boundaries.
- `JWT_SECRET` must be set for auth-related tests and runtime token operations.

## Testing

```bash
# Run user library tests
clojure -M:test:db/h2 :user

# JWT secret required for auth tests
JWT_SECRET="dev-secret-32-chars-minimum" clojure -M:test:db/h2 :user

# Update validation snapshots
UPDATE_SNAPSHOTS=true clojure -M:test:db/h2 --focus boundary.user.core.user-validation-snapshot-test
```

## Links

- [Library README](README.md)
- [MFA Setup Guide](../../docs/modules/guides/pages/authentication.adoc)
- [Root AGENTS Guide](../../AGENTS.md)
