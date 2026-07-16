-- V017 — catalogo (§3.3.3): configs, produto, variações e SKU.
-- Q7 (fechada): a variação (produto_barra) tem `sku` interno (obrigatório, único por
-- tenant) + `ean` opcional (GTIN real, único quando preenchido). FKs internas usam o
-- surrogate `id_variacao`; `sku` é o identificador de negócio. Dinheiro em NUMERIC (P7).

CREATE TABLE cfg_categoria_produto (
  id_categoria   integer    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant      smallint   NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome_categoria text       NOT NULL,
  CONSTRAINT cfg_categoria_produto_uk UNIQUE (id_tenant, nome_categoria)
);
CREATE INDEX cfg_categoria_produto_id_tenant_ix ON cfg_categoria_produto (id_tenant);

CREATE TABLE cfg_variante_linha (
  id_variante_linha integer  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant         smallint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  descricao         text     NOT NULL,                 -- ex.: cor
  CONSTRAINT cfg_variante_linha_uk UNIQUE (id_tenant, descricao)
);
CREATE INDEX cfg_variante_linha_id_tenant_ix ON cfg_variante_linha (id_tenant);

CREATE TABLE cfg_variante_coluna (
  id_variante_coluna integer  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  descricao          text     NOT NULL,                 -- ex.: tamanho / voltagem
  CONSTRAINT cfg_variante_coluna_uk UNIQUE (id_tenant, descricao)
);
CREATE INDEX cfg_variante_coluna_id_tenant_ix ON cfg_variante_coluna (id_tenant);

CREATE TABLE produto (
  id_produto           integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant            smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  ativo                boolean       NOT NULL DEFAULT true,     -- legado 'S'/'N' -> boolean
  marca                text,
  referencia           text,
  descricao            text          NOT NULL,
  preco_custo          numeric(12,2) NOT NULL DEFAULT 0,      -- legado FLOAT -> NUMERIC (P7)
  percentual_venda     numeric(5,2)  NOT NULL DEFAULT 0,
  preco_venda          numeric(12,2) NOT NULL DEFAULT 0,
  data_inicio_oferta   timestamptz,
  data_final_oferta    timestamptz,
  preco_oferta         numeric(12,2),
  codigo_ncm           text,
  peso_bruto           numeric(14,3) NOT NULL DEFAULT 0,
  peso_liquido         numeric(14,3) NOT NULL DEFAULT 0,
  nome_variante_linha  text,
  nome_variante_coluna text,
  imagem               text,                          -- URL/chave de object storage
  criado_em            timestamptz NOT NULL DEFAULT now(),
  atualizado_em        timestamptz NOT NULL DEFAULT now(),
  reajustado_em        timestamptz
);
CREATE INDEX produto_id_tenant_ix  ON produto (id_tenant);
CREATE INDEX produto_descricao_ix  ON produto (id_tenant, descricao);
CREATE INDEX produto_marca_ix      ON produto (id_tenant, marca);
CREATE INDEX produto_referencia_ix ON produto (id_tenant, referencia);

CREATE TABLE produto_categoria (
  id_produto   integer  NOT NULL REFERENCES produto (id_produto),
  id_categoria integer  NOT NULL REFERENCES cfg_categoria_produto (id_categoria),
  id_tenant    smallint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  CONSTRAINT produto_categoria_pk PRIMARY KEY (id_produto, id_categoria)
);
CREATE INDEX produto_categoria_id_tenant_ix ON produto_categoria (id_tenant);

-- produto_barra = VARIAÇÃO (1 por produto × linha × coluna). Q7: sku + ean.
CREATE TABLE produto_barra (
  id_variacao        integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_produto         integer     NOT NULL REFERENCES produto (id_produto),
  id_variante_linha  integer     REFERENCES cfg_variante_linha (id_variante_linha),
  id_variante_coluna integer     REFERENCES cfg_variante_coluna (id_variante_coluna),
  ean                text        NOT NULL,       
  ean_fabricante     plataforma.sim_nao NOT NULL DEFAULT 'SIML',
  criado_em          timestamptz NOT NULL DEFAULT now(),
  atualizado_em      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT produto_barra_sku_uk UNIQUE (id_tenant, ean),
  CONSTRAINT produto_barra_variacao_uk UNIQUE (id_produto, id_variante_linha, id_variante_coluna)
);
-- EAN único por tenant SÓ quando preenchido (produto sem EAN é permitido — Q7).
CREATE UNIQUE INDEX produto_barra_ean_uk ON produto_barra (id_tenant, ean) WHERE ean IS NOT NULL;
CREATE INDEX produto_barra_id_tenant_ix ON produto_barra (id_tenant);
CREATE INDEX produto_barra_id_produto_ix ON produto_barra (id_produto);

COMMENT ON TABLE  produto        IS 'Produto do catálogo (RLS). Compartilhado entre empresas do tenant (sem id_empresa).';
COMMENT ON TABLE  produto_barra  IS 'Variação = SKU. Q7: sku interno (único/tenant) + ean opcional (GTIN, único quando preenchido).';
