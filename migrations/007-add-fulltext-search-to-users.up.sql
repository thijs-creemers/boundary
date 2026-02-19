-- =============================================================================
-- Migration 007: Add Full-Text Search to Users Table
-- =============================================================================
-- This migration adds PostgreSQL full-text search capability to the users table
-- using tsvector, tsquery, and GIN indexes for efficient text searching.
--
-- Features:
-- - Generated tsvector column combining name (weight A), email (weight B)
-- - GIN index for fast full-text search queries
-- - Automatic maintenance via GENERATED column (PostgreSQL 12+)
--
-- Weight Strategy:
--   A (1.0) = name          - Highest relevance for user search
--   B (0.4) = email         - Secondary relevance
--
-- Date: 2026-01-04
-- Author: Boundary Team
-- Phase: 4.5 - Full-Text Search Implementation
-- =============================================================================

-- =============================================================================
-- COMPATIBILITY CHECK
-- =============================================================================
-- This migration requires PostgreSQL 12+ for GENERATED columns
-- For older versions, use triggers to maintain the tsvector column
-- =============================================================================

-- =============================================================================
-- Add Full-Text Search Vector Column
-- =============================================================================

-- Add the tsvector column as a GENERATED column
-- This automatically updates whenever name or email changes
ALTER TABLE users 
  ADD COLUMN IF NOT EXISTS search_vector tsvector 
  GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(email, '')), 'B')
  ) STORED;

-- =============================================================================
-- Create GIN Index for Fast Full-Text Search
-- =============================================================================

-- GIN (Generalized Inverted Index) provides fast full-text search
-- Index creation may take time on large tables
CREATE INDEX IF NOT EXISTS users_search_vector_idx 
  ON users 
  USING GIN(search_vector);

-- =============================================================================
-- Add Column Documentation
-- =============================================================================

COMMENT ON COLUMN users.search_vector IS 
  'Full-text search vector combining name (weight A) and email (weight B). 
   Automatically maintained via GENERATED column. 
   Use with @@ operator and ts_rank for relevance scoring.
   
   Example query:
   SELECT * FROM users 
   WHERE search_vector @@ to_tsquery(''english'', ''John:*'') 
   ORDER BY ts_rank(search_vector, to_tsquery(''english'', ''John:*'')) DESC;';

-- =============================================================================
-- Verify Installation
-- =============================================================================

-- Check that the search_vector column exists and has a GIN index
DO $$
BEGIN
  -- Verify column exists
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns 
    WHERE table_name = 'users' AND column_name = 'search_vector'
  ) THEN
    RAISE EXCEPTION 'search_vector column was not created';
  END IF;
  
  -- Verify GIN index exists
  IF NOT EXISTS (
    SELECT 1 FROM pg_indexes 
    WHERE tablename = 'users' AND indexname = 'users_search_vector_idx'
  ) THEN
    RAISE EXCEPTION 'users_search_vector_idx was not created';
  END IF;
  
  RAISE NOTICE 'Full-text search successfully added to users table';
END $$;

-- =============================================================================
-- Performance Notes
-- =============================================================================
-- GIN Index Size: Approximately 30-40% of table size
-- Search Performance: Typically <50ms for simple queries on tables with <1M rows
-- Maintenance: GENERATED column automatically updates on INSERT/UPDATE
-- 
-- For very large tables (>10M rows), consider:
-- - Partitioning by created_at
-- - Using pg_trgm for fuzzy matching
-- - Materialized views for complex search scenarios
-- =============================================================================
