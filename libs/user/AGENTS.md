# User Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

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

**Details**: See [MFA API documentation](../../docs-site/content/api/mfa.md) for complete MFA setup guide

## Testing

```bash
# Run user library tests
clojure -M:test:db/h2 :user

# JWT secret required for auth tests
JWT_SECRET="dev-secret-32-chars-minimum" clojure -M:test:db/h2 :user

# Update validation snapshots
UPDATE_SNAPSHOTS=true clojure -M:test:db/h2 --focus boundary.user.core.user-validation-snapshot-test
```
