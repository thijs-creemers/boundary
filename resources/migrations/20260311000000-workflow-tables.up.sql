-- workflow_instances: tracks the current state of a domain entity within a workflow
CREATE TABLE IF NOT EXISTS workflow_instances (
  id             TEXT NOT NULL PRIMARY KEY,
  workflow_id    TEXT NOT NULL,
  entity_type    TEXT NOT NULL,
  entity_id      TEXT NOT NULL,
  current_state  TEXT NOT NULL,
  created_at     TEXT NOT NULL,
  updated_at     TEXT NOT NULL,
  metadata       TEXT
);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_instances_entity
  ON workflow_instances (entity_type, entity_id);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_instances_workflow_id
  ON workflow_instances (workflow_id);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_instances_current_state
  ON workflow_instances (current_state);
--;;

-- workflow_audit: immutable record of every state transition
CREATE TABLE IF NOT EXISTS workflow_audit (
  id           TEXT NOT NULL PRIMARY KEY,
  instance_id  TEXT NOT NULL REFERENCES workflow_instances(id),
  workflow_id  TEXT NOT NULL,
  entity_type  TEXT NOT NULL,
  entity_id    TEXT NOT NULL,
  transition   TEXT NOT NULL,
  from_state   TEXT NOT NULL,
  to_state     TEXT NOT NULL,
  actor_id     TEXT,
  actor_roles  TEXT,
  context      TEXT,
  occurred_at  TEXT NOT NULL
);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_audit_instance_id
  ON workflow_audit (instance_id);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_audit_occurred_at
  ON workflow_audit (occurred_at);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_audit_actor_id
  ON workflow_audit (actor_id);
