CREATE TABLE audience_segments (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  audience_id   VARCHAR(255) NOT NULL UNIQUE,
  label         VARCHAR(255) NOT NULL,
  description   TEXT,
  filters       JSONB NOT NULL,
  composition   JSONB,
  cache_config  JSONB,
  tags          JSONB,
  member_count  INTEGER DEFAULT 0,
  cached_at     TIMESTAMP,
  source        VARCHAR(50) DEFAULT 'dynamic',
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE TABLE audience_memberships (
  audience_id   UUID REFERENCES audience_segments(id) ON DELETE CASCADE,
  user_id       UUID NOT NULL,
  entered_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (audience_id, user_id)
);

--;;

CREATE INDEX idx_audience_memberships_user ON audience_memberships(user_id);
