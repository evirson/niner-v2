-- V018 — pedidos/vendas: venda física (legado) (§3.3.5). R9: venda manual com baixa
-- de estoque. Financeiro (caixa/crediário) fica fora do v1 (Q5/ADR-010) — a venda NÃO
-- gera recebível/caixa aqui. Os itens da venda vêm do ledger (produto_movimento tipo VENDA).

CREATE TABLE venda (
  id_venda       bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant      bigint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa     bigint      NOT NULL REFERENCES empresa (id_empresa),
  id_cliente     bigint      REFERENCES cliente (id_cliente),
  id_funcionario bigint      REFERENCES funcionario (id_funcionario),   -- vendedor
  data_venda     timestamptz NOT NULL DEFAULT now(),
  tipo_operacao  tipo_operacao_venda NOT NULL DEFAULT 'VENDA',
  valor_total    numeric(12,2) NOT NULL DEFAULT 0,
  observacao     text,
  criado_em      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX venda_id_tenant_ix  ON venda (id_tenant);
CREATE INDEX venda_id_empresa_ix ON venda (id_tenant, id_empresa);
CREATE INDEX venda_data_ix       ON venda (id_tenant, data_venda);

CREATE TABLE venda_devolucao (
  id_devolucao      bigint    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant         bigint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa        bigint    NOT NULL REFERENCES empresa (id_empresa),
  data_devolucao    timestamptz NOT NULL DEFAULT now(),
  id_venda_credito  bigint    REFERENCES venda (id_venda),
  id_venda_debito   bigint    REFERENCES venda (id_venda),
  id_vale_mercadoria text,
  vale_usado        boolean   NOT NULL DEFAULT false,
  criado_em         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX venda_devolucao_id_tenant_ix ON venda_devolucao (id_tenant);

COMMENT ON TABLE venda IS 'Venda da loja física (R9). Sem financeiro no v1 (Q5). Itens no ledger de estoque (movimento VENDA).';
