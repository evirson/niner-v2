-- V101 — configuração da NFS-e por empresa, e o que varia por município (bloco S5.5)
--
-- Duas tabelas com naturezas opostas de propósito:
--   fiscal_config_nfse   — do LOJISTA, por empresa, sob RLS
--   cfg_municipio_nfse   — do MUNICÍPIO, global, sem tenant e sem RLS
--
-- ⛔ POR QUE NÃO SÃO COLUNAS EM `fiscal_config_empresa`
-- Aquela tabela é de NF-e/NFC-e: CRT, CSC, série do modelo 65, contingência. O vocabulário da
-- NFS-e é outro (ISS, competência, Simples, município) e a DS9 do MODULOSERVICOS.md já recusou
-- misturar os dois no `documento_fiscal` pelo mesmo motivo — metade das colunas sempre nula. Aqui
-- vale igual, e ainda evita alargar uma tabela lida em todo caminho de venda.
--
-- ⭐ O CAMPO QUE DECIDE SE A LOJA CONSEGUE EMITIR: `aliquota_simples_efetiva`
-- Medido em produção em 2026-08-30/31 (docs/MODULONFSE.md §2.6): para ME/EPP o SEFIN RECUSA o
-- `indTotTrib` (E0712) e o schema EXIGE o bloco `totTrib` (E1235). Não existe emissão de optante do
-- Simples sem esse percentual. Ele é do contador, o Nainer não tem como derivá-lo (o ADR-015 já
-- recusou tirar de `uso_venda_mes`: é venda no ERP, não receita da empresa), e por isso é
-- PRÉ-REQUISITO — a Conformidade Fiscal barra antes (F11), em vez de o operador tomar E0712 no
-- balcão.

-- ---------------------------------------------------------------------------------------------
-- 1. A configuração do lojista
-- ---------------------------------------------------------------------------------------------

CREATE TABLE fiscal_config_nfse (
  id_config_nfse            integer         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant                 smallint        NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa                integer         NOT NULL,
  emite_nfse                boolean         NOT NULL DEFAULT false,   -- o gate do F12
  ambiente                  ambiente_fiscal NOT NULL DEFAULT 'HOMOLOGACAO',
  serie                     smallint        NOT NULL DEFAULT 1,
  -- Simples Nacional — o que vai no pTotTribSN
  rbt12                     numeric(14,2),
  simples_anexo             text,
  aliquota_simples_efetiva  numeric(5,2),
  -- Resultado do último "Testar conexão" (o GET que espera 404 + E2401)
  ultimo_teste_em           timestamptz,
  ultimo_teste_status       text,
  ultimo_teste_mensagem     text,
  criado_em                 timestamptz     NOT NULL DEFAULT now(),
  atualizado_em             timestamptz     NOT NULL DEFAULT now(),
  CONSTRAINT fiscal_config_nfse_uk UNIQUE (id_tenant, id_empresa),
  -- FK composta (P8) — FK simples não valida que o id_empresa é do mesmo tenant.
  CONSTRAINT fiscal_config_nfse_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT fiscal_config_nfse_serie_ck CHECK (serie >= 1 AND serie <= 99999),
  CONSTRAINT fiscal_config_nfse_anexo_ck CHECK (simples_anexo IS NULL
                                                OR simples_anexo IN ('III', 'V')),
  CONSTRAINT fiscal_config_nfse_rbt12_ck CHECK (rbt12 IS NULL OR rbt12 >= 0),
  -- Teto de 33%: é o maior percentual que os Anexos III e V alcançam na 6ª faixa. Percentual sem
  -- teto já custou caro neste repositório (V083, perc_desconto de 999,99%), e aqui ele vai
  -- IMPRESSO na nota como "total de tributos".
  CONSTRAINT fiscal_config_nfse_aliq_sn_ck CHECK (aliquota_simples_efetiva IS NULL
                                                  OR (aliquota_simples_efetiva >= 0
                                                      AND aliquota_simples_efetiva <= 33))
);

CREATE INDEX fiscal_config_nfse_id_tenant_ix ON fiscal_config_nfse (id_tenant);

COMMENT ON TABLE fiscal_config_nfse IS
  'Configuração da NFS-e POR EMPRESA (não por tenant): gate de emissão, ambiente, série da DPS e '
  'os dados do Simples que vão no pTotTribSN. Separada de fiscal_config_empresa de propósito — '
  'ver o cabeçalho da V101. RLS.';

