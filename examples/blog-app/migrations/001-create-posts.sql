-- Blog posts table
-- Stores all blog posts with their content and metadata

CREATE TABLE IF NOT EXISTS posts (
    id TEXT PRIMARY KEY,
    author_id TEXT,
    title TEXT NOT NULL,
    slug TEXT NOT NULL UNIQUE,
    content TEXT NOT NULL,
    excerpt TEXT,
    published INTEGER NOT NULL DEFAULT 0,
    published_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Index for slug lookups (used in URLs)
CREATE INDEX IF NOT EXISTS idx_posts_slug ON posts(slug);

-- Index for listing published posts
CREATE INDEX IF NOT EXISTS idx_posts_published ON posts(published, published_at DESC);

-- Index for author's posts
CREATE INDEX IF NOT EXISTS idx_posts_author ON posts(author_id);
