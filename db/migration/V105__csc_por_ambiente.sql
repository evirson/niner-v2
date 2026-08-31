-- V105 — CSC por AMBIENTE (homologação × produção).
--
-- ⛔ O DEFEITO QUE ISTO CORRIGE, e ele estava marcado para o pior dia possível.
--
-- `fiscal_config_empresa` guardava UM par csc_id/csc_token_cifrado por empresa, com `ambiente`
-- num campo à parte (UNIQUE era (id_tenant, id_empresa)). Mas a SEFAZ credencia **CSCs
-- diferentes** para homologação e produção: o de homologação não vale em produção e vice-versa.
--
-- Consequência: no go-live, ao virar HOMOLOGACAO → PRODUCAO, o CSC de homologação continuaria
-- gravado e **toda NFC-e seria rejeitada com `cStat 464` — "Código de Hash no QR-Code difere do
-- calculado"** — no primeiro dia de operação real, com cliente no caixa. E o 464 não menciona CSC
-- em lugar nenhum: manda o diagnóstico para o QR Code, que estaria certo.
--
-- Não é hipótese: o mesmo 464 aconteceu neste banco em 2026-08-31 (7 emissões seguidas
-- rejeitadas, 35 autorizadas antes), e foi ele que trouxe este desenho à tona.
--
-- ⚠️ ESTA MIGRATION LÊ E TRANSFORMA DADO DE TENANT, então precisa de NO FORCE ROW LEVEL SECURITY:
-- migration roda como `niner_owner` e, com FORCE RLS (V024), **nem o dono escapa da política** —
-- sem `app.id_tenant` no contexto o UPDATE casaria ZERO linhas e o Flyway anunciaria sucesso.
-- `NO FORCE` (não `DISABLE`) libera só o dono; a política continua valendo para `niner_app`.

ALTER TABLE fiscal_config_empresa
  ADD COLUMN csc_id_homologacao           text,
  ADD COLUMN csc_token_homologacao_cifrado text,
  ADD COLUMN csc_id_producao              text,
  ADD COLUMN csc_token_producao_cifrado   text;

COMMENT ON COLUMN fiscal_config_empresa.csc_id_homologacao IS
  'Identificador do CSC de HOMOLOGAÇÃO. A SEFAZ credencia um CSC por ambiente — o de produção não '
  'vale aqui, e usar o errado rejeita toda NFC-e com cStat 464.';
COMMENT ON COLUMN fiscal_config_empresa.csc_id_producao IS
  'Identificador do CSC de PRODUÇÃO. Ver o comentário de csc_id_homologacao.';

-- Backfill: o par que existe hoje é o do ambiente EM QUE A EMPRESA ESTÁ — é o único fato que o
-- dado sustenta. ⛔ Copiar para os dois ambientes seria inventar um CSC de produção que ninguém
-- cadastrou, e o efeito disso é exatamente o defeito que esta migration corrige: o go-live
-- rejeitando tudo, agora com a agravante de a tela AFIRMAR que o CSC de produção está configurado.
ALTER TABLE fiscal_config_empresa NO FORCE ROW LEVEL SECURITY;

UPDATE fiscal_config_empresa
   SET csc_id_homologacao = csc_id,
       csc_token_homologacao_cifrado = csc_token_cifrado
 WHERE ambiente = 'HOMOLOGACAO' AND csc_token_cifrado IS NOT NULL;

UPDATE fiscal_config_empresa
   SET csc_id_producao = csc_id,
       csc_token_producao_cifrado = csc_token_cifrado
 WHERE ambiente = 'PRODUCAO' AND csc_token_cifrado IS NOT NULL;

ALTER TABLE fiscal_config_empresa FORCE ROW LEVEL SECURITY;

-- ⚠️ As colunas antigas ficam, por ora, como estavam. Não são mais lidas pela emissão (o
-- `FiscalConfigService` passou a resolver o par pelo ambiente corrente), e removê-las na mesma
-- migration do backfill impediria conferir o resultado depois — que é o passo que a V089 pulou e
-- gravou 347 vendas com a coluna vazia. A remoção fica para uma migration futura, depois que o
-- lojista tiver reconfigurado e emitido.
