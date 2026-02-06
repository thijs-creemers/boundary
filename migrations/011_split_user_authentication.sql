-- Migration: 011_split_user_authentication
-- Description: Split user table into auth_users (public schema) and tenant-specific users
-- Author: Boundary Framework
-- Date: 2026-02-06
--
-- PHASE 8: Multi-Tenancy User Authentication Split
--
-- This migration implements the authentication split required for schema-per-tenant
-- multi-tenancy as specified in ADR-004. It separates authentication credentials
-- (which must be global) from tenant-specific user data (which is isolated).
--
-- ARCHITECTURE:
-- ┌─────────────────────────────────────────────────────────────────┐
-- │                      PUBLIC SCHEMA                               │
-- │  ┌─────────────────────────────────────────────────────────┐    │
-- │  │  auth_users (authentication data - shared)              │    │
-- │  │  - id, email, password_hash                             │    │
-- │  │  - mfa_enabled, mfa_secret, mfa_backup_codes            │    │
-- │  │  - failed_login_count, lockout_until                    │    │
-- │  │  - active, created_at, updated_at, deleted_at           │    │
-- │  └─────────────────────────────────────────────────────────┘    │
-- │  ┌─────────────────────────────────────────────────────────┐    │
-- │  │  tenants (tenant registry)                              │    │
-- │  │  - id, slug, name, schema_name, status, settings        │    │
-- │  └─────────────────────────────────────────────────────────┘    │
-- └─────────────────────────────────────────────────────────────────┘
--
-- ┌─────────────────────────────────────────────────────────────────┐
-- │               TENANT SCHEMA (tenant_<slug>)                      │
-- │  ┌─────────────────────────────────────────────────────────┐    │
-- │  │  users (tenant-specific user data)                      │    │
-- │  │  - id (same as auth_users.id)                           │    │
-- │  │  - tenant_id (FK to public.tenants.id)                  │    │
-- │  │  - name, role, avatar_url                               │    │
-- │  │  - login_count, last_login                              │    │
-- │  │  - date_format, time_format                             │    │
-- │  │  - created_at, updated_at, deleted_at                   │    │
-- │  └─────────────────────────────────────────────────────────┘    │
-- └─────────────────────────────────────────────────────────────────┘
--
-- AUTHENTICATION FLOW (Post-Split):
-- 1. User logs in with email + password
-- 2. Query public.auth_users for authentication (email, password_hash, mfa)
-- 3. Extract tenant_id from JWT claim or subdomain
-- 4. Set search_path to tenant_<slug>, public
-- 5. Query tenant_<slug>.users for user profile data (name, role, preferences)
-- 6. Merge auth + profile data into unified user entity
--
-- WHY THIS SPLIT:
-- - Authentication credentials (password, MFA) are global - user has ONE password
--   across all tenants they belong to
-- - User profile data (name, role, preferences) is tenant-specific - user can have
--   different roles/names in different tenants
-- - Simplifies authentication logic (no tenant context needed for login)
-- - Enables cross-tenant user management (future: user belongs to multiple tenants)
--
-- MIGRATION STRATEGY:
-- Phase 1: Create auth_users table in public schema
-- Phase 2: Migrate authentication data from users to auth_users
-- Phase 3: Drop authentication columns from users table (keep profile data)
-- Phase 4: Add tenant_id column to users table
-- Phase 5: Create indexes for performance
--
-- BACKWARDS COMPATIBILITY:
-- This migration maintains SQLite compatibility for development while preparing
-- for PostgreSQL schema-per-tenant pattern in production.

-- =============================================================================
-- Phase 1: Create auth_users Table (Public Schema - Shared)
-- =============================================================================

