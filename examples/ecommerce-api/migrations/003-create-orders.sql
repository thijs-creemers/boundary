-- Orders table
-- Stores completed orders with customer and payment information
--
-- Type decisions:
--   VARCHAR(36)  — UUIDs
--   VARCHAR(50)  — order number (format: ORD-YYYYMMDD-XXXXX ≈ 22 chars; 50 gives headroom)
--   VARCHAR(20)  — status enum values (longest: 'cancelled' = 9 chars)
--   VARCHAR(100) — payment intent IDs (Stripe format: pi_3Xyz... ≈ 27 chars; 100 is generous)
--   VARCHAR(50)  — payment status strings
--   VARCHAR(200) — customer name (matches Malli :customer-name schema)
--   VARCHAR(255) — email addresses (RFC 5321 max local+domain = 254 chars)
--   VARCHAR(3)   — ISO 4217 currency codes
--   VARCHAR(30)  — ISO 8601 timestamps
--   TEXT         — shipping_address stored as JSON; no length constraint needed
--   INTEGER      — all monetary amounts in cents; avoids floating-point rounding

CREATE TABLE IF NOT EXISTS orders (
    id                VARCHAR(36)   NOT NULL PRIMARY KEY,
    order_number      VARCHAR(50)   NOT NULL UNIQUE,
    status            VARCHAR(20)   NOT NULL DEFAULT 'pending'
                                    CHECK(status IN ('pending', 'paid', 'shipped', 'delivered', 'cancelled', 'refunded')),

    -- Customer info (denormalized for audit history; survives account deletion)
    customer_email    VARCHAR(255)  NOT NULL,
    customer_name     VARCHAR(200)  NOT NULL,
    shipping_address  TEXT          NOT NULL, -- JSON: {line1, line2, city, postal-code, country}

    -- Payment info
    payment_intent_id VARCHAR(100),
    payment_status    VARCHAR(50),

    -- Totals (all in cents; currency stored alongside to avoid ambiguity)
    subtotal_cents    INTEGER       NOT NULL CHECK(subtotal_cents >= 0),
    shipping_cents    INTEGER       NOT NULL DEFAULT 0 CHECK(shipping_cents >= 0),
    tax_cents         INTEGER       NOT NULL DEFAULT 0 CHECK(tax_cents >= 0),
    total_cents       INTEGER       NOT NULL CHECK(total_cents > 0),
    currency          VARCHAR(3)    NOT NULL DEFAULT 'EUR'
                                    CHECK(currency IN ('EUR', 'USD', 'GBP')),

    -- Timestamps (nullable lifecycle timestamps set by state machine transitions)
    created_at        VARCHAR(30)   NOT NULL,
    updated_at        VARCHAR(30),
    deleted_at        VARCHAR(30),
    paid_at           VARCHAR(30),
    shipped_at        VARCHAR(30),
    delivered_at      VARCHAR(30),
    cancelled_at      VARCHAR(30)
);

-- Order items
-- Denormalized product snapshot frozen at purchase time.
-- No FK on product_id by design: products can be deleted after purchase,
-- but the order history must remain intact.
CREATE TABLE IF NOT EXISTS order_items (
    id                  VARCHAR(36)   NOT NULL PRIMARY KEY,
    order_id            VARCHAR(36)   NOT NULL,
    product_id          VARCHAR(36)   NOT NULL, -- reference only; no FK (see above)

    -- Product snapshot at time of purchase
    product_name        VARCHAR(200)  NOT NULL,
    product_price_cents INTEGER       NOT NULL CHECK(product_price_cents > 0),
    quantity            INTEGER       NOT NULL CHECK(quantity > 0),
    total_cents         INTEGER       NOT NULL CHECK(total_cents > 0),

    created_at          VARCHAR(30)   NOT NULL,
    updated_at          VARCHAR(30),
    deleted_at          VARCHAR(30),
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
);

-- Indexes for common order query patterns
CREATE INDEX IF NOT EXISTS idx_orders_number      ON orders(order_number);
CREATE INDEX IF NOT EXISTS idx_orders_customer    ON orders(customer_email);
CREATE INDEX IF NOT EXISTS idx_orders_status      ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_payment     ON orders(payment_intent_id);

-- Index for fetching all items belonging to an order
CREATE INDEX IF NOT EXISTS idx_order_items_order  ON order_items(order_id);
