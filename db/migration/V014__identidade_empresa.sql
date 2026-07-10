-- V014 — identidade.empresa (§3.3.2). Empresa/CNPJ do lojista.
-- tenant 1:N empresa (1:1 no v1, Q6). Toda tabela de domínio nasce com id_tenant (P8).

CREATE TABLE empresa (
  id_empresa         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          bigint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  razao_social       text        NOT NULL,
  nome_fantasia      text,
  cnpj               text,
  inscricao_estadual text,
  endereco           text,
  numero             text,
  complemento        text,
  bairro             text,
  cidade             text,
  estado             text,
  cep                text,
  telefone           text,
  email              text,
  imagem_relatorio   text,                       -- URL/chave de object storage (não binário no banco)
  ativo              boolean     NOT NULL DEFAULT true,
  criado_em          timestamptz NOT NULL DEFAULT now(),
  atualizado_em      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT empresa_cnpj_uk UNIQUE (id_tenant, cnpj)
);

CREATE INDEX empresa_id_tenant_ix ON empresa (id_tenant);

COMMENT ON TABLE empresa IS 'Empresa/CNPJ do lojista. tenant 1:N empresa (1:1 no v1, Q6). Sujeita a RLS (V024).';