CREATE TABLE IF NOT EXISTS auth_users (
    -- Primary Key
    id TEXT PRIMARY KEY,  -- UUID as TEXT for SQLite, will be UUID type in PostgreSQL

    -- Authentication Credentials (GLOBAL - shared across all tenants)
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,  -- bcrypt hash, always 60 characters

    -- Multi-Factor Authentication (MFA)
    mfa_enabled INTEGER DEFAULT 0,  -- SQLite: 0 = false, 1 = true (will be BOOLEAN in PostgreSQL)
    mfa_secret TEXT,  -- TOTP secret (base32 encoded)
    mfa_backup_codes TEXT,  -- JSON array of backup codes (will be JSONB in PostgreSQL)
    mfa_backup_codes_used TEXT,  -- JSON array of used backup codes (will be JSONB in PostgreSQL)
    mfa_enabled_at TEXT,  -- ISO 8601 timestamp (will be TIMESTAMPTZ in PostgreSQL)

    -- Account Security
    failed_login_count INTEGER DEFAULT 0,  -- Consecutive failed login attempts
    lockout_until TEXT,  -- ISO 8601 timestamp for account lockout expiration (will be TIMESTAMPTZ in PostgreSQL)

    -- Account Status (GLOBAL)
    active INTEGER DEFAULT 1,  -- SQLite: 0 = inactive, 1 = active (will be BOOLEAN in PostgreSQL)

    -- Audit Fields
    created_at TEXT NOT NULL,  -- ISO 8601 timestamp (will be TIMESTAMPTZ in PostgreSQL)
    updated_at TEXT,  -- ISO 8601 timestamp (will be TIMESTAMPTZ in PostgreSQL)
    deleted_at TEXT  -- ISO 8601 timestamp for soft deletion (will be TIMESTAMPTZ in PostgreSQL)
);

-- =============================================================================
-- Phase 2: Migrate Authentication Data from users to auth_users
-- =============================================================================

-- Copy authentication-related data from users table to auth_users
-- This preserves existing user credentials while splitting the tables
INSERT INTO auth_users (
    id,
    email,
    password_hash,
    mfa_enabled,
    mfa_secret,
    mfa_backup_codes,
    mfa_backup_codes_used,
    mfa_enabled_at,
    failed_login_count,
    lockout_until,
    active,
    created_at,
    updated_at,
    deleted_at
)
SELECT
    id,
    email,
    password_hash,
    COALESCE(mfa_enabled, 0),  -- Default to 0 (false) if NULL
    mfa_secret,
    mfa_backup_codes,
    mfa_backup_codes_used,
    mfa_enabled_at,
    COALESCE(failed_login_count, 0),  -- Default to 0 if NULL
    lockout_until,
    COALESCE(active, 1),  -- Default to 1 (true) if NULL
    created_at,
    updated_at,
    deleted_at
FROM users;

-- =============================================================================
-- Phase 3: Drop Authentication Columns from users Table
-- =============================================================================

-- SQLite doesn't support DROP COLUMN, so we need to recreate the table
-- This removes authentication data, keeping only tenant-specific profile data

-- Step 1: Create temporary table with only profile columns
CREATE TABLE users_new (
    -- Primary Key (matches auth_users.id)
    id TEXT PRIMARY KEY,

    -- Tenant Association (will reference public.tenants.id)
    tenant_id TEXT,  -- Will be populated later when tenants are created

    -- User Profile (TENANT-SPECIFIC - can differ across tenants)
    name TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user',  -- admin, user, viewer
    avatar_url TEXT,

    -- User Activity Tracking
    login_count INTEGER DEFAULT 0,
    last_login TEXT,  -- ISO 8601 timestamp (will be TIMESTAMPTZ in PostgreSQL)

    -- User Preferences (TENANT-SPECIFIC)
    date_format TEXT DEFAULT 'iso',  -- iso, us, eu
    time_format TEXT DEFAULT '24h',  -- 12h, 24h

    -- Audit Fields
    created_at TEXT NOT NULL,
    updated_at TEXT,
    deleted_at TEXT
);

-- Step 2: Copy profile data from old users table to new users table
INSERT INTO users_new (
    id,
    tenant_id,
    name,
    role,
    avatar_url,
    login_count,
    last_login,
    date_format,
    time_format,
    created_at,
    updated_at,
    deleted_at
)
SELECT
    id,
    NULL,  -- tenant_id will be populated when tenant schemas are created
    name,
    role,
    avatar_url,
    COALESCE(login_count, 0),
    last_login,
    COALESCE(date_format, 'iso'),
    COALESCE(time_format, '24h'),
    created_at,
    updated_at,
    deleted_at
