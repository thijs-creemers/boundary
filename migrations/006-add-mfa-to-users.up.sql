-- Migration: Add MFA (Multi-Factor Authentication) support to users table
-- Description: Adds fields for TOTP-based MFA, backup codes, and MFA status tracking
-- Date: 2024-01-04

-- Add MFA fields to users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_secret TEXT; -- Base32-encoded TOTP secret
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_backup_codes TEXT[]; -- Array of backup codes
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_backup_codes_used TEXT[] DEFAULT ARRAY[]::TEXT[]; -- Array of used backup codes
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_enabled_at TIMESTAMP WITH TIME ZONE; -- When MFA was enabled

-- Create index for MFA-enabled users (for admin queries)
CREATE INDEX IF NOT EXISTS idx_users_mfa_enabled ON users(mfa_enabled) WHERE mfa_enabled = TRUE;

-- Add comment for documentation
COMMENT ON COLUMN users.mfa_enabled IS 'Whether MFA is enabled for this user';
COMMENT ON COLUMN users.mfa_secret IS 'Base32-encoded TOTP secret for authenticator apps (encrypted at rest in production)';
COMMENT ON COLUMN users.mfa_backup_codes IS 'Array of backup codes for MFA recovery';
COMMENT ON COLUMN users.mfa_backup_codes_used IS 'Array of backup codes that have been used (cannot be reused)';
COMMENT ON COLUMN users.mfa_enabled_at IS 'Timestamp when MFA was first enabled for this user';

-- Rollback script (commented out for safety)
-- ALTER TABLE users DROP COLUMN IF EXISTS mfa_enabled;
-- ALTER TABLE users DROP COLUMN IF EXISTS mfa_secret;
-- ALTER TABLE users DROP COLUMN IF EXISTS mfa_backup_codes;
-- ALTER TABLE users DROP COLUMN IF EXISTS mfa_backup_codes_used;
-- ALTER TABLE users DROP COLUMN IF EXISTS mfa_enabled_at;
-- DROP INDEX IF EXISTS idx_users_mfa_enabled;
