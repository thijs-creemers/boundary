-- Migration: 011-split-user-authentication
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
-- WHY THIS SPLIT:
-- - Authentication credentials (password, MFA) are global - user has ONE password
--   across all tenants they belong to
-- - User profile data (name, role, preferences) is tenant-specific - user can have
--   different roles/names in different tenants
-- - Simplifies authentication logic (no tenant context needed for login)
-- - Enables cross-tenant user management (future: user belongs to multiple tenants)

-- =============================================================================
-- Phase 0: Clean up partial previous migration run
-- =============================================================================
DROP TABLE IF EXISTS auth_users;

--;;

-- =============================================================================
-- Phase 1: Create auth_users Table (Public Schema - Shared)
-- =============================================================================
CREATE TABLE auth_users (
    id VARCHAR(36) PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    mfa_enabled BOOLEAN DEFAULT FALSE,
    mfa_secret TEXT,
    mfa_backup_codes TEXT,
    mfa_backup_codes_used TEXT,
    mfa_enabled_at TEXT,
    failed_login_count INTEGER DEFAULT 0,
    lockout_until TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    send_welcome BOOLEAN,
    created_at TEXT NOT NULL,
    updated_at TEXT,
    deleted_at TEXT
);

--;;

-- =============================================================================
-- Phase 2: Migrate Authentication Data from users to auth_users
-- =============================================================================
INSERT INTO auth_users (
    id, email, password_hash,
    mfa_enabled, mfa_secret, mfa_backup_codes, mfa_backup_codes_used, mfa_enabled_at,
    failed_login_count, lockout_until,
    active, send_welcome,
    created_at, updated_at, deleted_at
)
SELECT
    id, email, password_hash,
    COALESCE(mfa_enabled, FALSE),
    mfa_secret, mfa_backup_codes, mfa_backup_codes_used, mfa_enabled_at,
    COALESCE(failed_login_count, 0),
    lockout_until,
    COALESCE(active, TRUE),
    send_welcome,
    created_at, updated_at, deleted_at
FROM users;

--;;

-- =============================================================================
-- Phase 3: Drop search_vector if exists
-- =============================================================================
DROP INDEX IF EXISTS users_search_vector_idx;

--;;

ALTER TABLE users DROP COLUMN IF EXISTS search_vector;

--;;

-- =============================================================================
-- Phase 4: Drop Authentication Columns from users Table
-- =============================================================================
ALTER TABLE users DROP COLUMN IF EXISTS email;

--;;

ALTER TABLE users DROP COLUMN IF EXISTS password_hash;

--;;

ALTER TABLE users DROP COLUMN IF EXISTS mfa_enabled;

--;;

ALTER TABLE users DROP COLUMN IF EXISTS mfa_secret;

--;;

ALTER TABLE users DROP COLUMN IF EXISTS mfa_backup_codes;

--;;

ALTER TABLE users DROP COLUMN IF EXISTS mfa_backup_codes_used;

--;;

ALTER TABLE users DROP COLUMN IF EXISTS mfa_enabled_at;

--;;

ALTER TABLE users DROP COLUMN IF EXISTS failed_login_count;

--;;

ALTER TABLE users DROP COLUMN IF EXISTS lockout_until;

--;;

ALTER TABLE users DROP COLUMN IF EXISTS active;

--;;

ALTER TABLE users DROP COLUMN IF EXISTS send_welcome;

--;;

-- Drop indexes that referenced removed columns
DROP INDEX IF EXISTS idx_users_mfa_enabled;

--;;

DROP INDEX IF EXISTS idx_users_mfa_enabled_at;

--;;

DROP INDEX IF EXISTS idx_users_lockout_until;

--;;

DROP INDEX IF EXISTS idx_users_active;

--;;

DROP INDEX IF EXISTS idx_users_email;

--;;

-- =============================================================================
-- Phase 5: Add tenant_id Column to users Table
-- =============================================================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS tenant_id TEXT;

--;;

-- =============================================================================
-- Phase 6: Create search_vector and Indexes
-- =============================================================================
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS search_vector tsvector
  GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(name, '')), 'A')
  ) STORED;

--;;

CREATE INDEX IF NOT EXISTS users_search_vector_idx
  ON users USING GIN(search_vector);

--;;

-- auth_users indexes
CREATE INDEX IF NOT EXISTS idx_auth_users_email ON auth_users(email);

--;;

CREATE INDEX IF NOT EXISTS idx_auth_users_active ON auth_users(active);

--;;

CREATE INDEX IF NOT EXISTS idx_auth_users_deleted_at ON auth_users(deleted_at);

--;;

CREATE INDEX IF NOT EXISTS idx_auth_users_lockout_until ON auth_users(lockout_until);

--;;

CREATE INDEX IF NOT EXISTS idx_auth_users_mfa_enabled ON auth_users(mfa_enabled) WHERE mfa_enabled = TRUE;

--;;

-- users indexes (tenant-scoped profile queries)
CREATE INDEX IF NOT EXISTS idx_users_tenant_id ON users(tenant_id);

--;;

CREATE INDEX IF NOT EXISTS idx_users_tenant_role ON users(tenant_id, role);

--;;

CREATE INDEX IF NOT EXISTS idx_users_tenant_active ON users(tenant_id, deleted_at);
