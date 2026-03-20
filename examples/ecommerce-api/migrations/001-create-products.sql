-- Products table
-- Stores product catalog with pricing in cents to avoid float issues
--
-- Type decisions:
--   VARCHAR(36)  — UUIDs (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx = 36 chars)
--   VARCHAR(200) — bounded strings; max derived from Malli schema
--   VARCHAR(3)   — ISO 4217 currency codes (EUR, USD, GBP)
--   VARCHAR(30)  — ISO 8601 timestamps (2026-01-01T00:00:00.000000Z ≤ 30 chars)
--   INTEGER      — cents amounts and stock; avoids floating-point rounding
--   TEXT         — unbounded text (description has no max length in the entity)

CREATE TABLE IF NOT EXISTS products (
    id          VARCHAR(36)   NOT NULL PRIMARY KEY,
    name        VARCHAR(200)  NOT NULL,
    slug        VARCHAR(200)  NOT NULL UNIQUE,
    description TEXT,
    price_cents INTEGER       NOT NULL CHECK(price_cents > 0),
    currency    VARCHAR(3)    NOT NULL DEFAULT 'EUR'
                              CHECK(currency IN ('EUR', 'USD', 'GBP')),
    stock       INTEGER       NOT NULL DEFAULT 0 CHECK(stock >= 0),
    active      INTEGER       NOT NULL DEFAULT 1 CHECK(active IN (0, 1)),
    created_at  VARCHAR(30)   NOT NULL,
    updated_at  VARCHAR(30),
    deleted_at  VARCHAR(30)
);

-- Index for slug lookups (common for SEO-friendly product URLs)
CREATE INDEX IF NOT EXISTS idx_products_slug   ON products(slug);

-- Index for active products listing (most queries filter by active = 1)
CREATE INDEX IF NOT EXISTS idx_products_active ON products(active);

-- Seed some example products
INSERT INTO products (id, name, slug, description, price_cents, currency, stock, active, created_at, updated_at)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'Boundary Framework T-Shirt', 'boundary-tshirt',    'Comfortable cotton t-shirt with Boundary logo. Available in black.',        2999, 'EUR', 100, 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('22222222-2222-2222-2222-222222222222', 'Clojure Mug',                'clojure-mug',        'Ceramic mug with Clojure lambda logo. Perfect for your morning coffee.',   1499, 'EUR',  50, 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('33333333-3333-3333-3333-333333333333', 'Functional Programming Book', 'fp-book',           'Learn functional programming principles with practical Clojure examples.', 4999, 'EUR',  25, 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
    ('44444444-4444-4444-4444-444444444444', 'REPL Sticker Pack',           'repl-stickers',     'Set of 10 developer stickers featuring REPL-driven development themes.',    799, 'EUR', 200, 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
