-- Migration 005: Create items table

CREATE TABLE IF NOT EXISTS items (
  id UUID PRIMARY KEY,
  name TEXT NOT NULL,
  sku TEXT NOT NULL UNIQUE,
  quantity INTEGER NOT NULL,
  location TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_items_created_at ON items(created_at);
