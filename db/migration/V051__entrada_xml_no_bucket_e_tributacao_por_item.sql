-- V051 — XML da entrada vai para o bucket fiscal, e a tributação da nota do fornecedor passa a ser
-- gravada item a item (2026-08-20). Pré-requisito da rotina de DEVOLUÇÃO DE COMPRA ao fornecedor.
--
-- ============================================================================================
-- PARTE 1 — o XML sai do banco e vai para o mesmo lugar das notas de saída
-- ============================================================================================
-- `entrada_xml.xml_bruto` (V031) guarda o XML da NF-e do fornecedor numa coluna `text`. Funciona,
-- mas destoa do resto do módulo fiscal em três pontos que importam:
--   · **peso** — uma NF-e de 200 itens tem ~500 KB; mil notas/mês são ~500 MB/mês dentro do
--     Postgres **e dentro do `pg_dump` diário**, que é justamente o que o object storage evita;
--   · **imutabilidade** — a área `FISCAL_XML` do MinIO (ADR-014) recusa sobrescrever e apagar, e
--     tem a regra de ciclo de vida de 5 anos. Uma coluna `text` aceita `UPDATE` e `DELETE`;
--   · **prova de origem** — a devolução ao fornecedor só é emitida contra uma nota de entrada
--     arquivada. "Arquivada" tem de significar o mesmo que significa para a nota de saída.
--
-- ⚠️ `xml_bruto` fica ANULÁVEL em vez de ser removida: entrada já importada num banco existente
-- tem o XML só ali, e derrubar a coluna apagaria essa nota sem aviso. A leitura cai de volta nela
-- quando `xml_objeto_bucket` for nulo. Remover a coluna é migration futura, depois de migrar o que
-- houver — sempre para a frente, nunca editando esta.

ALTER TABLE entrada_xml
  ALTER COLUMN xml_bruto DROP NOT NULL,
  ADD COLUMN xml_objeto_bucket text,
  ADD COLUMN xml_hash          text,
  ADD COLUMN arquivado_em      timestamptz;

-- Uma das duas formas de guarda tem de existir — linha sem XML nenhum não serve para nada e
-- esconderia uma falha de gravação.
ALTER TABLE entrada_xml
  ADD CONSTRAINT entrada_xml_tem_conteudo_ck
  CHECK (xml_bruto IS NOT NULL OR xml_objeto_bucket IS NOT NULL);

COMMENT ON COLUMN entrada_xml.xml_bruto IS
  'LEGADO: XML no proprio banco (V031). Entrada nova grava no bucket — ver xml_objeto_bucket. Mantida anulavel para nao perder o que ja foi importado antes da V051.';
COMMENT ON COLUMN entrada_xml.xml_objeto_bucket IS
  'Chave do objeto na area FISCAL_XML do MinIO (ADR-014), prefixo `entrada/`. Guarda a CHAVE, nunca a URL — mesma regra de documento_fiscal.xml_objeto_bucket.';
COMMENT ON COLUMN entrada_xml.xml_hash IS
  'SHA-256 do XML gravado — prova que o objeto no bucket e o mesmo que entrou.';

-- ============================================================================================
-- PARTE 2 — a tributação da nota do fornecedor, item a item
-- ============================================================================================
-- Para emitir a NF-e de devolução ao fornecedor é preciso ESPELHAR a tributação da nota de
-- entrada (a legislação pede os mesmos valores e bases, para o estorno bater). Hoje
-- `produto_movimento_detalhe` guarda apenas quantidade, custo, preço de venda, desconto e
-- acréscimo: **nenhum dado fiscal**.
--
-- É o mesmo buraco que o B9 encontrou do outro lado — `documento_fiscal_item` existia mas nunca
-- recebia INSERT, e a devolução ao consumidor não tinha de onde espelhar. A decisão daquela vez
-- foi gravar no ato da emissão em vez de parsear o XML depois ("frágil e desnecessário"). Aqui é
-- a mesma decisão, na direção da entrada.
--
-- ⚠️ NÃO é uma cópia de `documento_fiscal_item`, e a diferença principal é o **IPI**: a nossa
-- nota de saída é varejo no Simples e não destaca IPI, mas a nota que o FORNECEDOR emite quase
-- sempre destaca (indústria → varejista). Sem essas colunas, a devolução sairia sem o imposto que
-- a nota de origem cobrou.

