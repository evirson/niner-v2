-- ---------------------------------------------------------------------------------------------
-- Fornecedor ganha código do município (IBGE) e indicador de IE (2026-08-24).
--
-- POR QUÊ: são os dois campos que a NF-e exige do participante e que o cadastro de fornecedor não
-- tinha. O cliente ganhou os mesmos no mesmo dia, quando a venda a pessoa jurídica passou a sair em
-- NF-e 55 — e o fornecedor precisa deles pelo outro lado: a **devolução ao fornecedor** também é
-- uma NF-e 55, com o fornecedor no grupo `dest`.
--
-- `indicador_ie` repete o CHECK e o default do cliente (1 contribuinte · 2 isento · 9 não
-- contribuinte; 9 é o padrão porque é o caso mais comum e o que a NFC-e sempre usou). É ele que
-- decide se a inscrição estadual entra na nota: o XSD recusa a tag `IE` quando não é 1.
--
-- Só DDL — não lê nem transforma dado de tenant, então dispensa o `NO FORCE ROW LEVEL SECURITY`
-- que backfill exigiria (ver docs/infra/isolamento-tenant-rls.md).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fornecedor
    ADD COLUMN IF NOT EXISTS codigo_municipio_ibge integer,
    ADD COLUMN IF NOT EXISTS indicador_ie smallint NOT NULL DEFAULT 9;

ALTER TABLE fornecedor
    DROP CONSTRAINT IF EXISTS fornecedor_indicador_ie_ck;

ALTER TABLE fornecedor
    ADD CONSTRAINT fornecedor_indicador_ie_ck CHECK (indicador_ie IN (1, 2, 9));

COMMENT ON COLUMN fornecedor.codigo_municipio_ibge IS
    'Código IBGE do município (7 dígitos). Preenchido automaticamente pelo CEP na tela.';
COMMENT ON COLUMN fornecedor.indicador_ie IS
    '1 contribuinte · 2 isento · 9 não contribuinte. Decide se a IE entra na NF-e (indIEDest).';
