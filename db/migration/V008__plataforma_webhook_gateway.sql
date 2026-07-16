-- V008 — webhook_gateway: idempotência das notificações do gateway de cobrança.
-- Mesmo padrão de `webhook_recebido` dos marketplaces (§3.3.6): (gateway, evento_id)
-- único; um worker consome com FOR UPDATE SKIP LOCKED e aplica efeitos via outbox (P2).

CREATE TABLE plataforma.webhook_gateway (
  id            integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  gateway       text        NOT NULL,
  evento_id     text        NOT NULL,
  tipo          text,
  payload       jsonb       NOT NULL,
  recebido_em   timestamptz NOT NULL DEFAULT now(),
  processado_em timestamptz,
  erro          text,
  CONSTRAINT webhook_gateway_evento_uk UNIQUE (gateway, evento_id)
);

-- fila de não processados (consumo pelo worker)
CREATE INDEX webhook_gateway_pendentes_ix
  ON plataforma.webhook_gateway (recebido_em)
  WHERE processado_em IS NULL;

COMMENT ON TABLE plataforma.webhook_gateway IS 'Notificacoes do gateway (idempotentes por gateway+evento_id). Efeitos de cobranca via outbox (R16/P2).';
