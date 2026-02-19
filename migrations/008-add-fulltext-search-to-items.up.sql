-- =============================================================================
-- Migration 008: Add Full-Text Search to Items Table
-- =============================================================================
-- This migration adds PostgreSQL full-text search capability to the items table
-- using tsvector, tsquery, and GIN indexes for efficient text searching.
--
-- Features:
-- - Generated tsvector column combining name (weight A), sku (weight B), location (weight C)
-- - GIN index for fast full-text search queries
-- - Automatic maintenance via GENERATED column (PostgreSQL 12+)
--
-- Weight Strategy:
--   A (1.0) = name          - Highest relevance for item search
--   B (0.4) = sku           - Secondary relevance (product code)
--   C (0.2) = location      - Tertiary relevance (where stored)
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
-- This automatically updates whenever name, sku, or location changes
ALTER TABLE items 
  ADD COLUMN IF NOT EXISTS search_vector tsvector 
  GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(sku, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(location, '')), 'C')
  ) STORED;

-- =============================================================================
-- Create GIN Index for Fast Full-Text Search
-- =============================================================================

-- GIN (Generalized Inverted Index) provides fast full-text search
-- Index creation may take time on large tables
CREATE INDEX IF NOT EXISTS items_search_vector_idx 
  ON items 
  USING GIN(search_vector);

-- =============================================================================
-- Add Column Documentation
-- =============================================================================

COMMENT ON COLUMN items.search_vector IS 
  'Full-text search vector combining name (weight A), sku (weight B), and location (weight C). 
   Automatically maintained via GENERATED column. 
   Use with @@ operator and ts_rank for relevance scoring.
   
   Example query:
   SELECT * FROM items 
   WHERE search_vector @@ to_tsquery(''english'', ''laptop:*'') 
   ORDER BY ts_rank(
     array[1.0, 0.4, 0.2, 0.1], 
     search_vector, 
     to_tsquery(''english'', ''laptop:*'')
   ) DESC;';

-- =============================================================================
-- Verify Installation
-- =============================================================================

-- Check that the search_vector column exists and has a GIN index
DO $$
BEGIN
  -- Verify column exists
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns 
    WHERE table_name = 'items' AND column_name = 'search_vector'
  ) THEN
    RAISE EXCEPTION 'search_vector column was not created';
  END IF;
  
  -- Verify GIN index exists
  IF NOT EXISTS (
    SELECT 1 FROM pg_indexes 
    WHERE tablename = 'items' AND indexname = 'items_search_vector_idx'
  ) THEN
    RAISE EXCEPTION 'items_search_vector_idx was not created';
  END IF;
  
  RAISE NOTICE 'Full-text search successfully added to items table';
END $$;

-- =============================================================================
-- Performance Notes
-- =============================================================================
-- GIN Index Size: Approximately 30-40% of table size
-- Search Performance: Typically <50ms for simple queries on tables with <1M rows
-- Maintenance: GENERATED column automatically updates on INSERT/UPDATE
-- 
-- For very large inventories (>10M items), consider:
-- - Partitioning by created_at or location
-- - Using pg_trgm for fuzzy matching on SKU
-- - Separate indexes for exact SKU lookups
-- - Materialized views for complex search scenarios
-- =============================================================================