CREATE TABLE entrada_nfe_item (
  id_entrada_item     bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant           smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_movimento        integer       NOT NULL,
  numero_item         smallint      NOT NULL,          -- nItem do XML do fornecedor
  -- A variação que ESTE item virou no nosso catálogo. Anulável: item da nota que o operador
  -- decidiu não importar (pendência) não vira variação, mas a tributação dele continua registrada.
  id_variacao         integer,

  -- Identificação como o FORNECEDOR declarou (não como está no nosso cadastro): é o que a nota de
  -- devolução tem de repetir.
  codigo_produto      text          NOT NULL,          -- cProd do fornecedor
  codigo_ean          text,                            -- cEAN (pode vir "SEM GTIN")
  descricao           text          NOT NULL,          -- xProd
  codigo_ncm          text,
  cest                text,
  cfop                char(4)       NOT NULL,          -- o CFOP de ENTRADA declarado pelo fornecedor
  unidade_comercial   text          NOT NULL,
  quantidade          numeric(14,4) NOT NULL,
  valor_unitario      numeric(14,6) NOT NULL,
  valor_produto       numeric(12,2) NOT NULL,
  valor_desconto      numeric(12,2) NOT NULL DEFAULT 0,
  valor_frete         numeric(12,2) NOT NULL DEFAULT 0,
  valor_seguro        numeric(12,2) NOT NULL DEFAULT 0,
  valor_outros        numeric(12,2) NOT NULL DEFAULT 0,
  origem_mercadoria   smallint      NOT NULL DEFAULT 0,

  -- ICMS
  cst_icms            char(2),
  csosn               char(3),
  base_calculo_icms   numeric(12,2) NOT NULL DEFAULT 0,
  perc_reducao_bc     numeric(5,2)  NOT NULL DEFAULT 0,
  aliquota_icms       numeric(5,2)  NOT NULL DEFAULT 0,
  valor_icms          numeric(12,2) NOT NULL DEFAULT 0,
  -- Substituição tributária
  base_calculo_st     numeric(12,2) NOT NULL DEFAULT 0,
  mva_st              numeric(5,2)  NOT NULL DEFAULT 0,
  aliquota_st         numeric(5,2)  NOT NULL DEFAULT 0,
  valor_icms_st       numeric(12,2) NOT NULL DEFAULT 0,
  base_st_retido      numeric(12,2) NOT NULL DEFAULT 0,
  icms_st_retido      numeric(12,2) NOT NULL DEFAULT 0,
  aliquota_fcp        numeric(5,2)  NOT NULL DEFAULT 0,
  valor_fcp           numeric(12,2) NOT NULL DEFAULT 0,
  -- IPI — a diferença que justifica esta tabela não ser um espelho de documento_fiscal_item
  cst_ipi             char(2),
  base_calculo_ipi    numeric(12,2) NOT NULL DEFAULT 0,
  aliquota_ipi        numeric(5,2)  NOT NULL DEFAULT 0,
  valor_ipi           numeric(12,2) NOT NULL DEFAULT 0,
  -- PIS/COFINS
  cst_pis             char(2),
  base_calculo_pis    numeric(12,2) NOT NULL DEFAULT 0,
  aliquota_pis        numeric(5,2)  NOT NULL DEFAULT 0,
  valor_pis           numeric(12,2) NOT NULL DEFAULT 0,
  cst_cofins          char(2),
  base_calculo_cofins numeric(12,2) NOT NULL DEFAULT 0,
  aliquota_cofins     numeric(5,2)  NOT NULL DEFAULT 0,
  valor_cofins        numeric(12,2) NOT NULL DEFAULT 0,

  criado_em           timestamptz   NOT NULL DEFAULT now(),

  CONSTRAINT entrada_nfe_item_uk UNIQUE (id_tenant, id_movimento, numero_item),
  CONSTRAINT entrada_nfe_item_movimento_fk FOREIGN KEY (id_tenant, id_movimento)
    REFERENCES produto_movimento_mestre (id_tenant, id_movimento),
  CONSTRAINT entrada_nfe_item_variacao_fk FOREIGN KEY (id_tenant, id_variacao)
    REFERENCES produto_barra (id_tenant, id_variacao)
);
CREATE INDEX entrada_nfe_item_movimento_ix ON entrada_nfe_item (id_tenant, id_movimento);

COMMENT ON TABLE entrada_nfe_item IS
  'Tributacao da NF-e do FORNECEDOR, item a item, gravada no ato da entrada por XML. E a fonte que a devolucao de compra espelha — sem ela seria preciso parsear o XML na hora, caminho ja rejeitado no B9. Entrada manual/planilha nao alimenta esta tabela: essas nao tem nota de origem.';

-- ============================================================================================
-- RLS + GRANTS — mesmo padrão de todas as tabelas de domínio (P8)
-- ============================================================================================
ALTER TABLE entrada_nfe_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE entrada_nfe_item FORCE ROW LEVEL SECURITY;
CREATE POLICY entrada_nfe_item_tenant ON entrada_nfe_item
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'niner_app') THEN
    -- Sem DELETE: item de nota de entrada é histórico fiscal. O cancelamento da entrada marca o
    -- movimento como cancelado; não apaga o que a nota do fornecedor declarou (P3/F6).
    GRANT SELECT, INSERT, UPDATE ON entrada_nfe_item TO niner_app;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'niner_owner') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON entrada_nfe_item TO niner_owner;
  END IF;
END $$;
