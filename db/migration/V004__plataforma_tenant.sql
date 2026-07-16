-- V004 — tenant (conta assinante). É a raiz do isolamento: `empresa.id_tenant` e
-- toda tabela de domínio referenciam este id. Global (P9), sem RLS de tenant.

CREATE TABLE plataforma.tenant (
  id_tenant     smallint    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nome_conta    text        NOT NULL,
  slug          text        NOT NULL,
  email_contato text        NOT NULL,
  status        plataforma.status_tenant NOT NULL DEFAULT 'TRIAL',
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  cancelado_em  timestamptz,
  CONSTRAINT tenant_slug_uk UNIQUE (slug),
  CONSTRAINT tenant_slug_formato_ck
    CHECK (slug ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$')
);

COMMENT ON TABLE  plataforma.tenant IS 'Conta assinante do SaaS. tenant 1:N empresa (1:1 no v1). Fecha Q6.';
COMMENT ON COLUMN plataforma.tenant.slug   IS 'Identificador URL-safe da conta (subdominio/rota).';
COMMENT ON COLUMN plataforma.tenant.status IS 'TRIAL ao nascer (signup R12); gate de login do ERP depende dele (R16/R20).';
