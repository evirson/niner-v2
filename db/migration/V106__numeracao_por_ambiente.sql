-- V106 — NUMERAÇÃO FISCAL POR AMBIENTE (homologação × produção), nos três documentos.
--
-- ⛔ O DEFEITO: a numeração era compartilhada entre os dois ambientes.
--
--   fiscal_numeracao          PK (id_tenant, id_empresa, modelo, serie)      -- NFC-e e NF-e
--   nfse_numeracao            PK (id_tenant, id_empresa, serie)              -- NFS-e
--   nfse_documento_numeracao_uk  UNIQUE (id_tenant, id_empresa, serie, numero_dps)
--
-- Nenhuma delas carregava o ambiente. Consequência: **cada nota emitida em homologação queima um
-- número de produção**. Medido neste banco em 2026-08-31: a NFC-e de homologação já estava no
-- número 58, então a primeira nota de PRODUÇÃO sairia com 59 — e os números 1 a 58, que a SEFAZ de
-- produção nunca viu, viram buraco de numeração e obrigação de inutilização formal.
--
-- ⭐ Homologação e produção são bases SEPARADAS na SEFAZ: cada uma tem a sua própria sequência,
-- começando do 1. Um contador só para as duas é uma modelagem que só funciona enquanto ninguém
-- troca de ambiente — e trocar de ambiente é exatamente o que o go-live é.
--
-- ⚠️ Esta migration ALTERA CHAVE PRIMÁRIA de tabela com dado. O backfill marca tudo o que existe
-- como do ambiente em que a empresa está HOJE, que é o único fato que o dado sustenta.
-- ⚠️ E ela LÊ dado de tenant: precisa de NO FORCE ROW LEVEL SECURITY, senão o UPDATE casa zero
-- linhas e o Flyway anuncia sucesso (a lição da V089).

-- ---------------------------------------------------------------------------------------------
-- 1. NFC-e / NF-e
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fiscal_numeracao ADD COLUMN ambiente ambiente_fiscal;

ALTER TABLE fiscal_numeracao NO FORCE ROW LEVEL SECURITY;
UPDATE fiscal_numeracao n
   SET ambiente = c.ambiente
  FROM fiscal_config_empresa c
 WHERE c.id_tenant = n.id_tenant AND c.id_empresa = n.id_empresa;
-- Empresa sem configuração fiscal: o default do sistema é homologação (V035).
UPDATE fiscal_numeracao SET ambiente = 'HOMOLOGACAO' WHERE ambiente IS NULL;
ALTER TABLE fiscal_numeracao FORCE ROW LEVEL SECURITY;

ALTER TABLE fiscal_numeracao ALTER COLUMN ambiente SET NOT NULL;
ALTER TABLE fiscal_numeracao DROP CONSTRAINT fiscal_numeracao_pk;
ALTER TABLE fiscal_numeracao
  ADD CONSTRAINT fiscal_numeracao_pk PRIMARY KEY (id_tenant, id_empresa, modelo, serie, ambiente);

COMMENT ON COLUMN fiscal_numeracao.ambiente IS
  'Homologação e produção são bases separadas na SEFAZ, cada uma com sua própria sequência a '
  'partir do 1. Sem esta coluna, nota de teste queimava número de produção.';

-- ---------------------------------------------------------------------------------------------
-- 2. NFS-e
-- ---------------------------------------------------------------------------------------------
ALTER TABLE nfse_numeracao ADD COLUMN ambiente ambiente_fiscal;

ALTER TABLE nfse_numeracao NO FORCE ROW LEVEL SECURITY;
UPDATE nfse_numeracao n
   SET ambiente = c.ambiente
  FROM fiscal_config_nfse c
 WHERE c.id_tenant = n.id_tenant AND c.id_empresa = n.id_empresa;
UPDATE nfse_numeracao SET ambiente = 'HOMOLOGACAO' WHERE ambiente IS NULL;
ALTER TABLE nfse_numeracao FORCE ROW LEVEL SECURITY;

ALTER TABLE nfse_numeracao ALTER COLUMN ambiente SET NOT NULL;
ALTER TABLE nfse_numeracao DROP CONSTRAINT nfse_numeracao_pk;
ALTER TABLE nfse_numeracao
  ADD CONSTRAINT nfse_numeracao_pk PRIMARY KEY (id_tenant, id_empresa, serie, ambiente);

-- A UNIQUE do documento também: o mesmo nDPS pode existir uma vez em cada ambiente, e é isso que
-- permite a produção começar do 1 depois de a homologação ter chegado ao 2000.
-- ⚠️ `nfse_documento.ambiente` JÁ existe (V102) — só a restrição é que o ignorava.
ALTER TABLE nfse_documento DROP CONSTRAINT nfse_documento_numeracao_uk;
ALTER TABLE nfse_documento
  ADD CONSTRAINT nfse_documento_numeracao_uk UNIQUE (id_tenant, id_empresa, serie, numero_dps, ambiente);
