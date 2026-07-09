-- V005 — plano (tiers de preço e seus limites/entitlements — R13).
-- Limites NULL = ilimitado. Preços em NUMERIC (P7). Valores de fato ficam no seed (V012).

CREATE TABLE plataforma.plano (
  id_plano           bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nome               text          NOT NULL,
  descricao          text,
  ciclo_padrao       plataforma.ciclo_cobranca NOT NULL DEFAULT 'MENSAL',
  preco_mensal       numeric(12,2) NOT NULL CHECK (preco_mensal >= 0),
  preco_anual        numeric(12,2) NOT NULL CHECK (preco_anual  >= 0),
  ativo              boolean       NOT NULL DEFAULT true,
  limite_canais      integer       CHECK (limite_canais      IS NULL OR limite_canais      >= 0),
  limite_produtos    integer       CHECK (limite_produtos    IS NULL OR limite_produtos    >= 0),
  limite_usuarios    integer       CHECK (limite_usuarios    IS NULL OR limite_usuarios    >= 0),
  limite_pedidos_mes integer       CHECK (limite_pedidos_mes IS NULL OR limite_pedidos_mes >= 0),
  criado_em          timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT plano_nome_uk UNIQUE (nome)
);

COMMENT ON TABLE  plataforma.plano IS 'Catalogo de planos/tiers e seus limites (R13). Consumido pelo enforcement R19.';
COMMENT ON COLUMN plataforma.plano.limite_canais      IS 'Canais online simultaneos. NULL = ilimitado.';
COMMENT ON COLUMN plataforma.plano.limite_produtos    IS 'SKUs (produto x variacao). NULL = ilimitado.';
COMMENT ON COLUMN plataforma.plano.limite_usuarios    IS 'Usuarios do tenant. NULL = ilimitado.';
COMMENT ON COLUMN plataforma.plano.limite_pedidos_mes IS 'Pedidos importados/mes. Soft-cap: nunca descarta pedido (R19).';
