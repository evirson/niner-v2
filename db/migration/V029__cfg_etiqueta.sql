-- V029 — configuração de etiqueta de código de barras de produtos (2026-08-04). Tabela nova,
-- não mexe nas migrations já aplicadas (banco em construção). Nasce depois do guarda-corpo de
-- V024, então tem RLS próprio no arquivo (mesmo padrão de V025/V026/V028).
--
-- Modelo em 3 tabelas por configuração nomeada (um tenant pode ter várias — impressoras/rolos
-- diferentes por loja/situação, escolhidas na tela de Emissão):
--   cfg_etiqueta_config — cabeçalho: dimensões do rolo, da etiqueta e das bordas.
--   cfg_etiqueta_coluna — filha: posição inicial de cada coluna de etiqueta no rolo físico
--     (1 a numero_colunas do cabeçalho; não é calculada por fórmula porque rolos com espaçamento
--     irregular entre colunas existem na prática).
--   cfg_etiqueta_campo  — filha: quais dos 10 campos possíveis aparecem em cada configuração,
--     com posição livre (x/y, em mm, relativa ao canto superior-esquerdo da própria etiqueta —
--     não do rolo) e estilo (fonte, tamanho, negrito, fundo preto/letra branca ou o padrão letra
--     preta/fundo branco, alinhamento). Modelo validado contra um PDF real de etiqueta impressa
--     fornecido pelo dono do produto (ver docs/telas/configuracao-etiqueta.md).

CREATE TYPE campo_etiqueta AS ENUM (
  'NOME_EMPRESA', 'DESCRICAO_PRODUTO', 'MARCA', 'REFERENCIA',
  'PRECO_VENDA', 'PRECO_OFERTA', 'SKU_BARRAS', 'EAN_BARRAS',
  'VARIANTE_LINHA', 'VARIANTE_COLUNA'
);
COMMENT ON TYPE campo_etiqueta IS 'Campos possíveis numa etiqueta de produto — cada valor mapeia pra uma coluna real (ver docs/telas/configuracao-etiqueta.md): NOME_EMPRESA->empresa.cfg_nome_etiqueta, DESCRICAO_PRODUTO->produto.descricao, MARCA->produto.marca, REFERENCIA->produto.referencia, PRECO_VENDA->produto.preco_venda, PRECO_OFERTA->produto.preco_oferta, SKU_BARRAS->produto_barra.sku, EAN_BARRAS->produto_barra.ean, VARIANTE_LINHA->cfg_variante_linha.descricao, VARIANTE_COLUNA->cfg_variante_coluna.descricao.';

CREATE TYPE alinhamento_etiqueta_campo AS ENUM ('ESQUERDA', 'CENTRO', 'DIREITA');

CREATE TYPE fonte_etiqueta AS ENUM ('ARIAL', 'COURIER', 'TIMES_NEW_ROMAN');
COMMENT ON TYPE fonte_etiqueta IS 'Conjunto provisório — depende da tecnologia de impressão (impressora térmica dedicada ZPL/EPL vs. impressão via navegador) ainda não decidida; fácil de estender via ALTER TYPE ADD VALUE (banco em construção).';

CREATE TABLE cfg_etiqueta_config (
  id_config_etiqueta  integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant           smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome                text          NOT NULL,
  largura_rolo_mm     numeric(6,2)  NOT NULL,
  numero_colunas      smallint      NOT NULL,
  largura_etiqueta_mm numeric(6,2)  NOT NULL,
  altura_etiqueta_mm  numeric(6,2)  NOT NULL,
  borda_superior_mm   numeric(6,2)  NOT NULL DEFAULT 0,
  borda_inferior_mm   numeric(6,2)  NOT NULL DEFAULT 0,
  borda_esquerda_mm   numeric(6,2)  NOT NULL DEFAULT 0,
  borda_direita_mm    numeric(6,2)  NOT NULL DEFAULT 0,
  ativo               boolean       NOT NULL DEFAULT true,
  criado_em           timestamptz   NOT NULL DEFAULT now(),
  atualizado_em       timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT cfg_etiqueta_config_numero_colunas_ck CHECK (numero_colunas BETWEEN 1 AND 6),
  CONSTRAINT cfg_etiqueta_config_nome_uk UNIQUE (id_tenant, nome),
  -- base para FK composta (id_tenant, id_config_etiqueta) das duas tabelas filhas abaixo.
  CONSTRAINT cfg_etiqueta_config_id_uk UNIQUE (id_tenant, id_config_etiqueta)
);
CREATE INDEX cfg_etiqueta_config_id_tenant_ix ON cfg_etiqueta_config (id_tenant);

