-- V022 — integracao (§3.3.6): outbox + idempotência de webhooks (P2).
-- Toda mutação de estoque/preço grava um evento no outbox na MESMA transação; um worker
-- @Scheduled consome com SELECT ... FOR UPDATE SKIP LOCKED, retry exponencial e dead-letter.
-- outbox_evento carrega id_tenant: o worker (fora do RLS) estabelece o TenantContext a
-- partir de evento.id_tenant antes de aplicar efeitos de domínio (P8).

CREATE TABLE outbox_evento (
  id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     bigint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  tipo          text        NOT NULL,               -- ex.: ESTOQUE_ATUALIZADO, PRECO_ATUALIZADO
  agregado_id   text,                               -- id do agregado afetado (ex.: id_variacao)
  payload       jsonb       NOT NULL,
  status        status_outbox NOT NULL DEFAULT 'PENDENTE',
  tentativas    integer     NOT NULL DEFAULT 0,
  proximo_retry timestamptz NOT NULL DEFAULT now(),
  processado_em timestamptz,
  erro          text,
  criado_em     timestamptz NOT NULL DEFAULT now()
);
-- Índice do worker: pega pendentes/erro cujo retry já venceu (ordem de despacho).
CREATE INDEX outbox_evento_fila_ix
  ON outbox_evento (proximo_retry)
  WHERE status IN ('PENDENTE', 'ERRO');
CREATE INDEX outbox_evento_id_tenant_ix ON outbox_evento (id_tenant);

CREATE TABLE webhook_recebido (
  id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     bigint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_canal      bigint      NOT NULL REFERENCES canal (id_canal),
  webhook_id    text        NOT NULL,               -- id do evento no marketplace (idempotência)
  recebido_em   timestamptz NOT NULL DEFAULT now(),
  processado_em timestamptz,
  erro          text,
  CONSTRAINT webhook_recebido_uk UNIQUE (id_canal, webhook_id)
);
CREATE INDEX webhook_recebido_id_tenant_ix ON webhook_recebido (id_tenant);

COMMENT ON TABLE outbox_evento    IS 'Outbox (P2). Worker despacha com FOR UPDATE SKIP LOCKED; id_tenant reidrata o TenantContext (P8).';
COMMENT ON TABLE webhook_recebido IS 'Idempotência de webhooks de marketplace por (canal, webhook_id).';
