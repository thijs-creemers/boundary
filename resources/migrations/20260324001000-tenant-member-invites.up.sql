CREATE TABLE IF NOT EXISTS public.tenant_member_invites (
  id                   VARCHAR(255)             NOT NULL PRIMARY KEY,
  tenant_id            VARCHAR(255)             NOT NULL,
  email                VARCHAR(320)             NOT NULL,
  role                 VARCHAR(255)             NOT NULL,
  status               VARCHAR(255)             NOT NULL,
  token_hash           VARCHAR(255)             NOT NULL UNIQUE,
  expires_at           TIMESTAMP WITH TIME ZONE NOT NULL,
  accepted_at          TIMESTAMP WITH TIME ZONE,
  revoked_at           TIMESTAMP WITH TIME ZONE,
  accepted_by_user_id  VARCHAR(255),
  metadata             JSONB,
  created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at           TIMESTAMP WITH TIME ZONE
);
--;;
CREATE INDEX IF NOT EXISTS idx_tenant_member_invites_tenant_id ON public.tenant_member_invites (tenant_id);
--;;
CREATE INDEX IF NOT EXISTS idx_tenant_member_invites_email ON public.tenant_member_invites (email);
--;;
CREATE INDEX IF NOT EXISTS idx_tenant_member_invites_status ON public.tenant_member_invites (status);
