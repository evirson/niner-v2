-- V016 — Cadastros básicos referenciados pelo estoque/vendas: cliente, fornecedor,
-- funcionario (§3.3.8 do legado). Todos com id_tenant (P8). Dinheiro em NUMERIC (P7).

CREATE TABLE cliente (
  id_cliente      integer    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant       smallint   NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome            text       NOT NULL,
  fisica_juridica boolean    NOT NULL DEFAULT true,     -- true = pessoa física
  cpf_cnpj        text,
  rg_ie           text,
  email           text,
  telefone        text,
  endereco        text, 
  numero          text, 
  bairro          text, 
  cidade          text,
  estado          text, 
  cep             text,
  limite_credito  numeric(12,2) NOT NULL DEFAULT 0,     -- crediário é Fase 2; campo fica pronto
  ativo           boolean       NOT NULL DEFAULT true,
  criado_em       timestamptz   NOT NULL DEFAULT now(),
  atualizado_em   timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT cliente_documento_uk UNIQUE (id_tenant, cpf_cnpj)
);
CREATE INDEX cliente_id_tenant_ix ON cliente (id_tenant);
CREATE INDEX cliente_nome_ix      ON cliente (id_tenant, nome);

CREATE TABLE fornecedor (
  id_fornecedor      integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  razao_social       text        NOT NULL,
  nome_fantasia      text,
  cnpj               text,
  inscricao_estadual text,
  email              text,
  telefone           text,
  endereco           text, 
  numero             text,
  bairro             text,
  cidade             text,
  estado             text,
  cep                text,
  ativo              boolean     NOT NULL DEFAULT true,
  criado_em          timestamptz NOT NULL DEFAULT now(),
  atualizado_em      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fornecedor_cnpj_uk UNIQUE (id_tenant, cnpj)
);
CREATE INDEX fornecedor_id_tenant_ix ON fornecedor (id_tenant);

CREATE TABLE funcionario (
  id_funcionario integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant      smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa     integer      REFERENCES empresa (id_empresa),
  nome           text         NOT NULL,
  cpf            text,
  cargo          text,
  perc_comissao  numeric(5,2) NOT NULL DEFAULT 0,
  ativo          boolean      NOT NULL DEFAULT true,
  criado_em      timestamptz  NOT NULL DEFAULT now(),
  atualizado_em  timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT funcionario_cpf_uk UNIQUE (id_tenant, cpf)
);
CREATE INDEX funcionario_id_tenant_ix ON funcionario (id_tenant);

COMMENT ON TABLE cliente     IS 'Cliente do lojista (RLS). limite_credito preparado para crediário (Fase 2).';
COMMENT ON TABLE fornecedor  IS 'Fornecedor do lojista (RLS).';
COMMENT ON TABLE funcionario IS 'Funcionário do lojista (RLS). Referenciado no ledger de estoque/venda.';
