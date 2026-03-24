CREATE TABLE IF NOT EXISTS public.tenant_memberships (
  id           VARCHAR(255)             NOT NULL PRIMARY KEY,
  tenant_id    VARCHAR(255)             NOT NULL,
  user_id      VARCHAR(255)             NOT NULL,
  role         VARCHAR(255)             NOT NULL,
  status       VARCHAR(255)             NOT NULL,
  invited_at   TIMESTAMP WITH TIME ZONE NOT NULL,
  accepted_at  TIMESTAMP WITH TIME ZONE,
  created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at   TIMESTAMP WITH TIME ZONE,
  UNIQUE (tenant_id, user_id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_tenant_memberships_tenant_id ON public.tenant_memberships (tenant_id);
--;;
CREATE INDEX IF NOT EXISTS idx_tenant_memberships_user_id   ON public.tenant_memberships (user_id);
--;;
CREATE INDEX IF NOT EXISTS idx_tenant_memberships_status    ON public.tenant_memberships (status);
