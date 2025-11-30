-- =============================================================================
-- Migration: Create User Audit Log Table
-- =============================================================================
-- This migration creates a user_audit_log table to track all user-related
-- actions for compliance, security monitoring, and admin tooling.
--
-- Date: 2025-11-30
-- Author: Boundary Team
-- =============================================================================

-- Create user_audit_log table
CREATE TABLE IF NOT EXISTS user_audit_log (
    id VARCHAR(36) PRIMARY KEY,
    action TEXT NOT NULL CHECK (action IN (
        'create', 'update', 'delete', 'activate', 'deactivate', 
        'role-change', 'bulk-action', 'login', 'logout'
    )),
    actor_id VARCHAR(36), -- NULL for system actions
    actor_email TEXT,
    target_user_id VARCHAR(36) NOT NULL,
    target_user_email TEXT NOT NULL,
    changes TEXT, -- Store changes as JSON string: {"field": "name", "old": "John", "new": "Jane"}
    metadata TEXT, -- Additional context as JSON string: {"bulk_count": 5, "reason": "security"}
    ip_address TEXT,
    user_agent TEXT,
    result TEXT NOT NULL CHECK (result IN ('success', 'failure')),
    error_message TEXT,
    created_at TEXT NOT NULL
);

-- Create indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_user_audit_log_target_user_id ON user_audit_log(target_user_id);
CREATE INDEX IF NOT EXISTS idx_user_audit_log_actor_id ON user_audit_log(actor_id);
CREATE INDEX IF NOT EXISTS idx_user_audit_log_action ON user_audit_log(action);
CREATE INDEX IF NOT EXISTS idx_user_audit_log_created_at ON user_audit_log(created_at);
CREATE INDEX IF NOT EXISTS idx_user_audit_log_result ON user_audit_log(result);

-- Create composite index for user activity timeline
CREATE INDEX IF NOT EXISTS idx_user_audit_log_user_timeline 
    ON user_audit_log(target_user_id, created_at DESC);

-- Create composite index for actor activity timeline
CREATE INDEX IF NOT EXISTS idx_user_audit_log_actor_timeline 
    ON user_audit_log(actor_id, created_at DESC);

-- =============================================================================
-- Migration Complete
-- =============================================================================
-- Summary of changes:
-- 1. Created user_audit_log table with comprehensive audit tracking
-- 2. Added check constraints for action and result enums
-- 3. Created indexes for efficient querying:
--    - Target user lookups
--    - Actor user lookups
--    - Action type filtering
--    - Date range queries
--    - Timeline views (user and actor)
-- 
-- Next steps:
-- 1. Update schema initialization to include user_audit_log table
-- 2. Create audit log repository implementation
-- 3. Integrate audit logging with user operations
-- 4. Create admin UI for viewing audit trail
-- =============================================================================
