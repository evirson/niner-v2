-- V017 — catalogo (§3.3.3): configs, produto, variações e SKU.
-- Q7 (fechada): a variação (produto_barra) tem `sku` interno (obrigatório, único por
-- tenant) + `ean` opcional (GTIN real, único quando preenchido). FKs internas usam o
-- surrogate `id_variacao`; `sku` é o identificador de negócio. Dinheiro em NUMERIC (P7).

CREATE TABLE cfg_categoria_produto (
  id_categoria  integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome_categoria text       NOT NULL,
  CONSTRAINT cfg_categoria_produto_uk UNIQUE (id_tenant, nome_categoria),
  -- base para FK composta (2026-07-16, P8) — ver comentário em empresa_id_empresa_uk (V014).
  CONSTRAINT cfg_categoria_produto_id_uk UNIQUE (id_tenant, id_categoria)
);
CREATE INDEX cfg_categoria_produto_id_tenant_ix ON cfg_categoria_produto (id_tenant);

CREATE TABLE cfg_variante_linha (
  id_variante_linha integer  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  descricao     text         NOT NULL,                 -- ex.: cor
  CONSTRAINT cfg_variante_linha_uk UNIQUE (id_tenant, descricao),
  CONSTRAINT cfg_variante_linha_id_uk UNIQUE (id_tenant, id_variante_linha)
);
CREATE INDEX cfg_variante_linha_id_tenant_ix ON cfg_variante_linha (id_tenant);

CREATE TABLE cfg_variante_coluna (
  id_variante_coluna integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  descricao     text         NOT NULL,                 -- ex.: tamanho / voltagem
  CONSTRAINT cfg_variante_coluna_uk UNIQUE (id_tenant, descricao),
  CONSTRAINT cfg_variante_coluna_id_uk UNIQUE (id_tenant, id_variante_coluna)
);
CREATE INDEX cfg_variante_coluna_id_tenant_ix ON cfg_variante_coluna (id_tenant);

-- cfg_produto_ncm — referência de NCM, GLOBAL (sem id_tenant/RLS, P9): o mesmo código NCM
-- vale para qualquer tenant, mantida por script (carga/atualização da tabela oficial da
-- Receita Federal) — sem tela de manutenção/CRUD via API.
CREATE TABLE cfg_produto_ncm (
  codigo_ncm    text          PRIMARY KEY,
  descricao_ncm text          NOT NULL,
  aliquota_ibpt numeric(10,2)
);
COMMENT ON TABLE cfg_produto_ncm IS 'Referência de NCM (código + descrição + alíquota IBPT), GLOBAL — igual para todos os tenants, sem RLS. Mantida por script, sem tela de manutenção.';
-- niner_app só lê (mantida por script/carga, não pela aplicação) — sem entrar no laço de
-- RLS/grants de V024 porque não tem id_tenant (guarda-corpo de P8 não se aplica a ela).
GRANT SELECT ON cfg_produto_ncm TO niner_app;
-- niner_owner é quem roda o script de carga/atualização (fora do tráfego da aplicação).
GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_produto_ncm TO niner_owner;

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
  codigo_ncm           text          REFERENCES cfg_produto_ncm (codigo_ncm),
  peso_bruto           numeric(14,3) NOT NULL DEFAULT 0,
  peso_liquido         numeric(14,3) NOT NULL DEFAULT 0,
  nome_variante_linha  text,
  nome_variante_coluna text,
  criado_em            timestamptz   NOT NULL DEFAULT now(),
  atualizado_em        timestamptz   NOT NULL DEFAULT now(),
  reajustado_em        timestamptz,
  -- base para FK composta (2026-07-16, P8) — ver comentário em empresa_id_empresa_uk (V014).
  CONSTRAINT produto_id_produto_uk UNIQUE (id_tenant, id_produto)
);
CREATE INDEX produto_id_tenant_ix  ON produto (id_tenant);
CREATE INDEX produto_descricao_ix  ON produto (id_tenant, descricao);
CREATE INDEX produto_marca_ix      ON produto (id_tenant, marca);
CREATE INDEX produto_referencia_ix ON produto (id_tenant, referencia);

