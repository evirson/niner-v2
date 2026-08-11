-- V031 — Entrada de Produtos por Compra (2026-08-11): payload bruto do XML da NF-e (auditoria,
-- P3) e o vínculo produto×fornecedor (código do fornecedor, pra casar itens de notas futuras
-- sem precisar de EAN). Nenhuma mudança nas tabelas de ledger (produto_movimento_mestre/detalhe,
-- V019) além das colunas já adicionadas naquele arquivo (id_usuario/chave_nfe/serie_nota).

-- XML bruto da NF-e importada, 1:1 com o movimento que ela gerou (auditoria/conferência, P3).
CREATE TABLE entrada_xml (
  id_movimento  integer     NOT NULL,
  id_tenant     smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  xml_bruto     text        NOT NULL,
  importado_em  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT entrada_xml_pkey PRIMARY KEY (id_tenant, id_movimento),
  CONSTRAINT entrada_xml_movimento_fk FOREIGN KEY (id_tenant, id_movimento)
    REFERENCES produto_movimento_mestre (id_tenant, id_movimento)
);

-- Vínculo entre o código do fornecedor (cProd do XML) e a variação do sistema — acelera o match
-- de importações futuras do mesmo fornecedor sem depender de EAN. unidade_compra/fator_conversao
-- resolvem a conversão de unidade de compra (ex.: CX12) pra unidade de venda (UN).
CREATE TABLE produto_fornecedor (
  id_produto_fornecedor integer        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant             smallint       NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_variacao           integer        NOT NULL,
  id_fornecedor         integer        NOT NULL,
  codigo_fornecedor     text           NOT NULL,             -- cProd do XML
  unidade_compra        text,                                -- ex.: 'CX12'; nullable, default = mesma unidade de venda
  fator_conversao       numeric(10,3)  NOT NULL DEFAULT 1,    -- quantas unidades de venda cabem numa unidade de compra
  criado_em             timestamptz    NOT NULL DEFAULT now(),
  CONSTRAINT produto_fornecedor_uk UNIQUE (id_tenant, id_fornecedor, codigo_fornecedor),
  CONSTRAINT produto_fornecedor_variacao_fk FOREIGN KEY (id_tenant, id_variacao)
    REFERENCES produto_barra (id_tenant, id_variacao),
  CONSTRAINT produto_fornecedor_fornecedor_fk FOREIGN KEY (id_tenant, id_fornecedor)
    REFERENCES fornecedor (id_tenant, id_fornecedor)
);
CREATE INDEX produto_fornecedor_id_tenant_ix ON produto_fornecedor (id_tenant);
CREATE INDEX produto_fornecedor_variacao_ix  ON produto_fornecedor (id_tenant, id_variacao);

-- RLS (P8), mesmo padrão de V026.
ALTER TABLE entrada_xml ENABLE ROW LEVEL SECURITY;
ALTER TABLE entrada_xml FORCE  ROW LEVEL SECURITY;
CREATE POLICY entrada_xml_rls ON entrada_xml
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT, UPDATE, DELETE ON entrada_xml TO niner_app;

ALTER TABLE produto_fornecedor ENABLE ROW LEVEL SECURITY;
ALTER TABLE produto_fornecedor FORCE  ROW LEVEL SECURITY;
CREATE POLICY produto_fornecedor_rls ON produto_fornecedor
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT, UPDATE, DELETE ON produto_fornecedor TO niner_app;

-- Guarda-corpo (P8), mesmo padrão de V024/V025/V026: falha a migration se qualquer tabela com
-- id_tenant tiver ficado sem RLS habilitado.
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

COMMENT ON TABLE entrada_xml IS 'XML bruto da NF-e importada por Entrada de Produtos, 1:1 com produto_movimento_mestre (auditoria, P3). RLS.';
COMMENT ON TABLE produto_fornecedor IS 'Vinculo codigo-do-fornecedor (cProd) <-> variacao do sistema, aprendido a cada Entrada de Produtos. RLS.';
