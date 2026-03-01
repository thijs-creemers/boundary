CREATE INDEX IF NOT EXISTS idx_users_tenant_id ON users (tenant_id);
--;;
CREATE INDEX IF NOT EXISTS idx_users_deleted_at ON users (deleted_at) WHERE deleted_at IS NOT NULL;
--;;
CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON user_sessions (user_id);
