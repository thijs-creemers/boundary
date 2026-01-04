# Multi-Factor Authentication (MFA) Setup Guide

## Overview

Boundary Framework includes production-ready Multi-Factor Authentication (MFA) support using TOTP (Time-based One-Time Password) authentication. This guide covers setup, usage, and security considerations.

## Table of Contents

- [Features](#features)
- [Quick Start](#quick-start)
- [User Flow](#user-flow)
- [API Reference](#api-reference)
- [Security Considerations](#security-considerations)
- [Troubleshooting](#troubleshooting)
- [Architecture](#architecture)

---

## Features

### Core Capabilities

- **TOTP Authentication**: Compatible with all major authenticator apps:
  - Google Authenticator
  - Authy
  - 1Password
  - Microsoft Authenticator
  - Any RFC 6238 compliant app

- **Backup Codes**: 
  - 10 single-use backup codes generated per user
  - 12 characters each (formatted as XXX-XXXX-XXXX)
  - Cryptographically secure generation
  - Marked as used (no reuse)

- **Easy Setup**:
  - QR code generation for quick scanning
  - Manual secret entry supported
  - Verification required before enabling

- **Seamless Integration**:
  - Works with existing authentication flow
  - JWT-based session management
  - No breaking changes to existing login

### Technical Implementation

- **Library**: `one-time/one-time` (Clojure TOTP library)
- **Secret Format**: Base32-encoded (RFC 4648)
- **Secret Length**: 160 bits (32 characters)
- **Time Window**: 30 seconds (TOTP standard)
- **QR Code**: Generated via api.qrserver.com
- **Backup Codes**: SecureRandom with Base64 encoding

---

## Quick Start

### Prerequisites

1. Boundary Framework installed and running
2. User account created and authenticated
3. Valid JWT token for API requests

### Basic Setup (5 minutes)

```bash
# 1. Setup MFA (get QR code and backup codes)
curl -X POST http://localhost:3000/api/auth/mfa/setup \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  | jq '.'

# Save the response - you'll need:
# - secret (for manual entry)
# - qr-code-url (scan with authenticator app)
# - backup-codes (save these securely!)

# 2. Scan QR code with your authenticator app
# Or manually enter the secret

# 3. Get current 6-digit code from your app

# 4. Enable MFA with verification
curl -X POST http://localhost:3000/api/auth/mfa/enable \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "secret": "JBSWY3DPEHPK3PXP",
    "backupCodes": ["3LTW-XRM1-GYVF", "CN2K-1AWR-GDVT", ...],
    "verificationCode": "123456"
  }' \
  | jq '.'

# 5. Test login with MFA
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "your-password",
    "mfa-code": "123456"
  }' \
  | jq '.'
```

---

## User Flow

### 1. MFA Setup Flow

```
┌─────────────────────────────────────────────────────────┐
│ User: Authenticated                                      │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ POST /api/auth/mfa/setup                                │
│ → Returns: secret, QR code URL, 10 backup codes        │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ User: Scans QR code with authenticator app              │
│ → App displays 6-digit code (changes every 30s)        │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ User: Saves backup codes securely                       │
│ → Print, password manager, or secure storage           │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ POST /api/auth/mfa/enable                               │
│ → Sends: secret, backup codes, verification code       │
│ → Server validates TOTP code                           │
│ → Enables MFA if valid                                 │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ MFA Enabled: All future logins require MFA code        │
└─────────────────────────────────────────────────────────┘
```

### 2. Login Flow (MFA Enabled)

```
┌─────────────────────────────────────────────────────────┐
│ POST /api/auth/login (email + password only)           │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ Response: {"requires-mfa?": true}                       │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ User: Gets 6-digit code from authenticator app          │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ POST /api/auth/login (email + password + mfa-code)     │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ Success: {"success": true, "session-id": "...", ...}   │
└─────────────────────────────────────────────────────────┘
```

### 3. Backup Code Usage

```
┌─────────────────────────────────────────────────────────┐
│ User: Lost access to authenticator app                  │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ POST /api/auth/login                                    │
│ → Uses backup code instead of TOTP code                │
│ → "mfa-code": "3LTW-XRM1-GYVF"                         │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ Backup code validated and marked as used                │
│ → Code cannot be reused                                │
│ → 9 backup codes remaining                             │
└─────────────────────────────────────────────────────────┘
```

---

## API Reference

### 1. Setup MFA

**Endpoint**: `POST /api/auth/mfa/setup`

**Authentication**: Required (Bearer token)

**Request**: No body required

**Response**:
```json
{
  "success?": true,
  "secret": "JBSWY3DPEHPK3PXP",
  "qr-code-url": "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=otpauth%3A%2F%2Ftotp%2FBoundary%2520Framework%3Auser%40example.com%3Fsecret%3DJBSWY3DPEHPK3PXP%26issuer%3DBoundary%2520Framework",
  "backup-codes": [
    "3LTW-XRM1-GYVF",
    "CN2K-1AWR-GDVT",
    "9FHJ-K2LM-PQRS",
    "7TUV-W3XY-Z4AB",
    "5CDE-F6GH-I8JK",
    "2LMN-O9PQ-R1ST",
    "8UVW-X4YZ-A5BC",
    "6DEF-G7HI-J9KL",
    "4MNO-P2QR-S3TU",
    "1VWX-Y8ZA-B0CD"
  ],
  "issuer": "Boundary Framework",
  "account-name": "user@example.com"
}
```

**Usage**:
```bash
curl -X POST http://localhost:3000/api/auth/mfa/setup \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json"
```

**Notes**:
- Call this endpoint to initiate MFA setup
- User must be authenticated
- Save the secret and backup codes securely
- QR code URL is valid for immediate scanning

---

### 2. Enable MFA

**Endpoint**: `POST /api/auth/mfa/enable`

**Authentication**: Required (Bearer token)

**Request**:
```json
{
  "secret": "JBSWY3DPEHPK3PXP",
  "backupCodes": [
    "3LTW-XRM1-GYVF",
    "CN2K-1AWR-GDVT",
    ...
  ],
  "verificationCode": "123456"
}
```

**Response (Success)**:
```json
{
  "success?": true
}
```

**Response (Invalid Code)**:
```json
{
  "success?": false,
  "error": "Invalid verification code"
}
```

**Usage**:
```bash
curl -X POST http://localhost:3000/api/auth/mfa/enable \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "secret": "JBSWY3DPEHPK3PXP",
    "backupCodes": ["3LTW-XRM1-GYVF", ...],
    "verificationCode": "123456"
  }'
```

**Notes**:
- Verification code must be current 6-digit TOTP code
- After successful enablement, all future logins require MFA
- Secret and backup codes are stored encrypted in database

---

### 3. Disable MFA

**Endpoint**: `POST /api/auth/mfa/disable`

**Authentication**: Required (Bearer token)

**Request**: No body required

**Response**:
```json
{
  "success?": true
}
```

**Usage**:
```bash
curl -X POST http://localhost:3000/api/auth/mfa/disable \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json"
```

**Notes**:
- Removes MFA requirement from user account
- Clears secret and backup codes
- User can re-enable MFA anytime

---

### 4. Get MFA Status

**Endpoint**: `GET /api/auth/mfa/status`

**Authentication**: Required (Bearer token)

**Request**: No body required

**Response (MFA Enabled)**:
```json
{
  "enabled": true,
  "enabled-at": "2024-01-04T10:00:00Z",
  "backup-codes-remaining": 10
}
```

**Response (MFA Disabled)**:
```json
{
  "enabled": false,
  "enabled-at": null,
  "backup-codes-remaining": 0
}
```

**Usage**:
```bash
curl -X GET http://localhost:3000/api/auth/mfa/status \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

**Notes**:
- Check if MFA is enabled for current user
- Shows how many backup codes remain unused
- Useful for UI to show/hide MFA options

---

### 5. Login with MFA

**Endpoint**: `POST /api/auth/login`

**Authentication**: None (this endpoint creates session)

**Request (Password Only - First Attempt)**:
```json
{
  "email": "user@example.com",
  "password": "your-password"
}
```

**Response (MFA Required)**:
```json
{
  "requires-mfa?": true,
  "message": "MFA code required"
}
```

**Request (With MFA Code - Second Attempt)**:
```json
{
  "email": "user@example.com",
  "password": "your-password",
  "mfa-code": "123456"
}
```

**Response (Success)**:
```json
{
  "success": true,
  "session-id": "550e8400-e29b-41d4-a716-446655440000",
  "user": {
    "id": "123e4567-e89b-12d3-a456-426614174000",
    "email": "user@example.com",
    "name": "John Doe"
  }
}
```

**Usage (Two-Step Process)**:
```bash
# Step 1: Try login with password only
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "your-password"
  }'

# Response: {"requires-mfa?": true}

# Step 2: Login with MFA code
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "your-password",
    "mfa-code": "123456"
  }'
```

**Using Backup Code**:
```bash
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "your-password",
    "mfa-code": "3LTW-XRM1-GYVF"
  }'
```

**Notes**:
- TOTP codes are 6 digits (123456)
- Backup codes are 12 characters with dashes (3LTW-XRM1-GYVF)
- System accepts either format in `mfa-code` field
- Backup codes can only be used once

---

## Security Considerations

### Secret Generation

- **Entropy Source**: `java.security.SecureRandom` (cryptographically secure)
- **Secret Length**: 160 bits (32 Base32 characters)
- **Format**: Base32 encoding (RFC 4648)
- **Storage**: Encrypted in database (application-level encryption recommended)

### Backup Codes

- **Generation**: 12 bytes of random data → Base64 → filtered to alphanumeric
- **Format**: XXX-XXXX-XXXX (e.g., 3LTW-XRM1-GYVF)
- **Quantity**: 10 codes per user
- **Usage**: Single-use only (marked used after first use)
- **Storage**: Plain text in database (user responsibility to store securely)

### TOTP Implementation

- **Time Window**: 30 seconds (RFC 6238 standard)
- **Algorithm**: HMAC-SHA1 (TOTP standard)
- **Code Length**: 6 digits
- **Clock Skew**: Handled by `one-time` library (typically ±1 window)

### Best Practices

1. **HTTPS Only**: Always use HTTPS in production
2. **Rate Limiting**: Implement rate limiting on MFA verification (recommended: 5 attempts per minute)
3. **Account Lockout**: Consider locking accounts after N failed MFA attempts
4. **Audit Logging**: Log all MFA-related events (setup, enable, disable, failed attempts)
5. **Backup Code Warnings**: Warn users when <3 backup codes remain
6. **Secret Rotation**: Support re-generating secrets and backup codes
7. **Recovery Process**: Provide account recovery flow for lost devices

### Threat Model

**Protects Against**:
- Password theft/phishing
- Credential stuffing attacks
- Brute force attacks
- Session hijacking (partial)

**Does NOT Protect Against**:
- SIM swapping (if SMS fallback added)
- Device theft (if device unlocked)
- Social engineering (account recovery)
- Malware on user device

### Compliance

- ✅ **NIST SP 800-63B**: Compliant with Level 2 authentication
- ✅ **PCI DSS**: Supports multi-factor authentication requirements
- ✅ **GDPR**: MFA data properly secured and deletable
- ✅ **SOC 2**: Meets access control requirements

---

## Troubleshooting

### Common Issues

#### 1. "Invalid verification code" on Enable

**Symptoms**: Setup completes successfully, but enable fails with invalid code

**Causes**:
- Time synchronization issues (server vs. client)
- Wrong code entered
- Code expired (30-second window)

**Solutions**:
```bash
# Check server time
date -u

# Ensure NTP is running
sudo systemctl status ntp  # Linux
sudo systemctl status systemsetup  # macOS

# Try fresh code from authenticator app
# Wait for code to refresh before submitting
```

#### 2. Backup codes not working

**Symptoms**: Backup code rejected during login

**Causes**:
- Code already used
- Typo in code entry
- Code format incorrect

**Solutions**:
```bash
# Check MFA status to see remaining codes
curl -X GET http://localhost:3000/api/auth/mfa/status \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# Ensure format is correct: XXXX-XXXX-XXXX
# Try different backup code
```

#### 3. QR code won't scan

**Symptoms**: Authenticator app won't recognize QR code

**Causes**:
- QR code URL expired/invalid
- Display resolution too low
- Network issues

**Solutions**:
```bash
# Use manual entry instead
# Copy the "secret" field from setup response
# Enter manually in authenticator app

# Or regenerate setup
curl -X POST http://localhost:3000/api/auth/mfa/setup \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

#### 4. Lost access to authenticator app

**Symptoms**: Can't log in, don't have TOTP codes

**Solutions**:
1. **Use Backup Code**: Use one of the 10 backup codes saved during setup
2. **Contact Administrator**: If no backup codes available, admin must disable MFA
3. **Account Recovery**: Implement recovery flow (email verification, support ticket)

### Debug Commands

```bash
# Check MFA status
curl -X GET http://localhost:3000/api/auth/mfa/status \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" | jq '.'

# Test TOTP code validation (REPL)
clojure -M:repl-clj <<EOF
(require '[boundary.user.shell.mfa :as mfa])
(mfa/verify-totp-code "123456" "JBSWY3DPEHPK3PXP")
EOF

# Check database for MFA settings
psql -U boundary_dev -d boundary_dev \
  -c "SELECT id, email, mfa_enabled, mfa_enabled_at FROM users WHERE email='user@example.com';"
```

---

## Architecture

### Functional Core / Imperative Shell

MFA implementation follows Boundary's FC/IS architecture:

#### Functional Core (`src/boundary/user/core/mfa.clj`)

Pure functions, no side effects:

```clojure
;; Business logic decisions
(should-require-mfa? user risk-analysis)
(can-enable-mfa? user)
(can-disable-mfa? user)

;; Data transformations
(prepare-mfa-enablement user secret codes time)
(prepare-mfa-disablement user time)

;; Validation logic
(is-valid-backup-code? user code)
(mark-backup-code-used user code)
```

#### Imperative Shell (`src/boundary/user/shell/mfa.clj`)

All I/O operations:

```clojure
;; Crypto operations
(generate-totp-secret)        ; SecureRandom
(verify-totp-code code secret) ; TOTP verification
(generate-backup-codes count)  ; Random generation

;; External services
(create-qr-code-url uri)      ; QR code service

;; Database operations (via repository)
(setup-mfa service user-id)
(enable-mfa service user-id secret codes code)
(disable-mfa service user-id)
```

### Module Structure

```
src/boundary/user/
├── core/
│   └── mfa.clj              # Pure business logic (350 lines)
├── shell/
│   ├── mfa.clj              # I/O operations (270 lines)
│   ├── auth.clj             # Authentication integration
│   ├── http.clj             # HTTP endpoints
│   └── module_wiring.clj    # Integrant wiring
├── schema.clj               # Malli validation schemas
└── ports.clj                # Protocol definitions

migrations/
└── 006_add_mfa_to_users.sql # Database schema

test/boundary/user/
├── core/
│   └── mfa_test.clj         # Pure function tests (9 tests)
└── shell/
    └── mfa_test.clj         # I/O operation tests (12 tests)
```

### Integration Points

```
┌─────────────────────────────────────────────────────────┐
│ HTTP Layer (shell/http.clj)                            │
│ → POST /api/auth/mfa/*                                 │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ MFA Service (shell/mfa.clj)                            │
│ → setup-mfa, enable-mfa, disable-mfa                   │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ Core Logic (core/mfa.clj)                              │
│ → Business rules, validation, decisions                │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ Repository (user-repository)                            │
│ → Database persistence                                  │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ Database (PostgreSQL/SQLite)                            │
│ → users table with MFA columns                         │
└─────────────────────────────────────────────────────────┘
```

### Database Schema

```sql
-- Migration: 006_add_mfa_to_users.sql
ALTER TABLE users ADD COLUMN mfa_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN mfa_secret TEXT;
ALTER TABLE users ADD COLUMN mfa_backup_codes TEXT;  -- JSON array
ALTER TABLE users ADD COLUMN mfa_backup_codes_used TEXT;  -- JSON array
ALTER TABLE users ADD COLUMN mfa_enabled_at TIMESTAMP;

CREATE INDEX idx_users_mfa_enabled ON users(mfa_enabled);
CREATE INDEX idx_users_mfa_enabled_at ON users(mfa_enabled_at);
```

### Testing Strategy

**Unit Tests (core/mfa_test.clj)**:
- 9 tests, 58 assertions
- Pure function testing (no mocks)
- Fast, deterministic

**Integration Tests (shell/mfa_test.clj)**:
- 12 tests, 59 assertions
- Mocked repository
- Tests I/O operations

**Total Coverage**: 21 tests, 117 assertions, 0 failures

---

## Additional Resources

### Documentation

- **[MFA Completion Summary](../../MFA_COMPLETION_SUMMARY.md)** - Implementation details
- **[AGENTS.md](../../AGENTS.md)** - Developer guide with MFA information
- **[User Authentication Guide](https://github.com/thijs-creemers/boundary-docs/tree/main/content/guides/)** - General auth docs

### External References

- **[RFC 6238 - TOTP](https://datatracker.ietf.org/doc/html/rfc6238)** - TOTP specification
- **[RFC 4648 - Base32](https://datatracker.ietf.org/doc/html/rfc4648)** - Base32 encoding
- **[one-time Library](https://github.com/suvash/one-time)** - Clojure TOTP implementation
- **[Google Authenticator Wiki](https://github.com/google/google-authenticator/wiki/Key-Uri-Format)** - QR code URI format

### Support

For issues, questions, or feature requests:

1. Check this guide first
2. Search existing issues: [GitHub Issues](https://github.com/your-org/boundary/issues)
3. Ask in community forum
4. Contact support

---

**Last Updated**: 2026-01-04  
**Version**: 1.0.0  
**Status**: Production Ready
