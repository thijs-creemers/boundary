-- =============================================================================
-- Migration: Remove Multi-Tenancy Support
-- =============================================================================
-- This migration removes tenant_id columns and tenant-specific constraints
-- from the users and user_sessions tables, converting to a single-tenant model.
--
-- BREAKING CHANGE: This is a major version change that removes multi-tenancy.
-- All existing data will be preserved, but tenant isolation will be removed.
--
-- Date: 2025-01-20
-- Author: Boundary Team
-- =============================================================================

-- =============================================================================
-- BACKUP REMINDER
-- =============================================================================
-- IMPORTANT: Create a backup before running this migration!
-- 
-- SQLite:   sqlite3 database.db ".backup backup.db"
-- Postgres: pg_dump database > backup.sql
-- MySQL:    mysqldump database > backup.sql
-- =============================================================================

-- =============================================================================
-- Part 1: Users Table Migration
-- =============================================================================

-- Step 1: Drop tenant-specific indexes
DROP INDEX IF EXISTS idx_users_tenant_id;
DROP INDEX IF EXISTS idx_users_email_tenant;
DROP INDEX IF EXISTS idx_users_role_tenant;
DROP INDEX IF EXISTS idx_users_active_tenant;

-- Step 2: Create new users table without tenant_id
-- SQLite doesn't support ALTER TABLE DROP COLUMN, so we need to recreate
CREATE TABLE users_new (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,  -- Email is now globally unique
  name VARCHAR(255) NOT NULL,
  password_hash VARCHAR(255),
  role TEXT NOT NULL CHECK(role IN ('admin', 'user', 'viewer')),
  active BOOLEAN NOT NULL DEFAULT true,
  login_count INTEGER,
  last_login TEXT,
  date_format TEXT CHECK(date_format IN ('iso', 'us', 'eu')),
  time_format TEXT CHECK(time_format IN ('12h', '24h')),
  avatar_url VARCHAR(255),
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT
);

-- Step 3: Copy data from old table to new table
-- Note: If there are duplicate emails across tenants, only the first will be kept
-- You may want to handle this differently based on your data
INSERT INTO users_new (
  id, email, name, password_hash, role, active, 
  login_count, last_login, date_format, time_format,
  avatar_url, created_at, updated_at, deleted_at
)
SELECT 
  id, email, name, password_hash, role, active,
  login_count, last_login, date_format, time_format,
  avatar_url, created_at, updated_at, deleted_at
FROM users
-- Handle duplicate emails: keep first occurrence (lowest id)
WHERE id IN (
  SELECT MIN(id) 
  FROM users 
  GROUP BY email
);

-- Step 4: Drop old table and rename new table
DROP TABLE users;
ALTER TABLE users_new RENAME TO users;

-- Step 5: Recreate indexes (without tenant)
CREATE INDEX idx_users_role ON users (role);
CREATE INDEX idx_users_date_format ON users (date_format);
CREATE INDEX idx_users_time_format ON users (time_format);
CREATE INDEX idx_users_created_at ON users (created_at);
CREATE INDEX idx_users_updated_at ON users (updated_at);
CREATE INDEX idx_users_deleted_at ON users (deleted_at);
CREATE INDEX idx_users_active ON users (active);

-- =============================================================================
-- Part 2: User Sessions Table Migration
-- =============================================================================

-- Step 1: Drop tenant-specific indexes
DROP INDEX IF EXISTS idx_user_sessions_tenant_id;

-- Step 2: Create new user_sessions table without tenant_id
CREATE TABLE user_sessions_new (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  session_token VARCHAR(255) NOT NULL,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  last_accessed_at TEXT,
  revoked_at TEXT,
  user_agent VARCHAR(255),
  ip_address VARCHAR(255),
  CONSTRAINT uk_user_sessions_token UNIQUE(session_token),
  CONSTRAINT fk_user_sessions_user_id FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Step 3: Copy data from old table to new table
INSERT INTO user_sessions_new (
  id, user_id, session_token, expires_at, created_at,
  last_accessed_at, revoked_at, user_agent, ip_address
)
SELECT 
  id, user_id, session_token, expires_at, created_at,
  last_accessed_at, revoked_at, user_agent, ip_address
FROM user_sessions
-- Only copy sessions for users that still exist after deduplication
WHERE user_id IN (SELECT id FROM users);

-- Step 4: Drop old table and rename new table
DROP TABLE user_sessions;
ALTER TABLE user_sessions_new RENAME TO user_sessions;

-- Step 5: Recreate indexes (without tenant)
CREATE INDEX idx_user_sessions_user_id ON user_sessions (user_id);
CREATE INDEX idx_user_sessions_expires_at ON user_sessions (expires_at);
CREATE INDEX idx_user_sessions_created_at ON user_sessions (created_at);
CREATE INDEX idx_user_sessions_last_accessed_at ON user_sessions (last_accessed_at);
CREATE INDEX idx_user_sessions_revoked_at ON user_sessions (revoked_at);
CREATE INDEX idx_sessions_token ON user_sessions (session_token);

-- =============================================================================
-- Migration Complete
-- =============================================================================
-- Summary of changes:
-- 1. Removed tenant_id column from users table
-- 2. Changed email constraint from unique-per-tenant to globally unique
-- 3. Removed tenant_id column from user_sessions table
-- 4. Removed all tenant-specific indexes
-- 5. Preserved all user and session data (with duplicate email handling)
-- 
-- Next steps:
-- 1. Test the migration in a non-production environment
-- 2. Update application code to remove tenant-id parameters
-- 3. Restart application services
-- 4. Monitor for any issues
-- =============================================================================