COMMENT ON COLUMN fiscal_config_nfse.emite_nfse IS
  'Gate do F12, desligado por padrão: quem não emite opera com a papeleta e o Recibo de Serviço, e '
  'isso é caminho permanente (DS10/P5). ⚠️ A tela precisa dizer para quem a nota é devida — desde '
  '01/11/2026 ME/EPP do Simples é obrigada ao Emissor Nacional, e escolher "só papeleta" ali é '
  'estar irregular. A escolha é legítima, mas tem de ser informada, não silenciosa.';

COMMENT ON COLUMN fiscal_config_nfse.serie IS
  'Série da DPS. ⚠️ A numeração do nDPS é por (CNPJ, série) no SEFIN — cada empresa tem a sua, e '
  'não há sequência compartilhada entre tenants. (A colisão que apareceu no S0 foi artefato do '
  'teste ter tomado emprestado o CNPJ da Vetor, que o finance-v também usa.)';

COMMENT ON COLUMN fiscal_config_nfse.aliquota_simples_efetiva IS
  'pTotTribSN — alíquota efetiva do Simples, em %. ⛔ PRÉ-REQUISITO DE EMISSÃO para optante: sem '
  'ela o SEFIN recusa com E0712, e omitir o bloco totTrib recusa com E1235 (medido em produção, '
  'docs/MODULONFSE.md §2.6). Vem do contador (extrato do PGDAS-D do mês anterior); o ERP não tem '
  'como derivar, porque conhece as vendas feitas NELE e não a receita declarada da empresa.';

COMMENT ON COLUMN fiscal_config_nfse.rbt12 IS
  'Receita bruta dos últimos 12 meses, informada pelo lojista/contador. Serve para a tela CALCULAR '
  'a alíquota efetiva pelas tabelas dos Anexos III/V em vez de exigir o número pronto — é o máximo '
  'de autoatendimento possível aqui. Nulo = o lojista informou a alíquota direto.';

COMMENT ON COLUMN fiscal_config_nfse.ultimo_teste_status IS
  'OK | FALHA do botão "Testar conexão". O teste é um GET de chave inexistente: a resposta esperada '
  'é 404 com E2401, e é ISSO que prova que certificado, mTLS e ambiente estão certos.';

-- ---------------------------------------------------------------------------------------------
-- 2. O que varia por município — o F10 aplicado à prefeitura
-- ---------------------------------------------------------------------------------------------
-- O F10 do módulo fiscal diz: *"regra da SEFAZ que varia por UF é LINHA, nunca `if`"*. A NFS-e
-- repete isso um nível abaixo, no município — e o S0 provou que a diferença é real e cara:
--   · `enviar_im`: em Curitiba/produção a IM é PROIBIDA (E0120); em produção restrita nada passa
--     (E0116, com e sem IM, em qualquer formato). Outro município pode exigir.
--   · prazo de cancelamento: 24 h em Curitiba, 5 dias no padrão nacional, outros valores em outras
--     cidades — competência municipal, não nacional.
--
-- ⭐ NASCE VAZIA, e isso é decisão, não preguiça. Semear exigiria inventar o prazo de cancelamento
-- (lei municipal, que o ADN não devolve) e cachear a adesão de milhares de municípios que mudam
-- sem nos avisar. A linha é criada na configuração de cada loja, a partir da consulta ao ADN
-- (`GET /parametrizacao/{cMun}/convenio`), e `consultado_em` diz quando — linha velha é
-- reconsultada, em vez de envelhecer em silêncio.
--
-- GLOBAL, sem id_tenant e sem RLS: é fato sobre a prefeitura, igual para todos os lojistas
-- daquela cidade. Mesma exceção documentada de cfg_produto_ncm, cfg_uf_autorizador e
-- cfg_servico_lc116.

