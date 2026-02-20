---
title: "Authentication Guide"
weight: 30
description: "Complete guide to authentication, authorization, and MFA in Boundary Framework"
---

# Authentication Guide

This guide provides a comprehensive overview of the authentication system in the Boundary Framework. Boundary provides a robust, production-ready authentication system out of the box, including JWT-based authentication, session management, and Multi-Factor Authentication (MFA).

## Table of Contents

- [Introduction](#introduction)
- [Security Model](#security-model)
- [JWT Authentication](#jwt-authentication)
  - [User Registration](#user-registration)
  - [User Login](#user-login)
  - [Token Lifecycle](#token-lifecycle)
- [Session Management](#session-management)
  - [Session Lifecycle](#session-lifecycle)
  - [Logout](#logout)
- [Multi-Factor Authentication (MFA)](#multi-factor-authentication-mfa)
  - [MFA Setup Flow](#mfa-setup-flow)
  - [Enforcing MFA](#enforcing-mfa)
  - [Backup Codes](#backup-codes)
- [Role-Based Access Control (RBAC)](#role-based-access-control-rbac)
- [Security Best Practices](#security-best-practices)
- [API Examples (Complete Workflows)](#api-examples)
- [Troubleshooting](#troubleshooting)

---

## Introduction

Boundary's authentication system is built on the **Functional Core / Imperative Shell** (FC/IS) architecture, ensuring that security logic is pure, testable, and separated from side effects like database access and token signing.

### Key Components

- **`boundary/user`**: The primary library for authentication, user management, and MFA.
- **JWT**: Stateless tokens for API authentication.
- **Sessions**: Stateful session tracking in the database for enhanced security (revocation, activity tracking).
- **MFA (TOTP)**: Time-based One-Time Passwords compatible with Google Authenticator, Authy, etc.

---

## Security Model

Boundary follows a multi-layered security approach:

1.  **Transport Security**: All authentication traffic must be encrypted via HTTPS.
2.  **Password Security**: Passwords are never stored in plain text. We use `bcrypt` with SHA-512 for salted hashing.
3.  **Token Security**: JWT tokens are signed using a secret key (`JWT_SECRET`) and have configurable expiration times.
4.  **Stateful Revocation**: While JWTs are stateless, Boundary pairs them with database-backed sessions to allow immediate revocation.
5.  **Account Protection**: Automatic account lockout after consecutive failed login attempts to prevent brute-force attacks.

---

## JWT Authentication

JWT (JSON Web Token) is the primary method for authenticating API requests.

### User Registration

Users can be registered via the API or the web UI. During registration, the password is validated against the configured password policy and hashed before storage.

**API Example: Registration**

```bash
curl -X POST http://localhost:3000/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "SecureP@ss123!",
    "name": "Jane Doe",
    "role": "user"
  }'
```text

**Response (201 Created):**
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "email": "user@example.com",
  "name": "Jane Doe",
  "role": "user",
  "active": true,
  "createdAt": "2026-01-26T10:00:00Z"
}
```bash

### User Login

The login process authenticates credentials and returns both a JWT token (for API access) and a Session ID.

**API Example: Login**

```bash
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "SecureP@ss123!"
  }'
```text

**Response (200 OK):**
```json
{
  "success": true,
  "jwt-token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "session-id": "550e8400-e29b-41d4-a716-446655440000",
  "user": {
    "id": "123e4567-e89b-12d3-a456-426614174000",
    "email": "user@example.com",
    "name": "Jane Doe",
    "role": "user",
    "mfa-enabled": false
  }
}
```bash

### Token Lifecycle

-   **Signature**: Tokens are signed using `HS256` with the `JWT_SECRET` environment variable.
-   **Claims**: Standard claims include `sub` (user ID), `email`, `role`, `iat` (issued at), and `exp` (expiration).
-   **Expiration**: Default expiration is 24 hours, configurable via `:jwt-expiration-hours`.

---

## Session Management

Boundary implements stateful session management to provide features that stateless JWTs cannot easily support.

### Session Lifecycle

1.  **Creation**: A session is created in the database upon successful login.
2.  **Tracking**: Each session tracks the user's IP address, user agent, and last access time.
3.  **Validation**: Every request using a session token is validated against the database.
4.  **Revocation**: Sessions can be revoked individually or all at once (e.g., when a user changes their password).

### Logout

Logging out invalidates the session in the database, rendering the corresponding JWT/Session Token useless for future requests.

**API Example: Logout**

```bash
curl -X DELETE http://localhost:3000/api/sessions/550e8400-e29b-41d4-a716-446655440000 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```bash

---

## Multi-Factor Authentication (MFA)

Boundary supports TOTP-based Multi-Factor Authentication for enhanced account security.

### MFA Setup Flow

1.  **Initiate Setup**: The user requests MFA setup. The server generates a unique secret and a set of backup codes.
2.  **Scan QR Code**: The user scans the provided QR code URL into their authenticator app.
3.  **Verify & Enable**: The user provides a 6-digit code from their app to prove successful setup. MFA is only enabled after this verification.

**API Example: Initiate Setup**

```bash
curl -X POST http://localhost:3000/api/auth/mfa/setup \
  -H "Authorization: Bearer <jwt-token>"
```text

**Response:**
```json
{
  "secret": "JBSWY3DPEHPK3PXP",
  "qrCodeUrl": "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=...",
  "backupCodes": ["3LTW-XRM1-GYVF", "CN2K-1AWR-GDVT", ...],
  "issuer": "Boundary Framework",
  "accountName": "user@example.com"
}
```bash

### Enforcing MFA

Once MFA is enabled, the login flow becomes a two-step process:

1.  **Step 1**: Login with email and password. Server returns `{"requires-mfa?": true}`.
2.  **Step 2**: Submit email, password, and `mfa-code`.

**API Example: Login Step 2**

```bash
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "SecureP@ss123!",
    "mfa-code": "123456"
  }'
```bash

### Backup Codes

Boundary generates 10 single-use backup codes during setup. These codes allow users to regain access if they lose their authenticator device.

-   **Format**: `XXXX-XXXX-XXXX` (e.g., `3LTW-XRM1-GYVF`)
-   **Usage**: Submit in the `mfa-code` field during login.
-   **Security**: Each code is invalidated immediately after one successful use.

For more details, see the [MFA Setup Guide](./mfa-setup.md) and [MFA API Reference](../api/mfa.md).

---

## Role-Based Access Control (RBAC)

Boundary uses a simple but effective role-based access control system.

### Standard Roles

-   **`:admin`**: Full access to all users and system settings.
-   **`:user`**: Standard access to their own profile and resources.
-   **`:viewer`**: Read-only access to assigned resources.

### Enforcing Roles

Roles are enforced via HTTP interceptors:

```clojure
;; Example route definition with role enforcement
{:path "/api/users"
 :methods {:post {:handler (create-user-handler user-service)
                  :interceptors ['boundary.user.shell.http-interceptors/require-admin]}}}
```bash

---

## Security Best Practices

To maintain a secure Boundary application, follow these guidelines:

1.  **Configure JWT_SECRET**: Never use the default secret. Generate a strong, random 32+ character string and set it via environment variables.
2.  **HTTPS Everywhere**: Ensure your application is only accessible over HTTPS. Use HSTS headers to enforce this.
3.  **Secure Cookies**: If using cookies for session storage, always set `HttpOnly`, `Secure`, and `SameSite=Strict` flags.
4.  **Short-Lived Tokens**: Keep JWT expiration times as short as practical for your use case.
5.  **Rate Limiting**: Apply rate limiting to all authentication endpoints (`/api/auth/login`, `/api/auth/mfa/*`).
6.  **Audit Logging**: Monitor the audit logs for suspicious activity, such as multiple failed login attempts for different users from the same IP.
7.  **Rotate Secrets**: Periodically rotate your `JWT_SECRET`. Note that this will invalidate all existing sessions.

---

## API Examples

### Complete Authentication Workflow

#### 1. Registration
```bash
curl -X POST http://localhost:3000/api/users \
  -H "Content-Type: application/json" \
  -d '{"email": "dev@example.com", "password": "Password123!", "name": "Developer", "role": "user"}'
```bash

#### 2. Initial Login
```bash
curl -i -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "dev@example.com", "password": "Password123!"}'
```bash
*Take note of the `jwt-token` in the response.*

#### 3. Setup MFA
```bash
curl -X POST http://localhost:3000/api/auth/mfa/setup \
  -H "Authorization: Bearer <your-jwt-token>"
```bash

#### 4. Enable MFA
```bash
curl -X POST http://localhost:3000/api/auth/mfa/enable \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "secret": "THE_SECRET_FROM_SETUP",
    "backupCodes": ["CODE1", "CODE2", ...],
    "verificationCode": "123456"
  }'
```bash

#### 5. Authenticated Login (with MFA)
```bash
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "dev@example.com", "password": "Password123!", "mfa-code": "654321"}'
```

---

## Troubleshooting

### Common Errors

| Error | Cause | Solution |
|-------|-------|----------|
| `Invalid credentials` | Wrong email or password. | Check spelling; use password reset if available. |
| `MFA code required` | MFA is enabled for this user. | Provide `mfa-code` in the login request. |
| `Invalid MFA code` | Code expired or wrong device. | Ensure device clock is synced; check if using the correct account. |
| `Account locked` | Too many failed attempts. | Wait for the lockout period to expire (default 15m). |
| `Unauthorized` | Missing or invalid JWT token. | Provide a valid `Authorization: Bearer <token>` header. |

### Debugging JWT Issues

If your JWT tokens are being rejected:
1.  **Check Expiration**: Ensure the `exp` claim hasn't passed.
2.  **Verify Secret**: Ensure the server is using the same `JWT_SECRET` that was used to sign the token.
3.  **Check Algorithm**: Boundary uses `HS256`. Ensure your client isn't trying to use a different algorithm.
4.  **Header Format**: Ensure the header is exactly `Authorization: Bearer <token>`.

### MFA Clock Skew

TOTP is highly sensitive to time. If your server or user's device clock is off by more than 30 seconds, codes will be rejected.
-   **Server**: Ensure NTP is running on your server.
-   **Client**: Ask the user to "Sync time" in their authenticator app settings.

---

**Last Updated**: 2026-01-26  
**Version**: 1.0.0  
**Status**: Stable

---

## See also

- [MFA API Reference](../api/mfa.md) - Complete MFA endpoint documentation
- [Security Setup](security-setup.md) - Production security configuration
- [Testing Guide](testing.md) - Testing authentication and MFA flows
- [Database Setup](database-setup.md) - Configure users table and sessions

