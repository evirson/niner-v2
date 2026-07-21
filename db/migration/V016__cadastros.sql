-- V016 — Cadastros básicos referenciados pelo estoque/vendas: cliente, fornecedor,
-- funcionario (§3.3.8 do legado). Todos com id_tenant (P8). Dinheiro em NUMERIC (P7).

-- categoria de cliente (§3.3.8 do legado). Cria antes de `cliente` por causa da FK.
CREATE TABLE cfg_categoria_cliente (
  id_categoria_cliente  integer  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant             smallint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome_categoria        text     NOT NULL,
  CONSTRAINT cfg_categoria_cliente_uk UNIQUE (id_tenant, nome_categoria),
  -- base para FK composta (2026-07-16, P8) — ver comentário em empresa_id_empresa_uk (V014).
  CONSTRAINT cfg_categoria_cliente_id_uk UNIQUE (id_tenant, id_categoria_cliente)
);
CREATE INDEX cfg_categoria_cliente_id_tenant_ix ON cfg_categoria_cliente (id_tenant);

CREATE TABLE cliente (
  id_cliente           integer    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant            smallint   NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_categoria_cliente integer    NOT NULL,
  nome                 text       NOT NULL,
  fisica_juridica      boolean    NOT NULL DEFAULT true,     -- true = pessoa física
  cpf_cnpj             text,
  rg_ie                text,
  data_nascimento      date,                                 -- obrigatório p/ pessoa física, ver CHECK
  genero               genero_cliente,                       -- obrigatório p/ pessoa física, ver CHECK
  email                text,
  telefone             text,
  whatsapp             text,
  instagram            text,
  facebook             text,
  tiktok               text,
  endereco             text,
  numero               text,
  complemento          text,                                 -- apto/bloco/sala etc. (2026-07-20)
  bairro               text,
  cidade               text,
  estado               text,
  cep                  text,
  limite_credito       numeric(12,2) NOT NULL DEFAULT 0,     -- crediário é Fase 2; campo fica pronto
  ativo                boolean       NOT NULL DEFAULT true,
  criado_em            timestamptz   NOT NULL DEFAULT now(),
  atualizado_em        timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT cliente_documento_uk UNIQUE (id_tenant, cpf_cnpj),
  -- genero obrigatório só para pessoa física; PJ não tem gênero. data_nascimento é sempre
  -- opcional (2026-07-21) — quando preenchida, a validade (não pode ser hoje/futuro) fica
  -- por conta da aplicação, não do banco.
  CONSTRAINT cliente_dados_pessoais_ck CHECK (
    NOT fisica_juridica OR genero IS NOT NULL
  ),
  -- FK composta (2026-07-16, P8) — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT cliente_categoria_fk FOREIGN KEY (id_tenant, id_categoria_cliente)
    REFERENCES cfg_categoria_cliente (id_tenant, id_categoria_cliente),
  -- base para FK composta de venda.id_cliente (V018).
  CONSTRAINT cliente_id_cliente_uk UNIQUE (id_tenant, id_cliente)
);
CREATE INDEX cliente_id_tenant_ix  ON cliente (id_tenant);
CREATE INDEX cliente_nome_ix       ON cliente (id_tenant, nome);
CREATE INDEX cliente_categoria_ix  ON cliente (id_tenant, id_categoria_cliente);

-- plano de contas (§3.3.7 do legado — preparação p/ relatórios/DRE futuros, fora do
-- financeiro completo que segue fora do v1, Q5/ADR-010). Chave de negócio (id_plano_contas)
-- é o código contábil (ex.: "3.1.001"), único POR TENANT — não é PK global.
CREATE TABLE cfg_plano_contas (
  id_tenant          smallint              NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_plano_contas    text                  NOT NULL,
  descricao          text                  NOT NULL,
  tipo_movimento     tipo_movimento_conta  NOT NULL,   -- CRÉDITO/DÉBITO/NEUTRO
  inclui_dre         boolean               NOT NULL,
  inclui_fluxo_caixa boolean               NOT NULL,
  criado_em          timestamptz           NOT NULL DEFAULT now(),  -- 2026-07-21 (auditoria, convenção do domínio)
  atualizado_em      timestamptz           NOT NULL DEFAULT now(),
  CONSTRAINT cfg_plano_contas_pk PRIMARY KEY (id_tenant, id_plano_contas)
);
CREATE INDEX cfg_plano_contas_id_tenant_ix ON cfg_plano_contas (id_tenant);
CREATE INDEX cfg_plano_contas_descricao_ix ON cfg_plano_contas (id_tenant, descricao);

CREATE TABLE fornecedor (
  id_fornecedor      integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_plano_contas    text        NOT NULL,
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
  CONSTRAINT fornecedor_cnpj_uk UNIQUE (id_tenant, cnpj),
  -- base para FK composta (2026-07-16, P8) — ver comentário em empresa_id_empresa_uk (V014).
  CONSTRAINT fornecedor_id_fornecedor_uk UNIQUE (id_tenant, id_fornecedor),
  -- FK composta (2026-07-16, P8) — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT fornecedor_plano_contas_fk FOREIGN KEY (id_tenant, id_plano_contas)
    REFERENCES cfg_plano_contas (id_tenant, id_plano_contas)
);
CREATE INDEX fornecedor_id_tenant_ix ON fornecedor (id_tenant);

CREATE TABLE funcionario (
  id_funcionario integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant      smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa     integer,
  nome           text         NOT NULL,
  cpf            text,
  telefone       text,
  cargo          text,
  perc_comissao  numeric(5,2) NOT NULL DEFAULT 0,
  ativo          boolean      NOT NULL DEFAULT true,
  criado_em      timestamptz  NOT NULL DEFAULT now(),
  atualizado_em  timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT funcionario_cpf_uk UNIQUE (id_tenant, id_funcionario),
  -- FK composta (2026-07-16, P8) — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT funcionario_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa)
);
CREATE INDEX funcionario_id_tenant_ix ON funcionario (id_tenant);

COMMENT ON TABLE cfg_categoria_cliente IS 'Categoria de cliente (RLS). Referenciada por cliente.id_categoria_cliente (NOT NULL).';
COMMENT ON TABLE cliente               IS 'Cliente do lojista (RLS). limite_credito preparado para crediário (Fase 2).';
COMMENT ON TABLE cfg_plano_contas      IS 'Plano de contas (RLS). Preparação p/ relatórios/DRE (financeiro completo é Fase 2, Q5/ADR-010). Referenciada por fornecedor.id_plano_contas (NOT NULL).';
COMMENT ON TABLE fornecedor            IS 'Fornecedor do lojista (RLS).';
COMMENT ON TABLE funcionario           IS 'Funcionário do lojista (RLS). Referenciado no ledger de estoque/venda.';