CREATE TABLE cfg_municipio_nfse (
  codigo_ibge               integer         NOT NULL,
  ambiente                  ambiente_fiscal NOT NULL,
  aderente_emissor_nacional boolean,
  enviar_im                 boolean,
  prazo_cancelamento_horas  integer,
  consultado_em             timestamptz,
  observacao                text,
  CONSTRAINT cfg_municipio_nfse_pk PRIMARY KEY (codigo_ibge, ambiente),
  CONSTRAINT cfg_municipio_nfse_ibge_ck  CHECK (codigo_ibge BETWEEN 1000000 AND 9999999),
  CONSTRAINT cfg_municipio_nfse_prazo_ck CHECK (prazo_cancelamento_horas IS NULL
                                                OR prazo_cancelamento_horas > 0)
);

COMMENT ON TABLE cfg_municipio_nfse IS
  'O que a NFS-e tem de diferente por município e ambiente — o F10 aplicado à prefeitura. GLOBAL, '
  'sem id_tenant e sem RLS. Nasce vazia e é preenchida por consulta ao ADN; ver o cabeçalho da '
  'V101 para por que não há seed.';

COMMENT ON COLUMN cfg_municipio_nfse.aderente_emissor_nacional IS
  'Vem de GET /parametrizacao/{cMun}/convenio (campo aderenteEmissorNacional). ⭐ É a DS8 '
  'respondida por município, pela fonte oficial: "aderir ao ambiente nacional" e "operar no '
  'Emissor Nacional" são coisas diferentes, e medindo em 2026-08-29 Salvador tinha 1 e 0. '
  'Falso = o Nainer não atende essa cidade, e a tela diz isso na implantação, não no balcão.';

COMMENT ON COLUMN cfg_municipio_nfse.enviar_im IS
  'Se a Inscrição Municipal do prestador vai na DPS. ⚠️ Errar dá E0120 (mandou onde não devia) ou '
  'E0116 (o CNC do município não reconhece a que foi mandada) — dois códigos que não dizem nada a '
  'quem não conhece o CNC. Descoberto pelo assistente de configuração, não perguntado ao lojista. '
  'Nulo = ainda não se sabe.';

COMMENT ON COLUMN cfg_municipio_nfse.prazo_cancelamento_horas IS
  'Prazo para cancelar sem processo administrativo. Competência MUNICIPAL: 24 h em Curitiba, 5 '
  'dias no padrão nacional, outros valores em outras cidades. Nulo = desconhecido, e nesse caso a '
  'tela avisa que o prazo não foi confirmado em vez de afirmar um número inventado.';

COMMENT ON COLUMN cfg_municipio_nfse.consultado_em IS
  'Quando o ADN foi consultado. Linha sem data, ou antiga, é reconsultada — cache fiscal que '
  'envelhece em silêncio é pior que consulta a mais.';

-- ---------------------------------------------------------------------------------------------
-- 3. RLS, grants e o guarda-corpo do P8
-- ---------------------------------------------------------------------------------------------
-- RLS só em fiscal_config_nfse: cfg_municipio_nfse não tem id_tenant, e o P8 não se aplica a ela.

ALTER TABLE fiscal_config_nfse ENABLE ROW LEVEL SECURITY;
ALTER TABLE fiscal_config_nfse FORCE  ROW LEVEL SECURITY;

CREATE POLICY fiscal_config_nfse_rls ON fiscal_config_nfse
  USING      (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());

GRANT SELECT, INSERT, UPDATE, DELETE ON fiscal_config_nfse TO niner_app;

GRANT SELECT ON cfg_municipio_nfse TO niner_app;
-- ⚠️ niner_app PRECISA escrever aqui: quem descobre o convênio e o enviar_im é o assistente de
-- configuração, rodando como a aplicação. Não é tabela de carga como cfg_produto_ncm.
GRANT INSERT, UPDATE ON cfg_municipio_nfse TO niner_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_municipio_nfse TO niner_owner;

-- Mesmo guarda-corpo da V035/V024: falha a migration se alguma tabela com id_tenant ficou sem RLS.
-- É barato e já pegou esquecimento neste projeto mais de uma vez.
DO $$
DECLARE faltantes text;
BEGIN
  SELECT string_agg(c.relname, ', ')
    INTO faltantes
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'id_tenant'
                     AND a.attnum > 0 AND NOT a.attisdropped
  WHERE c.relkind = 'r'
    AND n.nspname = 'public'
    AND NOT c.relrowsecurity;
  IF faltantes IS NOT NULL THEN
    RAISE EXCEPTION 'P8: tabelas de tenant sem RLS habilitado: %', faltantes;
  END IF;
END $$;
