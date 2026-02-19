-- =============================================================================
-- Migration: Add Account Lockout Support to Users
-- =============================================================================
-- This migration adds fields for tracking failed login attempts and account
-- lockout status to enable brute-force protection.
--
-- Fields:
-- - failed_login_count: Number of consecutive failed login attempts (INTEGER)
-- - lockout_until: ISO 8601 timestamp when lockout expires (TEXT, nullable)
--
-- Date: 2025-01-17
-- Author: Boundary Team
-- =============================================================================

-- Add account lockout fields to users table
-- SQLite Note: IF NOT EXISTS is supported in ALTER TABLE ADD COLUMN
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_login_count INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS lockout_until TEXT; -- ISO 8601 timestamp string

-- Create index for locked out users (for admin queries and cleanup jobs)
-- SQLite Note: Partial indexes (WHERE clause) are supported since SQLite 3.8.0
CREATE INDEX IF NOT EXISTS idx_users_lockout_until ON users(lockout_until) WHERE lockout_until IS NOT NULL;

-- =============================================================================
-- Migration Complete
-- =============================================================================
-- Summary of changes:
-- 1. Added failed_login_count INTEGER column (default: 0, not null)
-- 2. Added lockout_until TEXT column (nullable, stores ISO 8601 timestamp)
-- 3. Added partial index on lockout_until for efficient queries
-- 
-- Next steps:
-- 1. Restart application services to pick up the schema change
-- 2. Test authentication with failed login attempts
-- 3. Verify account lockout functionality after threshold is reached
-- =============================================================================

-- Rollback script (commented out for safety)
-- ALTER TABLE users DROP COLUMN failed_login_count;
-- ALTER TABLE users DROP COLUMN lockout_until;
-- DROP INDEX IF EXISTS idx_users_lockout_until;
