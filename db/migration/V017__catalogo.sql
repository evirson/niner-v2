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

-- cfg_cor/cfg_tamanho substituem cfg_variante_linha/cfg_variante_coluna (2026-08-08): o sistema
-- deixou de suportar variação genérica de 2 dimensões (qualquer rótulo, ex.: "voltagem") e passou
-- a modelar especificamente cor + tamanho (grade), único par de dimensões que varia de verdade
-- SEM mudar o cadastro do produto (calçados/confecções) — casos como voltagem mudam o preço, logo
-- viram produtos distintos, não variação. Ver docs/telas/produto.md.
CREATE TABLE cfg_cor (
  id_cor        integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  descricao     text         NOT NULL,
  CONSTRAINT cfg_cor_uk UNIQUE (id_tenant, descricao),
  CONSTRAINT cfg_cor_id_uk UNIQUE (id_tenant, id_cor)
);
CREATE INDEX cfg_cor_id_tenant_ix ON cfg_cor (id_tenant);

CREATE TABLE cfg_tamanho (
  id_tamanho    integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  descricao     text         NOT NULL,
  CONSTRAINT cfg_tamanho_uk UNIQUE (id_tenant, descricao),
  CONSTRAINT cfg_tamanho_id_uk UNIQUE (id_tenant, id_tamanho)
);
CREATE INDEX cfg_tamanho_id_tenant_ix ON cfg_tamanho (id_tenant);

-- cfg_grade: uma "grade de numeração" nomeada (ex.: "Grade 36-44", "Grade PP-GG"), até 20 tamanhos
-- em ordem (folga deliberada sobre o uso real, que raramente passa de 15 — decisão do dono do
-- produto, 2026-08-08). NULL (não 0) representa slot vazio, mesma convenção do resto do projeto
-- (produto.codigo_ncm, produto_barra.ean) — evita precisar de uma linha "cfg_tamanho id=0" fake.
CREATE TABLE cfg_grade (
  id_grade      integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  descricao     text         NOT NULL,
  id_tamanho1   integer,
  id_tamanho2   integer,
  id_tamanho3   integer,
  id_tamanho4   integer,
  id_tamanho5   integer,
  id_tamanho6   integer,
  id_tamanho7   integer,
  id_tamanho8   integer,
  id_tamanho9   integer,
  id_tamanho10  integer,
  id_tamanho11  integer,
  id_tamanho12  integer,
  id_tamanho13  integer,
  id_tamanho14  integer,
  id_tamanho15  integer,
  id_tamanho16  integer,
  id_tamanho17  integer,
  id_tamanho18  integer,
  id_tamanho19  integer,
  id_tamanho20  integer,
  CONSTRAINT cfg_grade_uk UNIQUE (id_tenant, descricao),
  CONSTRAINT cfg_grade_id_uk UNIQUE (id_tenant, id_grade),
  CONSTRAINT cfg_grade_tamanho1_fk  FOREIGN KEY (id_tenant, id_tamanho1)  REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho2_fk  FOREIGN KEY (id_tenant, id_tamanho2)  REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho3_fk  FOREIGN KEY (id_tenant, id_tamanho3)  REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho4_fk  FOREIGN KEY (id_tenant, id_tamanho4)  REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho5_fk  FOREIGN KEY (id_tenant, id_tamanho5)  REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho6_fk  FOREIGN KEY (id_tenant, id_tamanho6)  REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho7_fk  FOREIGN KEY (id_tenant, id_tamanho7)  REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho8_fk  FOREIGN KEY (id_tenant, id_tamanho8)  REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho9_fk  FOREIGN KEY (id_tenant, id_tamanho9)  REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho10_fk FOREIGN KEY (id_tenant, id_tamanho10) REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho11_fk FOREIGN KEY (id_tenant, id_tamanho11) REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho12_fk FOREIGN KEY (id_tenant, id_tamanho12) REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho13_fk FOREIGN KEY (id_tenant, id_tamanho13) REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho14_fk FOREIGN KEY (id_tenant, id_tamanho14) REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho15_fk FOREIGN KEY (id_tenant, id_tamanho15) REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho16_fk FOREIGN KEY (id_tenant, id_tamanho16) REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho17_fk FOREIGN KEY (id_tenant, id_tamanho17) REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho18_fk FOREIGN KEY (id_tenant, id_tamanho18) REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho19_fk FOREIGN KEY (id_tenant, id_tamanho19) REFERENCES cfg_tamanho (id_tenant, id_tamanho),
  CONSTRAINT cfg_grade_tamanho20_fk FOREIGN KEY (id_tenant, id_tamanho20) REFERENCES cfg_tamanho (id_tenant, id_tamanho)
);
CREATE INDEX cfg_grade_id_tenant_ix ON cfg_grade (id_tenant);

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

-- cfg_ean_gerador — controle do gerador de código de barras interno (EAN-13) usado em
-- produto_barra.sku. GLOBAL (sem id_tenant/RLS, P9, mesma exceção de cfg_produto_ncm): "banco"
-- aqui é a INSTÂNCIA de banco de dados (hoje só niner_db, id_banco=1), não o tenant — o
-- sequencial é um contador único compartilhado por todos os tenants deste banco. Se um dia a
-- Vetor fizer sharding (uma segunda instância de banco para outro grupo de tenants), essa
-- segunda instância nasce com id_banco=2, evitando colisão de código de barras entre bancos.
-- Singleton de verdade: uma linha só, sem PK própria (não é referenciada por FK nenhuma).
CREATE TABLE cfg_ean_gerador (
  id_banco           smallint NOT NULL,
  proximo_sequencial bigint   NOT NULL DEFAULT 1
);
COMMENT ON TABLE cfg_ean_gerador IS 'Controle do gerador de EAN-13 interno (produto_barra.sku), GLOBAL — 1 linha por instância de banco, sem RLS.';
-- niner_owner é o dono (a função roda como SECURITY DEFINER); niner_app nunca acessa a
-- tabela direto, só via gerar_ean13_interno() — evita escrita indevida fora da rotina.
INSERT INTO cfg_ean_gerador (id_banco, proximo_sequencial) VALUES (1, 1);

