-- =============================================================================================
-- V045 — realinha o schema de bancos que aplicaram V017/V035 ANTES de elas serem editadas.
--
-- ⚠️ O que aconteceu (2026-08-19, achado ao publicar em produção): dois arquivos de migration
-- **já aplicados** foram alterados no lugar, em vez de ganharem uma migration nova:
--
--   · V017__catalogo.sql   — `cfg_produto_ncm.aliquota_ibpt` virou quatro colunas
--                            (commit 4f48fa3, DANFE §3.2 / Lei 12.741)
--   · V035__fiscal_documento.sql — `documento_fiscal` ganhou base_ibs_cbs + o trio
--                            valor_trib_federal/estadual/municipal, e
--                            `documento_fiscal_evento` ganhou `tentativa` na UNIQUE
--                            (commits 2761c39 e 3d65d7b)
--
-- Num banco novo isso passa despercebido — o arquivo editado roda uma vez e produz o schema
-- certo. Em QUALQUER banco que já tinha rodado a versão anterior (o de produção, e o de
-- desenvolvimento de quem não recriou), o Flyway recusa a subir inteiro:
--
--     ERROR: Validate failed: Migrations have failed validation
--     Migration checksum mismatch for migration version 017
--
-- O deploy de produção parou aqui, sem publicar nada. Migration aplicada é imutável: o conserto
-- é sempre **para frente**. Esta migration é idempotente de propósito (`IF NOT EXISTS`,
-- verificação da constraint antes de recriar) porque roda nos dois mundos — no banco novo, onde
-- V017/V035 já criaram tudo, ela não faz nada.
--
-- Depois desta migration, o banco antigo precisa de `flyway repair` UMA vez, para que a soma de
-- verificação registrada de V017/V035 volte a bater com o arquivo em disco.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 1) cfg_produto_ncm — alíquota IBPT detalhada (V017 editada)
-- O tributo aproximado usa a federal NACIONAL **ou** a IMPORTADA, nunca as duas: depende da
-- origem do produto. Uma coluna só (a antiga `aliquota_ibpt`) superestimaria todo item nacional.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE cfg_produto_ncm ADD COLUMN IF NOT EXISTS alq_federal_nacional  numeric(10,2);
ALTER TABLE cfg_produto_ncm ADD COLUMN IF NOT EXISTS alq_federal_importado numeric(10,2);
ALTER TABLE cfg_produto_ncm ADD COLUMN IF NOT EXISTS alq_estadual          numeric(10,2);
ALTER TABLE cfg_produto_ncm ADD COLUMN IF NOT EXISTS alq_municipal         numeric(10,2);

-- Aproveita o que já estava carregado: a coluna antiga guardava a federal. Sem sobrescrever
-- quem já tenha a nova preenchida.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'cfg_produto_ncm' AND column_name = 'aliquota_ibpt') THEN
        EXECUTE 'UPDATE cfg_produto_ncm SET alq_federal_nacional = aliquota_ibpt
                 WHERE alq_federal_nacional IS NULL AND aliquota_ibpt IS NOT NULL';
        EXECUTE 'ALTER TABLE cfg_produto_ncm DROP COLUMN aliquota_ibpt';
    END IF;
END $$;

COMMENT ON TABLE cfg_produto_ncm IS 'Referência de NCM (código + descrição + alíquotas IBPT federal nacional/importado + estadual/municipal), GLOBAL — igual para todos os tenants, sem RLS. Mantida por script, sem tela de manutenção.';

-- ---------------------------------------------------------------------------------------------
-- 2) documento_fiscal — base do IBS/CBS e o detalhamento da Lei 12.741 (V035 editada)
-- Os três valor_trib_* somam valor_total_tributos; não vão no XML (o XSD tem um vTotTrib só),
-- existem para o DANFE.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE documento_fiscal ADD COLUMN IF NOT EXISTS base_ibs_cbs         numeric(12,2) NOT NULL DEFAULT 0;
ALTER TABLE documento_fiscal ADD COLUMN IF NOT EXISTS valor_trib_federal   numeric(12,2) NOT NULL DEFAULT 0;
ALTER TABLE documento_fiscal ADD COLUMN IF NOT EXISTS valor_trib_estadual  numeric(12,2) NOT NULL DEFAULT 0;
ALTER TABLE documento_fiscal ADD COLUMN IF NOT EXISTS valor_trib_municipal numeric(12,2) NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------------------------
-- 3) documento_fiscal_evento — `tentativa` na chave única (V035 editada)
-- Cancelamento (110111) sempre usa sequencia = 1; sem `tentativa` na UNIQUE, uma 1ª tentativa
-- recusada ocupava a única linha permitida e travava o reenvio com "duplicate key" — mesmo
-- quando a SEFAZ aceitava o reenvio idêntico.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE documento_fiscal_evento ADD COLUMN IF NOT EXISTS tentativa smallint NOT NULL DEFAULT 1;

DO $$
DECLARE
    definicao text;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO definicao
      FROM pg_constraint
     WHERE conname = 'documento_fiscal_evento_uk'
       AND conrelid = 'documento_fiscal_evento'::regclass;

    IF definicao IS NULL THEN
        ALTER TABLE documento_fiscal_evento
            ADD CONSTRAINT documento_fiscal_evento_uk
            UNIQUE (id_tenant, id_documento_fiscal, tipo_evento, sequencia, tentativa);
    ELSIF position('tentativa' in definicao) = 0 THEN
        ALTER TABLE documento_fiscal_evento DROP CONSTRAINT documento_fiscal_evento_uk;
        ALTER TABLE documento_fiscal_evento
            ADD CONSTRAINT documento_fiscal_evento_uk
            UNIQUE (id_tenant, id_documento_fiscal, tipo_evento, sequencia, tentativa);
    END IF;
END $$;
