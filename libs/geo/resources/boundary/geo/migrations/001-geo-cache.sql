-- boundary-geo: geo_cache table
-- Stores geocoding results keyed by SHA-256 hash of the normalised address query.
-- TTL enforcement happens at the application layer (SELECT with created_at check).

CREATE TABLE geo_cache (
  address_hash      TEXT PRIMARY KEY,
  lat               NUMERIC(10, 7) NOT NULL,
  lng               NUMERIC(10, 7) NOT NULL,
  formatted_address TEXT,
  postcode          TEXT,
  city              TEXT,
  country           TEXT,
  provider          TEXT NOT NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_geo_cache_coords ON geo_cache(lat, lng);