-- Gera o próximo código de barras interno: 9 (fixo) + id_banco (3) + sequencial (8) + DV (1),
-- estrutura FIIISSSSSSSSD — dígito verificador padrão EAN-13/GTIN (peso 1/3 alternado). Roda
-- como SECURITY DEFINER (dono niner_owner) porque niner_app não tem grant nenhum na tabela;
-- o UPDATE...RETURNING é atômico (Postgres serializa a linha), sem precisar de lock explícito.
CREATE OR REPLACE FUNCTION gerar_ean13_interno() RETURNS text
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  v_banco smallint;
  v_seq   bigint;
  v_corpo text;
  v_soma  int := 0;
  v_dv    int;
  i       int;
BEGIN
  UPDATE cfg_ean_gerador
     SET proximo_sequencial = proximo_sequencial + 1
   RETURNING id_banco, proximo_sequencial - 1
    INTO v_banco, v_seq;

  IF v_seq > 99999999 THEN
    RAISE EXCEPTION 'Sequencial de EAN-13 esgotado (máximo 99.999.999) — banco %.', v_banco;
  END IF;

  v_corpo := '9' || lpad(v_banco::text, 3, '0') || lpad(v_seq::text, 8, '0');

  FOR i IN 1..12 LOOP
    v_soma := v_soma + substr(v_corpo, i, 1)::int * (CASE WHEN i % 2 = 0 THEN 3 ELSE 1 END);
  END LOOP;
  v_dv := (10 - (v_soma % 10)) % 10;

  RETURN v_corpo || v_dv::text;
END;
$$;
COMMENT ON FUNCTION gerar_ean13_interno() IS 'Gera o próximo código de barras interno (EAN-13, FIIISSSSSSSSD) para produto_barra.sku.';
GRANT EXECUTE ON FUNCTION gerar_ean13_interno() TO niner_app;

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
  id_grade             integer,      -- grade de tamanhos deste produto; obrigatório (checado em
                                      -- serviço) quando cfg_geral.cfg_usa_cor_grade = true (2026-08-08)
  criado_em            timestamptz   NOT NULL DEFAULT now(),
  atualizado_em        timestamptz   NOT NULL DEFAULT now(),
  reajustado_em        timestamptz,
  -- base para FK composta (2026-07-16, P8) — ver comentário em empresa_id_empresa_uk (V014).
  CONSTRAINT produto_id_produto_uk UNIQUE (id_tenant, id_produto),
  CONSTRAINT produto_grade_fk FOREIGN KEY (id_tenant, id_grade) REFERENCES cfg_grade (id_tenant, id_grade)
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

-- produto_barra = VARIAÇÃO (1 por produto × cor × tamanho, ou 1 única sem cor/tamanho quando o
-- produto não usa grade). Q7: sku + ean. id_cor/id_tamanho substituem id_variante_linha/coluna
-- (2026-08-08) — quando o produto usa grade (produto.id_grade preenchido), os dois são
-- obrigatórios juntos (checado em serviço, ProdutoBarraService); quando não usa, ficam NULL.
CREATE TABLE produto_barra (
  id_variacao        integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_produto         integer     NOT NULL,
  id_cor             integer,
  id_tamanho         integer,
  sku                text        NOT NULL,            -- código de barras interno, SEMPRE gerado por gerar_ean13_interno() — nunca digitado
  ean                text,                            -- GTIN real (EAN-13/UPC), NULLABLE
  criado_em          timestamptz NOT NULL DEFAULT now(),
  atualizado_em      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT produto_barra_sku_uk UNIQUE (id_tenant, sku),
  CONSTRAINT produto_barra_variacao_uk UNIQUE (id_produto, id_cor, id_tamanho),
  -- base para FK composta (2026-07-16, P8) de estoque/canais/pedidos que referenciam a variação.
  CONSTRAINT produto_barra_id_variacao_uk UNIQUE (id_tenant, id_variacao),
  -- FKs compostas — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT produto_barra_produto_fk FOREIGN KEY (id_tenant, id_produto)
    REFERENCES produto (id_tenant, id_produto),
  CONSTRAINT produto_barra_cor_fk FOREIGN KEY (id_tenant, id_cor)
    REFERENCES cfg_cor (id_tenant, id_cor),
  CONSTRAINT produto_barra_tamanho_fk FOREIGN KEY (id_tenant, id_tamanho)
    REFERENCES cfg_tamanho (id_tenant, id_tamanho)
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
COMMENT ON COLUMN produto_barra.sku IS 'Código de barras interno (EAN-13), chave de negócio da variação (ex-codigo_barra); SEMPRE gerado por gerar_ean13_interno(), nunca digitado pelo usuário.';
COMMENT ON COLUMN produto_barra.ean IS 'GTIN real; NULL permitido; exigido só na publicação em canal que pede GTIN.';
COMMENT ON TABLE  produto_imagem IS 'Galeria de imagens do produto (RLS). indice único por produto — controla a ordem de exibição.';
