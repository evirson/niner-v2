-- V006 — assinatura (vínculo tenant ↔ plano, com ciclo de vida e trial).
-- No máximo UMA assinatura viva (não cancelada) por tenant.

CREATE TABLE plataforma.assinatura (
  id_assinatura         integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant             smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_plano              integer      NOT NULL REFERENCES plataforma.plano (id_plano),
  status                plataforma.status_assinatura NOT NULL DEFAULT 'TRIAL',
  ciclo                 plataforma.ciclo_cobranca    NOT NULL DEFAULT 'MENSAL',
  trial_expira_em       timestamptz,
  inicio_vigencia       date,
  fim_vigencia          date,
  proxima_cobranca      date,
  id_gateway_assinatura text,                       -- ref da recorrencia no gateway (ADR-008)
  criado_em             timestamptz NOT NULL DEFAULT now(),
  atualizado_em         timestamptz NOT NULL DEFAULT now()
);

-- Uma assinatura viva por tenant (histórico de canceladas é permitido).
CREATE UNIQUE INDEX assinatura_tenant_viva_uk
  ON plataforma.assinatura (id_tenant)
  WHERE status <> 'CANCELADA';

CREATE INDEX assinatura_id_tenant_ix ON plataforma.assinatura (id_tenant);
CREATE INDEX assinatura_id_plano_ix  ON plataforma.assinatura (id_plano);

COMMENT ON TABLE  plataforma.assinatura IS 'Assinatura do tenant (R14/R15). Criada em TRIAL no signup (trial_expira_em = +14d).';
COMMENT ON COLUMN plataforma.assinatura.id_gateway_assinatura IS 'Ref da recorrencia no gateway; preenchida pelo adapter (D3 adiada).';
