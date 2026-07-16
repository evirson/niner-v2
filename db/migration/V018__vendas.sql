-- V018 — pedidos/vendas: venda física (legado) (§3.3.5). R9: venda manual com baixa
-- de estoque. Financeiro (caixa/crediário) fica fora do v1 (Q5/ADR-010) — a venda NÃO
-- gera recebível/caixa aqui. Os itens da venda vêm do ledger (produto_movimento tipo VENDA).

CREATE TABLE venda (
  id_venda       integer             GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant      smallint            NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa     integer             NOT NULL,
  id_cliente     integer,
  data_venda     timestamptz         NOT NULL DEFAULT now(),
  tipo_operacao  tipo_operacao_venda NOT NULL DEFAULT 'VENDA',
  -- base para FK composta (2026-07-16, P8) de venda_devolucao/produto_movimento_mestre.
  CONSTRAINT venda_id_venda_uk UNIQUE (id_tenant, id_venda),
  -- FKs compostas — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT venda_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT venda_cliente_fk FOREIGN KEY (id_tenant, id_cliente)
    REFERENCES cliente (id_tenant, id_cliente)
);
CREATE INDEX venda_id_tenant_ix  ON venda (id_tenant);
CREATE INDEX venda_id_empresa_ix ON venda (id_tenant, id_empresa);
CREATE INDEX venda_data_ix       ON venda (id_tenant, data_venda);

CREATE TABLE venda_devolucao (
  id_devolucao       integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa         integer     NOT NULL,
  data_devolucao     timestamptz NOT NULL DEFAULT now(),
  id_venda_credito   integer,
  id_venda_debito    integer,
  id_vale_mercadoria text,
  vale_usado         boolean     NOT NULL DEFAULT false,
  -- base para FK composta (2026-07-16, P8) de produto_movimento_mestre.id_devolucao.
  CONSTRAINT venda_devolucao_id_uk UNIQUE (id_tenant, id_devolucao),
  -- FKs compostas — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT venda_devolucao_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT venda_devolucao_credito_fk FOREIGN KEY (id_tenant, id_venda_credito)
    REFERENCES venda (id_tenant, id_venda),
  CONSTRAINT venda_devolucao_debito_fk FOREIGN KEY (id_tenant, id_venda_debito)
    REFERENCES venda (id_tenant, id_venda)
);
CREATE INDEX venda_devolucao_id_tenant_ix ON venda_devolucao (id_tenant);

COMMENT ON TABLE venda IS 'Venda da loja física (R9). Sem financeiro no v1 (Q5). Itens no ledger de estoque (movimento VENDA). Funcionário/comissão por item ficam em produto_movimento_detalhe.id_funcionario, não aqui. Sem valor_total/observacao/criado_em (2026-07-16) — total é derivado do ledger, sem timestamp de auditoria nesta tabela.';
