-- V021 — pedidos de marketplace: fila única de expedição (R5), idempotente por
-- (canal, id_externo) (P2). A reserva de estoque ocorre no RECEBIDO (Q2/ADR-004).

CREATE TABLE pedido (
  id_pedido     integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_canal      integer       NOT NULL,
  id_externo    text          NOT NULL,               -- número do pedido no canal
  status        status_pedido NOT NULL DEFAULT 'RECEBIDO',
  comprador     jsonb,
  total         numeric(12,2) NOT NULL DEFAULT 0,
  frete         numeric(12,2) NOT NULL DEFAULT 0,
  payload_bruto jsonb,                              -- payload cru do marketplace (JSONB)
  reserva_expira_em timestamptz,                    -- Q2: expiração da reserva se não pagar
  criado_em     timestamptz   NOT NULL DEFAULT now(),
  atualizado_em timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT pedido_canal_externo_uk UNIQUE (id_canal, id_externo),   -- idempotência (P2)
  -- base para FK composta (2026-07-16, P8) de pedido_item.id_pedido.
  CONSTRAINT pedido_id_pedido_uk UNIQUE (id_tenant, id_pedido),
  -- FK composta — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT pedido_canal_fk FOREIGN KEY (id_tenant, id_canal)
    REFERENCES canal (id_tenant, id_canal)
);
CREATE INDEX pedido_id_tenant_ix ON pedido (id_tenant);
CREATE INDEX pedido_status_ix    ON pedido (id_tenant, status);

CREATE TABLE pedido_item (
  id_pedido_item integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant      smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_pedido      integer       NOT NULL,
  id_variacao    integer       NOT NULL,
  id_anuncio     integer,
  quantidade     numeric(14,3) NOT NULL,
  preco_unit     numeric(12,2) NOT NULL DEFAULT 0,
  -- FKs compostas (2026-07-16, P8) — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT pedido_item_pedido_fk FOREIGN KEY (id_tenant, id_pedido)
    REFERENCES pedido (id_tenant, id_pedido),
  CONSTRAINT pedido_item_variacao_fk FOREIGN KEY (id_tenant, id_variacao)
    REFERENCES produto_barra (id_tenant, id_variacao),
  CONSTRAINT pedido_item_anuncio_fk FOREIGN KEY (id_tenant, id_anuncio)
    REFERENCES anuncio (id_tenant, id_anuncio)
);
CREATE INDEX pedido_item_id_tenant_ix ON pedido_item (id_tenant);
CREATE INDEX pedido_item_id_pedido_ix ON pedido_item (id_pedido);

COMMENT ON TABLE pedido IS 'Pedido de canal (fila única R5). Idempotente por (canal, id_externo) (P2). Reserva no RECEBIDO (Q2).';