CREATE TABLE cfg_etiqueta_coluna (
  id_config_etiqueta_coluna integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant                 smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_config_etiqueta        integer      NOT NULL,
  numero_coluna              smallint     NOT NULL,
  posicao_inicial_mm         numeric(6,2) NOT NULL,
  CONSTRAINT cfg_etiqueta_coluna_numero_coluna_ck CHECK (numero_coluna BETWEEN 1 AND 6),
  CONSTRAINT cfg_etiqueta_coluna_uk UNIQUE (id_config_etiqueta, numero_coluna),
  CONSTRAINT cfg_etiqueta_coluna_config_fk FOREIGN KEY (id_tenant, id_config_etiqueta)
    REFERENCES cfg_etiqueta_config (id_tenant, id_config_etiqueta)
);
CREATE INDEX cfg_etiqueta_coluna_id_tenant_ix ON cfg_etiqueta_coluna (id_tenant);
CREATE INDEX cfg_etiqueta_coluna_config_ix    ON cfg_etiqueta_coluna (id_tenant, id_config_etiqueta);

CREATE TABLE cfg_etiqueta_campo (
  id_config_etiqueta_campo integer                    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant                smallint                   NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_config_etiqueta       integer                    NOT NULL,
  campo                    campo_etiqueta              NOT NULL,
  posicao_x_mm             numeric(6,2)               NOT NULL,
  posicao_y_mm             numeric(6,2)               NOT NULL,
  largura_mm               numeric(6,2),
  altura_mm                numeric(6,2),
  fonte                    fonte_etiqueta              NOT NULL DEFAULT 'ARIAL',
  tamanho_fonte_pt         numeric(5,2),
  negrito                  boolean                    NOT NULL DEFAULT false,
  fundo_preto              boolean                    NOT NULL DEFAULT false,
  alinhamento              alinhamento_etiqueta_campo   NOT NULL DEFAULT 'ESQUERDA',
  exibir_texto_legivel     boolean,
  CONSTRAINT cfg_etiqueta_campo_uk UNIQUE (id_config_etiqueta, campo),
  CONSTRAINT cfg_etiqueta_campo_config_fk FOREIGN KEY (id_tenant, id_config_etiqueta)
    REFERENCES cfg_etiqueta_config (id_tenant, id_config_etiqueta)
);
CREATE INDEX cfg_etiqueta_campo_id_tenant_ix ON cfg_etiqueta_campo (id_tenant);
CREATE INDEX cfg_etiqueta_campo_config_ix    ON cfg_etiqueta_campo (id_tenant, id_config_etiqueta);

-- RLS (P8) — mesmo padrão de V025/V026/V028.
ALTER TABLE cfg_etiqueta_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE cfg_etiqueta_config FORCE  ROW LEVEL SECURITY;
CREATE POLICY cfg_etiqueta_config_rls ON cfg_etiqueta_config
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_etiqueta_config TO niner_app;

ALTER TABLE cfg_etiqueta_coluna ENABLE ROW LEVEL SECURITY;
ALTER TABLE cfg_etiqueta_coluna FORCE  ROW LEVEL SECURITY;
CREATE POLICY cfg_etiqueta_coluna_rls ON cfg_etiqueta_coluna
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_etiqueta_coluna TO niner_app;

ALTER TABLE cfg_etiqueta_campo ENABLE ROW LEVEL SECURITY;
ALTER TABLE cfg_etiqueta_campo FORCE  ROW LEVEL SECURITY;
CREATE POLICY cfg_etiqueta_campo_rls ON cfg_etiqueta_campo
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_etiqueta_campo TO niner_app;

-- Guarda-corpo (P8), mesmo padrão de V024/V025/V026/V028.
DO $$
DECLARE faltantes text;
BEGIN
  SELECT string_agg(c.relname, ', ')
    INTO faltantes
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'id_tenant' AND a.attnum > 0 AND NOT a.attisdropped
  WHERE c.relkind = 'r'
    AND n.nspname = 'public'
    AND NOT c.relrowsecurity;
  IF faltantes IS NOT NULL THEN
    RAISE EXCEPTION 'P8: tabelas de tenant sem RLS habilitado: %', faltantes;
  END IF;
END $$;

COMMENT ON TABLE cfg_etiqueta_config IS 'Configuração nomeada de etiqueta de código de barras (rolo/etiqueta/bordas), por tenant — um tenant pode ter várias (RLS).';
COMMENT ON TABLE cfg_etiqueta_coluna IS 'Posição inicial (mm) de cada coluna de etiqueta no rolo físico, filha de cfg_etiqueta_config (RLS).';
COMMENT ON TABLE cfg_etiqueta_campo  IS 'Campo impresso numa etiqueta — posição x/y (mm) dentro da própria etiqueta + estilo (fonte/tamanho/negrito/fundo), filha de cfg_etiqueta_config (RLS).';
