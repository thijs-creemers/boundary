-- Products table
-- Stores product catalog with pricing in cents to avoid float issues

CREATE TABLE IF NOT EXISTS products (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    slug TEXT NOT NULL UNIQUE,
    description TEXT,
    price_cents INTEGER NOT NULL,
    currency TEXT NOT NULL DEFAULT 'EUR',
    stock INTEGER NOT NULL DEFAULT 0,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Index for slug lookups (common for API)
CREATE INDEX IF NOT EXISTS idx_products_slug ON products(slug);

-- Index for active products listing
CREATE INDEX IF NOT EXISTS idx_products_active ON products(active);

-- Seed some example products
INSERT INTO products (id, name, slug, description, price_cents, currency, stock, active, created_at, updated_at)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'Boundary Framework T-Shirt', 'boundary-tshirt', 'Comfortable cotton t-shirt with Boundary logo. Available in black.', 2999, 'EUR', 100, 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('22222222-2222-2222-2222-222222222222', 'Clojure Mug', 'clojure-mug', 'Ceramic mug with Clojure lambda logo. Perfect for your morning coffee.', 1499, 'EUR', 50, 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('33333333-3333-3333-3333-333333333333', 'Functional Programming Book', 'fp-book', 'Learn functional programming principles with practical Clojure examples.', 4999, 'EUR', 25, 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('44444444-4444-4444-4444-444444444444', 'REPL Sticker Pack', 'repl-stickers', 'Set of 10 developer stickers featuring REPL-driven development themes.', 799, 'EUR', 200, 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
