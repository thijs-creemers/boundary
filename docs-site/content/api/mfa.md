---
title: "MFA API Reference"
weight: 20
description: "Multi-factor authentication API endpoints for TOTP setup, verification, and backup codes"
---

# MFA API Reference

Complete API reference for Multi-Factor Authentication (TOTP) in Boundary Framework.

## Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/auth/mfa/setup` | POST | Initialize MFA setup |
| `/api/auth/mfa/enable` | POST | Enable MFA with verification |
| `/api/auth/mfa/disable` | POST | Disable MFA |
| `/api/auth/mfa/status` | GET | Check MFA status |
| `/api/auth/login` | POST | Login with MFA code |

---

## Setup MFA

Initialize MFA setup for authenticated user.

**Request**:
```http
POST /api/auth/mfa/setup
Authorization: Bearer <token>
```text

**Response** (200 OK):
```json
{
  "success?": true,
  "secret": "JBSWY3DPEHPK3PXP",
  "qr-code-url": "https://api.qrserver.com/v1/...",
  "backup-codes": [
    "3LTW-XRM1-GYVF",
    "CN2K-1AWR-GDVT",
    "..."
  ],
  "issuer": "Boundary Framework",
  "account-name": "user@example.com"
}
```text

**Fields**:
- `secret`: Base32 TOTP secret (32 chars)
- `qr-code-url`: QR code for authenticator apps
- `backup-codes`: 10 single-use codes (XXX-XXXX-XXXX format)

**Errors**:
- `401 Unauthorized`: Invalid/missing token
- `400 Bad Request`: MFA already enabled

**Example**:
```bash
curl -X POST http://localhost:3000/api/auth/mfa/setup \
  -H "Authorization: Bearer $TOKEN"
```bash

**Notes**:
- Backup codes are shown only once - save them securely
- Use Google Authenticator, Authy, or 1Password to scan QR code
- Setup does not enable MFA - call `/enable` endpoint next

---

## Enable MFA

Enable MFA after setup by verifying TOTP code.

**Request**:
```http
POST /api/auth/mfa/enable
Authorization: Bearer <token>
Content-Type: application/json

{
  "code": "123456"
}
```text

**Body**:
- `code` (required): 6-digit TOTP code from authenticator app

**Response** (200 OK):
```json
{
  "success?": true,
  "message": "MFA enabled successfully"
}
```text

**Errors**:
- `400 Bad Request`: Invalid code, MFA not setup, or already enabled
- `401 Unauthorized`: Invalid token

**Example**:
```bash
curl -X POST http://localhost:3000/api/auth/mfa/enable \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"code": "123456"}'
```bash

**Notes**:
- Must call `/setup` first
- Code expires after 30 seconds
- Allow ±1 time window for clock drift

---

## Disable MFA

Disable MFA for authenticated user.

**Request**:
```http
POST /api/auth/mfa/disable
Authorization: Bearer <token>
```text

**Response** (200 OK):
```json
{
  "success?": true,
  "message": "MFA disabled successfully"
}
```text

**Errors**:
- `401 Unauthorized`: Invalid token
- `400 Bad Request`: MFA not enabled

**Example**:
```bash
curl -X POST http://localhost:3000/api/auth/mfa/disable \
  -H "Authorization: Bearer $TOKEN"
```bash

---

## Get MFA Status

Check if MFA is enabled for authenticated user.

**Request**:
```http
GET /api/auth/mfa/status
Authorization: Bearer <token>
```text

**Response** (MFA Enabled):
```json
{
  "mfa-enabled": true,
  "mfa-enforced": false
}
```text

**Response** (MFA Disabled):
```json
{
  "mfa-enabled": false,
  "mfa-enforced": false
}
```text

**Fields**:
- `mfa-enabled`: User has MFA configured
- `mfa-enforced`: Organization requires MFA (future feature)

**Example**:
```bash
curl http://localhost:3000/api/auth/mfa/status \
  -H "Authorization: Bearer $TOKEN"
```bash

---

## Login with MFA

Two-step login flow for users with MFA enabled.

### Step 1: Authenticate with Password

**Request**:
```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "password123"
}
```text

**Response** (MFA Required):
```json
{
  "success?": true,
  "mfa-required": true,
  "message": "MFA code required"
}
```bash

### Step 2: Submit MFA Code

**Request**:
```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "password123",
  "mfa-code": "123456"
}
```text

**Response** (Success):
```json
{
  "success?": true,
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "name": "John Smith",
    "mfa-enabled": true
  }
}
```text

**Errors**:
- `400 Bad Request`: Invalid credentials or MFA code
- `429 Too Many Requests`: Rate limit exceeded (5 attempts per 15 min)

**Examples**:

```bash
# Step 1: Password authentication
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "password123"}'

# Step 2: MFA verification
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "password123",
    "mfa-code": "123456"
  }'
```text

**Backup Codes**:

Use backup codes when TOTP unavailable:
```bash
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "password123",
    "mfa-code": "3LTW-XRM1-GYVF"
  }'
```bash

**Notes**:
- Backup codes are single-use only
- TOTP codes expire after 30 seconds
- Rate limited: 5 failed attempts per 15 minutes

---

## Rate Limiting

| Endpoint | Limit | Window |
|----------|-------|--------|
| `/api/auth/login` | 5 attempts | 15 minutes |
| `/api/auth/mfa/setup` | 10 requests | 1 hour |
| `/api/auth/mfa/enable` | 10 attempts | 1 hour |

**Rate limit response** (429):
```json
{
  "error": "Too many requests",
  "message": "Rate limit exceeded. Try again in 15 minutes.",
  "retry-after": 900
}
```bash

---

## Security Best Practices

### Production Checklist

- ✅ **Enforce HTTPS**: All MFA endpoints require TLS
- ✅ **Store secrets securely**: Hash TOTP secrets in database
- ✅ **Rate limit**: Prevent brute force attacks
- ✅ **Audit logs**: Log all MFA events (setup, enable, disable, failures)
- ✅ **Backup codes**: Provide 10 codes, single-use only
- ✅ **Time sync**: Use NTP to prevent clock drift issues

### Compliance

- **NIST 800-63B**: TOTP (Time-based OTP) meets Authenticator Assurance Level 2 (AAL2)
- **PCI DSS**: Satisfies multi-factor authentication requirements
- **GDPR**: MFA secrets are personal data - handle accordingly

---

## Testing

### Test Credentials

Development environment includes test users:

```bash
# User without MFA
email: test@example.com
password: password123

# User with MFA enabled
email: test-mfa@example.com
password: password123
TOTP secret: JBSWY3DPEHPK3PXP
```text

Generate TOTP codes:
```bash
# Using oathtool
oathtool --totp --base32 JBSWY3DPEHPK3PXP

# Using Python
python3 -c "import pyotp; print(pyotp.TOTP('JBSWY3DPEHPK3PXP').now())"
```

---

## See Also

- [Authentication Guide](../guides/authentication.md) - Complete authentication setup
- [Security Setup](../guides/security-setup.md) - Security best practices
- [User API](../api/user.md) - User management endpoints

---

**Version**: 1.0  
**Last Updated**: 2026-02-15
