-- Add deleted_at to all tables that are missing it
-- Convention: every table has created_at, updated_at, deleted_at.
-- A non-NULL deleted_at marks a soft-deleted row; NULL means active.

ALTER TABLE products   ADD COLUMN deleted_at VARCHAR(30);
ALTER TABLE carts      ADD COLUMN deleted_at VARCHAR(30);
ALTER TABLE cart_items ADD COLUMN deleted_at VARCHAR(30);
ALTER TABLE orders     ADD COLUMN deleted_at VARCHAR(30);
ALTER TABLE order_items ADD COLUMN deleted_at VARCHAR(30);
