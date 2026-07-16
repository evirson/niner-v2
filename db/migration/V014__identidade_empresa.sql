-- V014 — identidade.empresa (§3.3.2). Empresa/CNPJ do lojista.
-- tenant 1:N empresa (1:1 no v1, Q6). Toda tabela de domínio nasce com id_tenant (P8).

CREATE TABLE empresa (
  id_empresa         integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  codigo_empresa     smallint    NOT NULL,
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
  cfg_nome_etiqueta  text        NOT NULL,       -- texto/modelo impresso na etiqueta de produto
  ativo              boolean     NOT NULL DEFAULT true,
  criado_em          timestamptz NOT NULL DEFAULT now(),
  atualizado_em      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT empresa_cnpj_uk UNIQUE (id_tenant, cnpj),
  CONSTRAINT empresa_codigo_empresa_uk UNIQUE (id_tenant, codigo_empresa),
  -- base para FK composta (id_tenant, id_empresa) de outras tabelas (2026-07-16, P8):
  -- FK simples não valida que o id_empresa referenciado é do mesmo tenant (RLS não se aplica
  -- a checagem de integridade referencial). Toda tabela que referencia empresa passa a usar
  -- FOREIGN KEY (id_tenant, id_empresa) REFERENCES empresa (id_tenant, id_empresa).
  CONSTRAINT empresa_id_empresa_uk UNIQUE (id_tenant, id_empresa)
);

CREATE INDEX empresa_id_tenant_ix ON empresa (id_tenant);

COMMENT ON TABLE  empresa IS 'Empresa/CNPJ do lojista. tenant 1:N empresa (1:1 no v1, Q6). Sujeita a RLS (V024).';
COMMENT ON COLUMN empresa.codigo_empresa IS 'Numero sequencial da empresa dentro do tenant, so para exibicao em relatorios (01, 02, 03...). Unico por tenant.';
