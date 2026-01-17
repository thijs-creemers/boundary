-- Orders table
-- Stores completed orders with denormalized product data

CREATE TABLE IF NOT EXISTS orders (
    id TEXT PRIMARY KEY,
    order_number TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'pending',
    -- Customer info (denormalized for history)
    customer_email TEXT NOT NULL,
    customer_name TEXT NOT NULL,
    shipping_address TEXT NOT NULL, -- JSON
    -- Payment info
    payment_intent_id TEXT,
    payment_status TEXT,
    -- Totals
    subtotal_cents INTEGER NOT NULL,
    shipping_cents INTEGER NOT NULL DEFAULT 0,
    tax_cents INTEGER NOT NULL DEFAULT 0,
    total_cents INTEGER NOT NULL,
    currency TEXT NOT NULL DEFAULT 'EUR',
    -- Timestamps
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    paid_at TEXT,
    shipped_at TEXT,
    delivered_at TEXT,
    cancelled_at TEXT
);

-- Order items (denormalized product info at time of purchase)
CREATE TABLE IF NOT EXISTS order_items (
    id TEXT PRIMARY KEY,
    order_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    -- Denormalized product data (frozen at purchase time)
    product_name TEXT NOT NULL,
    product_price_cents INTEGER NOT NULL,
    quantity INTEGER NOT NULL,
    total_cents INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
);

-- Index for order lookups
CREATE INDEX IF NOT EXISTS idx_orders_number ON orders(order_number);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_email);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_payment ON orders(payment_intent_id);

-- Index for order items
CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items(order_id);