FROM users;

-- Step 3: Drop old users table
DROP TABLE users;

-- Step 4: Rename new table to users
ALTER TABLE users_new RENAME TO users;

-- =============================================================================
-- Phase 4: Create Indexes for Performance
-- =============================================================================

-- auth_users indexes (authentication queries)
CREATE INDEX idx_auth_users_email ON auth_users(email);  -- Login by email
CREATE INDEX idx_auth_users_active ON auth_users(active);  -- Active users only
CREATE INDEX idx_auth_users_deleted_at ON auth_users(deleted_at);  -- Soft deletion filter
CREATE INDEX idx_auth_users_lockout_until ON auth_users(lockout_until);  -- Account lockout checks
CREATE INDEX idx_auth_users_mfa_enabled ON auth_users(mfa_enabled);  -- MFA-enabled users

-- users indexes (profile queries within tenant schema)
CREATE INDEX idx_users_tenant_id ON users(tenant_id);  -- Tenant association (critical for multi-tenancy)
CREATE INDEX idx_users_role ON users(role);  -- Role-based queries
CREATE INDEX idx_users_deleted_at ON users(deleted_at);  -- Soft deletion filter
CREATE INDEX idx_users_tenant_role ON users(tenant_id, role);  -- Composite: tenant + role queries
CREATE INDEX idx_users_tenant_active ON users(tenant_id, deleted_at);  -- Composite: tenant + active users

-- =============================================================================
-- Verification Queries (Run manually to verify migration)
-- =============================================================================

-- Count records in each table (should match original users count)
-- SELECT COUNT(*) AS auth_users_count FROM auth_users;
-- SELECT COUNT(*) AS users_count FROM users;

-- Verify authentication data migrated correctly
-- SELECT id, email, mfa_enabled, active, created_at FROM auth_users LIMIT 5;

-- Verify profile data preserved correctly
-- SELECT id, name, role, tenant_id, created_at FROM users LIMIT 5;

-- Check for orphaned records (should return 0)
-- SELECT COUNT(*) FROM users WHERE id NOT IN (SELECT id FROM auth_users);
-- SELECT COUNT(*) FROM auth_users WHERE id NOT IN (SELECT id FROM users);

-- =============================================================================
-- PostgreSQL Notes (For Production Migration)
-- =============================================================================

-- When migrating to PostgreSQL with schema-per-tenant pattern:
--
-- 1. CREATE SCHEMA tenant_<slug> for each tenant in public.tenants
-- 2. Move users table INTO tenant schema: CREATE TABLE tenant_<slug>.users AS SELECT ...
-- 3. Add foreign key: ALTER TABLE tenant_<slug>.users ADD CONSTRAINT fk_users_auth
--    FOREIGN KEY (id) REFERENCES public.auth_users(id) ON DELETE CASCADE
-- 4. Change data types:
--    - TEXT -> UUID for id columns
--    - TEXT -> TIMESTAMPTZ for timestamp columns
--    - INTEGER -> BOOLEAN for boolean columns
--    - TEXT -> JSONB for JSON columns (mfa_backup_codes, etc.)
-- 5. Set default search_path in middleware: SET search_path TO tenant_<slug>, public
--
-- Authentication Query Example (PostgreSQL):
-- SET search_path TO tenant_acme_corp, public;
-- SELECT
--   a.id, a.email, a.password_hash, a.mfa_enabled, a.mfa_secret,
--   u.name, u.role, u.avatar_url, u.date_format, u.time_format
-- FROM auth_users a
-- JOIN users u ON u.id = a.id
-- WHERE a.email = $1 AND a.active = true AND a.deleted_at IS NULL;

-- =============================================================================
-- Rollback Instructions
-- =============================================================================

-- To rollback this migration (not recommended after data modifications):
-- 1. Recreate original users table with authentication columns
-- 2. Merge auth_users and users data back into single users table
-- 3. Drop auth_users table
-- 4. Recreate original indexes
--
-- WARNING: Rollback requires careful data merging. Test in non-production first.