CREATE TABLE produto_categoria (
  id_produto   integer  NOT NULL,
  id_categoria integer  NOT NULL,
  id_tenant    smallint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  indice       smallint NOT NULL DEFAULT 0,  -- ordenação da categoria dentro do produto.
  CONSTRAINT produto_categoria_pk PRIMARY KEY (id_produto, id_categoria),
  -- FKs compostas (2026-07-16, P8) — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT produto_categoria_produto_fk FOREIGN KEY (id_tenant, id_produto)
    REFERENCES produto (id_tenant, id_produto),
  CONSTRAINT produto_categoria_categoria_fk FOREIGN KEY (id_tenant, id_categoria)
    REFERENCES cfg_categoria_produto (id_tenant, id_categoria),
  CONSTRAINT produto_categoria_indice_uk UNIQUE (id_tenant, id_produto, indice)
);
CREATE INDEX produto_categoria_id_tenant_ix ON produto_categoria (id_tenant);

-- produto_barra = VARIAÇÃO (1 por produto × linha × coluna). Q7: sku + ean.
CREATE TABLE produto_barra (
  id_variacao        integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_produto         integer     NOT NULL,
  id_variante_linha  integer,
  id_variante_coluna integer,
  sku                text        NOT NULL,            -- identificador INTERNO, imprimível como código de barras
  ean                text,                            -- GTIN real (EAN-13/UPC), NULLABLE
  criado_em          timestamptz NOT NULL DEFAULT now(),
  atualizado_em      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT produto_barra_sku_uk UNIQUE (id_tenant, sku),
  CONSTRAINT produto_barra_variacao_uk UNIQUE (id_produto, id_variante_linha, id_variante_coluna),
  -- base para FK composta (2026-07-16, P8) de estoque/canais/pedidos que referenciam a variação.
  CONSTRAINT produto_barra_id_variacao_uk UNIQUE (id_tenant, id_variacao),
  -- FKs compostas — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT produto_barra_produto_fk FOREIGN KEY (id_tenant, id_produto)
    REFERENCES produto (id_tenant, id_produto),
  CONSTRAINT produto_barra_variante_linha_fk FOREIGN KEY (id_tenant, id_variante_linha)
    REFERENCES cfg_variante_linha (id_tenant, id_variante_linha),
  CONSTRAINT produto_barra_variante_coluna_fk FOREIGN KEY (id_tenant, id_variante_coluna)
    REFERENCES cfg_variante_coluna (id_tenant, id_variante_coluna)
);
-- EAN único por tenant SÓ quando preenchido (produto sem EAN é permitido — Q7).
CREATE UNIQUE INDEX produto_barra_ean_uk ON produto_barra (id_tenant, ean) WHERE ean IS NOT NULL;
CREATE INDEX produto_barra_id_tenant_ix ON produto_barra (id_tenant);
CREATE INDEX produto_barra_id_produto_ix ON produto_barra (id_produto);

-- galeria de imagens do produto (várias por produto; indice controla a ordem de exibição).
CREATE TABLE produto_imagem (
  id_produto_imagem integer  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_produto         integer  NOT NULL,
  indice             smallint NOT NULL,
  imagem             text     NOT NULL,      -- URL/chave de object storage
  CONSTRAINT produto_imagem_uk UNIQUE (id_tenant, id_produto, indice),
  -- FK composta (2026-07-16, P8) — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT produto_imagem_produto_fk FOREIGN KEY (id_tenant, id_produto)
    REFERENCES produto (id_tenant, id_produto)
);
CREATE INDEX produto_imagem_id_tenant_ix  ON produto_imagem (id_tenant);
CREATE INDEX produto_imagem_id_produto_ix ON produto_imagem (id_tenant, id_produto);

COMMENT ON TABLE  produto        IS 'Produto do catálogo (RLS). Compartilhado entre empresas do tenant (sem id_empresa).';
COMMENT ON COLUMN produto_categoria.indice IS 'Ordenação da categoria dentro do produto (menor primeiro); único por produto.';
COMMENT ON TABLE  produto_barra  IS 'Variação = SKU. Q7: sku interno (único/tenant) + ean opcional (GTIN, único quando preenchido).';
COMMENT ON COLUMN produto_barra.sku IS 'Identificador interno obrigatório; chave de negócio da variação (ex-codigo_barra).';
COMMENT ON COLUMN produto_barra.ean IS 'GTIN real; NULL permitido; exigido só na publicação em canal que pede GTIN.';
COMMENT ON TABLE  produto_imagem IS 'Galeria de imagens do produto (RLS). indice único por produto — controla a ordem de exibição.';
