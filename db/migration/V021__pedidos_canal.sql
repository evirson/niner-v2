-- V021 — pedidos de marketplace: fila única de expedição (R5), idempotente por
-- (canal, id_externo) (P2). A reserva de estoque ocorre no RECEBIDO (Q2/ADR-004).

CREATE TABLE pedido (
  id_pedido     bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     bigint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_canal      bigint      NOT NULL REFERENCES canal (id_canal),
  id_externo    text        NOT NULL,               -- número do pedido no canal
  status        status_pedido NOT NULL DEFAULT 'RECEBIDO',
  comprador     jsonb,
  total         numeric(12,2) NOT NULL DEFAULT 0,
  frete         numeric(12,2) NOT NULL DEFAULT 0,
  payload_bruto jsonb,                              -- payload cru do marketplace (JSONB)
  reserva_expira_em timestamptz,                    -- Q2: expiração da reserva se não pagar
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pedido_canal_externo_uk UNIQUE (id_canal, id_externo)   -- idempotência (P2)
);
CREATE INDEX pedido_id_tenant_ix ON pedido (id_tenant);
CREATE INDEX pedido_status_ix    ON pedido (id_tenant, status);

CREATE TABLE pedido_item (
  id_pedido_item bigint     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     bigint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_pedido     bigint      NOT NULL REFERENCES pedido (id_pedido) ON DELETE CASCADE,
  id_variacao   bigint      NOT NULL REFERENCES produto_barra (id_variacao),
  id_anuncio    bigint      REFERENCES anuncio (id_anuncio),
  quantidade    numeric(14,3) NOT NULL,
  preco_unit    numeric(12,2) NOT NULL DEFAULT 0
);
CREATE INDEX pedido_item_id_tenant_ix ON pedido_item (id_tenant);
CREATE INDEX pedido_item_id_pedido_ix ON pedido_item (id_pedido);

COMMENT ON TABLE pedido IS 'Pedido de canal (fila única R5). Idempotente por (canal, id_externo) (P2). Reserva no RECEBIDO (Q2).';
