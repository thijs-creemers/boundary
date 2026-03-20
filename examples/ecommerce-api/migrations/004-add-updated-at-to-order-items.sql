-- Add updated_at to order_items
-- Omitted from the original schema because order items were treated as immutable
-- purchase snapshots. Now that the admin allows editing them, we track updates.
-- Nullable so existing rows (created via checkout) are not broken.

ALTER TABLE order_items ADD COLUMN updated_at VARCHAR(30);
