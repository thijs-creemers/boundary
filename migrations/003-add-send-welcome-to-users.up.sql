-- =============================================================================
-- Migration: Add Send Welcome Email Support to Users
-- =============================================================================
-- This migration adds a send_welcome column to the users table to
-- support optional welcome email sending during user creation.
--
-- Date: 2025-11-30
-- Author: Boundary Team
-- =============================================================================

-- Add send_welcome column to users table
-- Default to TRUE for backward compatibility (existing behavior)
ALTER TABLE users ADD COLUMN send_welcome BOOLEAN NOT NULL DEFAULT TRUE;

-- =============================================================================
-- Migration Complete
-- =============================================================================
-- Summary of changes:
-- 1. Added send_welcome BOOLEAN column to users (default: TRUE)
-- 
-- Next steps:
-- 1. Restart application services to pick up the schema change
-- 2. Test user creation with send_welcome flag
-- 3. Verify welcome email is sent/not sent based on flag
-- =============================================================================
