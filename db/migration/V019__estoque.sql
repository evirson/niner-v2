-- V019 — estoque (§3.3.4): saldo materializado, ledger imutável e balanço.
-- P1: o ERP é a fonte da verdade do estoque. P3: cada movimento grava o saldo_apos.
-- Q2/ADR-004: `reservado` sobe no RECEBIDO do pedido; `disponivel` = qtd_estoque − reservado.
-- A SP_ATUALIZA_QUANTIDADE_ESTOQUE legada é substituída pelo domínio Java (não trigger).

-- Saldo por variação × empresa.
CREATE TABLE produto_estoque (
  id_produto_estoque bigint    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     bigint         NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa    bigint         NOT NULL REFERENCES empresa (id_empresa),
  id_variacao   bigint         NOT NULL REFERENCES produto_barra (id_variacao),
  qtd_estoque   numeric(14,3)  NOT NULL DEFAULT 0,
  reservado     numeric(14,3)  NOT NULL DEFAULT 0,      -- Q2/ADR-004 (P1 anti-overselling)
  minimo        numeric(14,3)  NOT NULL DEFAULT 0,      -- alerta de estoque mínimo
  disponivel    numeric(14,3)  GENERATED ALWAYS AS (qtd_estoque - reservado) STORED,
  atualizado_em timestamptz    NOT NULL DEFAULT now(),
  CONSTRAINT produto_estoque_uk UNIQUE (id_tenant, id_empresa, id_variacao),
  CONSTRAINT produto_estoque_reservado_ck CHECK (reservado >= 0)
);
CREATE INDEX produto_estoque_id_tenant_ix ON produto_estoque (id_tenant);
CREATE INDEX produto_estoque_variacao_ix  ON produto_estoque (id_tenant, id_variacao);

-- Ledger de movimentação — mestre (imutável, P3).
CREATE TABLE produto_movimento_mestre (
  id_movimento    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant       bigint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa      bigint      NOT NULL REFERENCES empresa (id_empresa),
  tipo_movimento  tipo_movimento NOT NULL,
  data_movimento  timestamptz NOT NULL DEFAULT now(),
  id_fornecedor   bigint      REFERENCES fornecedor (id_fornecedor),
  id_venda        bigint      REFERENCES venda (id_venda),
  id_transferencia bigint,
  id_devolucao    bigint      REFERENCES venda_devolucao (id_devolucao),
  nota_fiscal     text
);
CREATE INDEX produto_movimento_mestre_id_tenant_ix ON produto_movimento_mestre (id_tenant);
CREATE INDEX produto_movimento_mestre_data_ix      ON produto_movimento_mestre (id_tenant, data_movimento);

-- Ledger de movimentação — detalhe (linha por SKU; grava saldo resultante, P3).
CREATE TABLE produto_movimento_detalhe (
  id_movimento_detalhe bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant       bigint       NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_movimento    bigint       NOT NULL REFERENCES produto_movimento_mestre (id_movimento) ON DELETE CASCADE,
  id_empresa      bigint       NOT NULL REFERENCES empresa (id_empresa),
  id_funcionario  bigint       REFERENCES funcionario (id_funcionario),
  id_variacao     bigint       NOT NULL REFERENCES produto_barra (id_variacao),
  credito_debito  credito_debito NOT NULL,
  qtd_produto     numeric(14,3) NOT NULL,
  preco_custo     numeric(12,2) NOT NULL DEFAULT 0,
  preco_venda     numeric(12,2) NOT NULL DEFAULT 0,
  valor_desconto  numeric(12,2) NOT NULL DEFAULT 0,
  valor_acrescimo numeric(12,2) NOT NULL DEFAULT 0,
  produto_oferta  boolean      NOT NULL DEFAULT false,
  saldo_apos      numeric(14,3) NOT NULL,             -- P3: saldo resultante
  origem          text                                 -- 'venda manual' / 'canal ML' / ...
);
CREATE INDEX produto_movimento_detalhe_id_tenant_ix  ON produto_movimento_detalhe (id_tenant);
CREATE INDEX produto_movimento_detalhe_id_movimento_ix ON produto_movimento_detalhe (id_movimento);
CREATE INDEX produto_movimento_detalhe_variacao_ix   ON produto_movimento_detalhe (id_tenant, id_variacao);

-- Balanço/contagem de estoque.
CREATE TABLE produto_balanco (
  id_balanco    bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     bigint        NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa    bigint        NOT NULL REFERENCES empresa (id_empresa),
  id_variacao   bigint        NOT NULL REFERENCES produto_barra (id_variacao),
  data_balanco  timestamptz   NOT NULL DEFAULT now(),
  qtd_contagem  numeric(14,3) NOT NULL DEFAULT 0,
  qtd_sistema   numeric(14,3) NOT NULL DEFAULT 0,
  observacao    text
);
CREATE INDEX produto_balanco_id_tenant_ix ON produto_balanco (id_tenant);

COMMENT ON TABLE  produto_estoque IS 'Saldo materializado por variação × empresa (RLS). disponivel = qtd_estoque − reservado.';
COMMENT ON COLUMN produto_estoque.reservado IS 'Reserva anti-overselling; sobe no RECEBIDO do pedido (Q2/ADR-004).';
COMMENT ON TABLE  produto_movimento_detalhe IS 'Ledger imutável (P3): grava saldo_apos e origem de cada movimento.';
