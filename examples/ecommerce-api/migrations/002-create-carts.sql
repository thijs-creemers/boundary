-- Carts table
-- Session-based shopping carts; one cart per browser session
--
-- Type decisions:
--   VARCHAR(36)  — UUIDs
--   VARCHAR(128) — session IDs (browser-generated, variable length ≤ 128)
--   VARCHAR(30)  — ISO 8601 timestamps
--   INTEGER      — quantity; pos-int? in schema so CHECK(quantity > 0)

CREATE TABLE IF NOT EXISTS carts (
    id         VARCHAR(36)   NOT NULL PRIMARY KEY,
    session_id VARCHAR(128)  NOT NULL UNIQUE,
    created_at VARCHAR(30)   NOT NULL,
    updated_at VARCHAR(30),
    deleted_at VARCHAR(30)
);

-- Cart items (line items in a cart)
-- UNIQUE(cart_id, product_id) prevents duplicate products; use upsert to update quantity instead
CREATE TABLE IF NOT EXISTS cart_items (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    cart_id    VARCHAR(36)  NOT NULL,
    product_id VARCHAR(36)  NOT NULL,
    quantity   INTEGER      NOT NULL DEFAULT 1 CHECK(quantity > 0),
    created_at VARCHAR(30)  NOT NULL,
    updated_at VARCHAR(30),
    deleted_at VARCHAR(30),
    FOREIGN KEY (cart_id)    REFERENCES carts(id)    ON DELETE CASCADE,
    FOREIGN KEY (product_id) REFERENCES products(id),
    UNIQUE(cart_id, product_id)
);

-- Index for cart lookups by session (most common query pattern)
CREATE INDEX IF NOT EXISTS idx_carts_session    ON carts(session_id);

-- Index for fetching all items in a cart
CREATE INDEX IF NOT EXISTS idx_cart_items_cart  ON cart_items(cart_id);
