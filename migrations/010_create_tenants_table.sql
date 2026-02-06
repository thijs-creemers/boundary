-- =============================================================================
-- Migration: Create Tenants Table for Multi-Tenancy Support
-- =============================================================================
-- This migration creates the tenants table in the public schema for
-- managing tenant metadata and schema names. This is Phase 7 of the
-- multi-tenancy implementation (ADR-004).
--
-- Each tenant gets:
-- - A unique slug (URL-friendly identifier)
-- - A dedicated PostgreSQL schema for data isolation
-- - Configuration settings (JSON)
--
-- Date: 2026-02-06
-- Author: Boundary Team
-- ADR: docs/adr/ADR-004-multi-tenancy-architecture.md
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
-- Part 1: Create Tenants Table (Public Schema)
-- =============================================================================

CREATE TABLE IF NOT EXISTS tenants (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  slug VARCHAR(100) NOT NULL UNIQUE,
  name VARCHAR(255) NOT NULL,
  schema_name VARCHAR(63) NOT NULL UNIQUE,  -- PostgreSQL schema name limit
  status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK(status IN ('active', 'suspended', 'deleted')),
  settings TEXT,  -- JSON configuration (stored as TEXT for SQLite compatibility)
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT
);

-- =============================================================================
-- Part 2: Create Indexes for Performance
-- =============================================================================

-- Index on slug for subdomain-based tenant lookup (primary identification method)
CREATE INDEX IF NOT EXISTS idx_tenants_slug ON tenants(slug);

-- Index on status for filtering active tenants
CREATE INDEX IF NOT EXISTS idx_tenants_status ON tenants(status);

-- Index on schema_name for reverse lookups
CREATE INDEX IF NOT EXISTS idx_tenants_schema_name ON tenants(schema_name);

-- Composite index for active tenant queries
CREATE INDEX IF NOT EXISTS idx_tenants_status_slug ON tenants(status, slug);

-- =============================================================================
-- Part 3: Migration Verification Queries
-- =============================================================================

-- Verify table was created
-- SELECT name FROM sqlite_master WHERE type='table' AND name='tenants';

-- Verify indexes were created
-- SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='tenants';

-- Count tenants (should be 0 initially)
-- SELECT COUNT(*) FROM tenants;

-- =============================================================================
-- Part 4: Rollback Instructions
-- =============================================================================

-- To rollback this migration, run:
-- DROP TABLE IF EXISTS tenants;

-- =============================================================================
-- Notes for PostgreSQL
-- =============================================================================

-- For PostgreSQL deployments, this migration will:
-- 1. Create the tenants table in the public schema
-- 2. Store settings as JSONB instead of TEXT
-- 3. Use TIMESTAMPTZ instead of TEXT for timestamps
--
-- PostgreSQL-specific version would use:
-- - id UUID PRIMARY KEY DEFAULT gen_random_uuid()
-- - settings JSONB
-- - created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
-- - updated_at TIMESTAMPTZ
-- - deleted_at TIMESTAMPTZ

-- =============================================================================
-- Next Steps (Phase 7 Implementation)
-- =============================================================================

-- After this migration:
-- 1. Implement tenant service layer (libs/tenant/src/boundary/tenant/shell/service.clj)
-- 2. Add tenant middleware for schema switching
-- 3. Create tenant provisioning workflow
-- 4. Add tenant-specific migration runner
-- 5. Integrate with user module (split auth into public.auth_users)
--
-- See docs/adr/ADR-004-multi-tenancy-architecture.md for full roadmap.
