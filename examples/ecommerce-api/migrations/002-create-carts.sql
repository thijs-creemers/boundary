-- Carts table
-- Session-based shopping carts

CREATE TABLE IF NOT EXISTS carts (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Cart items (line items in a cart)
CREATE TABLE IF NOT EXISTS cart_items (
    id TEXT PRIMARY KEY,
    cart_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (cart_id) REFERENCES carts(id) ON DELETE CASCADE,
    FOREIGN KEY (product_id) REFERENCES products(id),
    UNIQUE(cart_id, product_id)
);

-- Index for cart lookups by session
CREATE INDEX IF NOT EXISTS idx_carts_session ON carts(session_id);

-- Index for cart items by cart
CREATE INDEX IF NOT EXISTS idx_cart_items_cart ON cart_items(cart_id);
