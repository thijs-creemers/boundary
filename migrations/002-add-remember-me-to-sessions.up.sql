-- =============================================================================
-- Migration: Add Remember Me Support to User Sessions
-- =============================================================================
-- This migration adds a remember_me column to the user_sessions table to
-- support "Remember Me" functionality with extended session durations.
--
-- Date: 2025-11-29
-- Author: Boundary Team
-- =============================================================================

-- Add remember_me column to user_sessions table
-- Default to FALSE for existing sessions (standard 24-hour expiry)
ALTER TABLE user_sessions ADD COLUMN remember_me BOOLEAN NOT NULL DEFAULT FALSE;

-- Create index for potential future queries filtering by remember_me
CREATE INDEX idx_user_sessions_remember_me ON user_sessions (remember_me);

-- =============================================================================
-- Migration Complete
-- =============================================================================
-- Summary of changes:
-- 1. Added remember_me BOOLEAN column to user_sessions (default: FALSE)
-- 2. Added index on remember_me column for query optimization
-- 
-- Next steps:
-- 1. Restart application services to pick up the schema change
-- 2. Test "Remember Me" login functionality
-- 3. Verify session expiry times (24 hours vs 30 days)
-- =============================================================================
