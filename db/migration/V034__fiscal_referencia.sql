-- V034 — Módulo fiscal, parte 1: TABELAS DE REFERÊNCIA NACIONAIS (2026-08-16).
-- Ver docs/MODULOFISCAL.md §7.4 (camada 2 das três camadas de tributo).
--
-- Todas GLOBAIS: iguais para todos os tenants, SEM id_tenant e SEM RLS — mesma exceção
-- documentada de cfg_produto_ncm e cfg_banco (P8 não se aplica a tabela sem id_tenant).
-- niner_app só LÊ; a carga/atualização é feita por script rodando como niner_owner, fora do
-- tráfego da aplicação.
--
-- Estas tabelas são VERSIONADAS de propósito: o que muda com o tempo (alíquota, IBPT,
-- classificação da reforma) tem vigência, e o documento fiscal grava QUAL versão usou (F9).
-- Sem isso, uma nota de 2026 fica impossível de reproduzir em 2029.

-- ---------------------------------------------------------------------------------------------
-- cfg_uf_autorizador — F10: "a UF é dado, não código".
-- Uma linha por (UF, modelo, ambiente). É o que permite a UF piloto virar Brasil sem reescrever
-- o módulo: endpoint, prazo legal, alíquota interna e particularidade de validação são LINHA,
-- nunca `if (uf == 'PR')`.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE cfg_uf_autorizador (
  uf                        char(2)      NOT NULL,
  modelo                    smallint     NOT NULL,   -- 55 (NF-e) ou 65 (NFC-e)
  ambiente                  smallint     NOT NULL,   -- 1 producao · 2 homologacao (tpAmb do XML)
  codigo_uf_ibge            smallint     NOT NULL,   -- cUF (PR = 41)
  autorizador               text         NOT NULL,   -- 'PROPRIO' / 'SVRS' / 'SVAN' ... (informativo)
  url_autorizacao           text,                    -- NFeAutorizacao4
  url_ret_autorizacao       text,                    -- NFeRetAutorizacao4
  url_status_servico        text,                    -- NFeStatusServico4
  url_recepcao_evento       text,                    -- NFeRecepcaoEvento4 (cancelamento, CC-e)
  url_inutilizacao          text,                    -- NFeInutilizacao4
  url_consulta_protocolo    text,                    -- NFeConsultaProtocolo4
  -- ⚠️ As duas URLs abaixo NÃO são o host do webservice — no PR o webservice é
  -- `nfce.sefa.pr.gov.br` e a consulta pública é `www.fazenda.pr.gov.br`. Confundir as duas custou
  -- um `cStat 878` no B0, com a própria SEFAZ devolvendo o endereço certo na mensagem de erro.
  url_qrcode                text,                    -- base do QR Code impresso no DANFCE
  url_consulta_publica      text,                    -- consulta da chave pelo consumidor
  prazo_cancelamento_min    integer,                 -- NFC-e 30 min · NF-e 24 h = 1440 min
  prazo_contingencia_horas  integer,                 -- transmissão após cessar a falha (PR: 24 h)
  aliquota_interna          numeric(5,2),            -- ICMS interno modal da UF
  aliquota_fcp              numeric(5,2),            -- adicional de combate à pobreza (FECOP)
  exige_cbenef              boolean      NOT NULL DEFAULT false,
  permite_extemporaneo      boolean      NOT NULL DEFAULT false,  -- cancelamento fora do prazo por
                                                     -- via administrativa. O PR NÃO oferece.
  observacao                text,
  atualizado_em             timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT cfg_uf_autorizador_pk PRIMARY KEY (uf, modelo, ambiente),
  CONSTRAINT cfg_uf_autorizador_modelo_ck   CHECK (modelo   IN (55, 65)),
  CONSTRAINT cfg_uf_autorizador_ambiente_ck CHECK (ambiente IN (1, 2))
);
COMMENT ON TABLE cfg_uf_autorizador IS 'Endpoints e regras por UF x modelo x ambiente (F10). GLOBAL, sem RLS. Carga por script.';

-- ---------------------------------------------------------------------------------------------
-- cfg_cfop / cfg_cest — códigos de operação e de substituição tributária.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE cfg_cfop (
  codigo_cfop   char(4)  PRIMARY KEY,
  descricao     text     NOT NULL,
  entrada_saida char(1)  NOT NULL,   -- 'E' entrada · 'S' saida (1º dígito do código)
  ambito        text     NOT NULL,   -- 'INTERNO' / 'INTERESTADUAL' / 'EXTERIOR'
  CONSTRAINT cfg_cfop_entrada_saida_ck CHECK (entrada_saida IN ('E', 'S'))
);
COMMENT ON TABLE cfg_cfop IS 'CFOP (Convenio SINIEF s/n). GLOBAL, sem RLS. V034 semeia apenas o subconjunto usado pelo v1; a carga completa e feita por script, igual ao NCM.';

CREATE TABLE cfg_cest (
  codigo_cest text PRIMARY KEY,
  codigo_ncm  text NOT NULL,      -- sem FK: o CEST casa com NCM por PREFIXO, não por igualdade
  descricao   text NOT NULL,
  segmento    text
);
COMMENT ON TABLE cfg_cest IS 'CEST x NCM (Convenio ICMS 142/2018). GLOBAL, sem RLS. Carga por script.';

-- ---------------------------------------------------------------------------------------------
-- cfg_cst_icms / cfg_csosn — o domínio de tributação do ICMS.
-- As colunas `exige_*` existem porque cada código determina quais campos são OBRIGATÓRIOS e quais
-- são PROIBIDOS no XML: campo a mais rejeita tanto quanto campo a menos (§8.2). Ter isso em tabela
-- deixa o motor validar ANTES de assinar e transmitir — erro pego no motor custa milissegundos,
-- erro pego na SEFAZ custa a venda.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE cfg_cst_icms (
  codigo_cst        char(2)  PRIMARY KEY,
  descricao         text     NOT NULL,
  exige_valor_icms  boolean  NOT NULL DEFAULT false,  -- vBC/pICMS/vICMS
  exige_reducao_bc  boolean  NOT NULL DEFAULT false,  -- pRedBC
  exige_grupo_st    boolean  NOT NULL DEFAULT false,  -- vBCST/pMVAST/pICMSST/vICMSST
  exige_st_retido   boolean  NOT NULL DEFAULT false   -- vBCSTRet/vICMSSTRet/pST
);
COMMENT ON TABLE cfg_cst_icms IS 'CST do ICMS (CRT 3, regime normal) + quais campos cada codigo exige. GLOBAL, sem RLS.';

CREATE TABLE cfg_csosn (
  codigo_csosn      char(3)  PRIMARY KEY,
  descricao         text     NOT NULL,
  exige_pcred_sn    boolean  NOT NULL DEFAULT false,  -- pCredSN/vCredICMSSN
  exige_grupo_st    boolean  NOT NULL DEFAULT false,
  exige_st_retido   boolean  NOT NULL DEFAULT false
);
COMMENT ON TABLE cfg_csosn IS 'CSOSN (CRT 1/2/4, Simples Nacional) + quais campos cada codigo exige. GLOBAL, sem RLS.';

-- ---------------------------------------------------------------------------------------------
-- Reforma tributária (NT 2025.002-RTC) — IBS/CBS/IS.
-- `ind_gibscbs` é a coluna que sustenta a estratégia da DF4 ("motor pronto para todos os regimes
-- desde o v1"): a rejeição 1021 dispara pelo CST DO ITEM, não pelo CRT do emitente. O gate é por
-- item, não `if (crt == SIMPLES)`. ⚠️ A condição exata da regra UB13-20 tem que ser conferida no
-- texto oficial da NT antes de o motor confiar nesta coluna (F0).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE cfg_cst_ibscbs (
  codigo_cst   char(3)  PRIMARY KEY,
  descricao    text     NOT NULL,
  ind_gibscbs  boolean  NOT NULL DEFAULT true,   -- o item com este CST leva o grupo IBS/CBS?
  versao_nt    text
);
COMMENT ON TABLE cfg_cst_ibscbs IS 'CST do IBS/CBS (3 digitos, NT 2025.002). GLOBAL, sem RLS. Carga por script.';

CREATE TABLE cfg_cclasstrib (
  codigo_cclasstrib   char(6)  PRIMARY KEY,
  descricao           text     NOT NULL,
  codigo_cst          char(3),                    -- CST compatível (a incompatibilidade é rejeição)
  exige_trib_regular  boolean  NOT NULL DEFAULT false,  -- dispara o grupo gTribRegular
  permite_cred_pres   boolean  NOT NULL DEFAULT false,  -- dispara gIBSCredPres
  -- Três colunas acrescentadas em 2026-08-17, ao carregar a planilha oficial do IT 2025.002:
  -- vinham no arquivo e não tinham onde morar, e as duas primeiras são necessárias antes do
  -- motor tributário (B4), não depois.
  ind_nfce            boolean  NOT NULL DEFAULT true,   -- o código vale em NFC-e (modelo 65)?
                                          -- Sem isto a tela ofereceria ao lojista códigos que a
                                          -- SEFAZ rejeita na NFC-e (ex.: 010001/010002 são NFSe).
  perc_reducao_ibs    numeric(5,2) NOT NULL DEFAULT 0,  -- pRedIBS: % de redução da alíquota
  perc_reducao_cbs    numeric(5,2) NOT NULL DEFAULT 0,  -- pRedCBS: idem para a CBS. Sem os dois o
                                          -- motor saberia que a alíquota é reduzida, mas não quanto.
  versao_it           text,                       -- versão do Informe Técnico importada (F9)
  vigencia_inicio     date,
  vigencia_fim        date
);
COMMENT ON TABLE cfg_cclasstrib IS 'Classificacao tributaria do IBS/CBS (6 digitos, ~173 codigos do IT 2025.002 RFB/CGIBS). GLOBAL, sem RLS. Carga por script a partir do portal SVRS.';

-- ---------------------------------------------------------------------------------------------
-- cfg_ibpt — Lei 12.741/2012 (valor aproximado dos tributos no cupom, `vTotTrib`).
-- ⚠️ cfg_produto_ncm.aliquota_ibpt NÃO serve: é UMA coluna, e o IBPT tem QUATRO alíquotas por NCM,
-- POR UF e POR vigência. Aquela coluna fica onde está (não quebra nada) e deixa de ser usada.
-- A tabela é licenciada (DF16) — nasce vazia.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE cfg_ibpt (
  codigo_ncm        text          NOT NULL,
  uf                char(2)       NOT NULL,
  ex_tipi           text          NOT NULL DEFAULT '',
  vigencia_inicio   date          NOT NULL,
  vigencia_fim      date,
  versao            text          NOT NULL,        -- gravada no documento fiscal (F9)
  aliq_nacional_federal numeric(5,2) NOT NULL DEFAULT 0,
  aliq_importado_federal numeric(5,2) NOT NULL DEFAULT 0,
  aliq_estadual     numeric(5,2)  NOT NULL DEFAULT 0,
  aliq_municipal    numeric(5,2)  NOT NULL DEFAULT 0,
  CONSTRAINT cfg_ibpt_pk PRIMARY KEY (codigo_ncm, uf, ex_tipi, vigencia_inicio)
);
COMMENT ON TABLE cfg_ibpt IS 'Tabela IBPT por NCM x UF x vigencia (Lei 12.741/2012). GLOBAL, sem RLS. Licenciada (DF16) — nasce vazia.';

-- =============================================================================================
-- GRANTS — mesmo padrão de cfg_produto_ncm: aplicação só lê, dono carrega.
-- =============================================================================================
DO $$
DECLARE
  t text;
  tabelas text[] := ARRAY[
    'cfg_uf_autorizador', 'cfg_cfop', 'cfg_cest', 'cfg_cst_icms', 'cfg_csosn',
    'cfg_cst_ibscbs', 'cfg_cclasstrib', 'cfg_ibpt'
  ];
BEGIN
  FOREACH t IN ARRAY tabelas LOOP
    EXECUTE format('GRANT SELECT ON %I TO niner_app', t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO niner_owner', t);
  END LOOP;
END $$;

-- =============================================================================================
-- SEED — só o que é estável e verificável. O resto é carga por script (igual aos 10.515 NCMs).
-- =============================================================================================

-- CST do ICMS (CRT 3). Fonte: MOC / tabela nacional.
INSERT INTO cfg_cst_icms (codigo_cst, descricao, exige_valor_icms, exige_reducao_bc, exige_grupo_st, exige_st_retido) VALUES
  ('00', 'Tributada integralmente',                                        true,  false, false, false),
  ('10', 'Tributada e com cobranca do ICMS por substituicao tributaria',   true,  false, true,  false),
  ('20', 'Com reducao de base de calculo',                                 true,  true,  false, false),
  ('30', 'Isenta/nao tributada e com cobranca do ICMS por ST',             false, false, true,  false),
  ('40', 'Isenta',                                                         false, false, false, false),
  ('41', 'Nao tributada',                                                  false, false, false, false),
  ('50', 'Suspensao',                                                      false, false, false, false),
  ('51', 'Diferimento',                                                    false, false, false, false),
  ('60', 'ICMS cobrado anteriormente por substituicao tributaria',         false, false, false, true),
  ('70', 'Com reducao de base de calculo e cobranca do ICMS por ST',       true,  true,  true,  false),
  ('90', 'Outras',                                                         false, false, false, false);

-- CSOSN (CRT 1/2/4). 102 e 500 são os dois casos dominantes do varejo (confeccao/calcado).
INSERT INTO cfg_csosn (codigo_csosn, descricao, exige_pcred_sn, exige_grupo_st, exige_st_retido) VALUES
  ('101', 'Tributada pelo Simples Nacional com permissao de credito',      true,  false, false),
  ('102', 'Tributada pelo Simples Nacional sem permissao de credito',      false, false, false),
  ('103', 'Isencao do ICMS no Simples Nacional para faixa de receita',     false, false, false),
  ('201', 'Tributada com permissao de credito e com cobranca do ICMS por ST', true, true, false),
  ('202', 'Tributada sem permissao de credito e com cobranca do ICMS por ST', false, true, false),
  ('203', 'Isencao do ICMS para faixa de receita e com cobranca por ST',   false, true,  false),
  ('300', 'Imune',                                                         false, false, false),
  ('400', 'Nao tributada pelo Simples Nacional',                           false, false, false),
  ('500', 'ICMS cobrado anteriormente por ST ou por antecipacao',          false, false, true),
  ('900', 'Outros',                                                        false, false, false);

-- CFOP: só o subconjunto que o v1 usa (docs/MODULOFISCAL.md §4.1). A carga completa é script.
INSERT INTO cfg_cfop (codigo_cfop, descricao, entrada_saida, ambito) VALUES
  ('5102', 'Venda de mercadoria adquirida ou recebida de terceiros',                  'S', 'INTERNO'),
  ('5405', 'Venda de mercadoria adquirida de terceiros, ST, na condicao de substituido', 'S', 'INTERNO'),
  ('1202', 'Devolucao de venda de mercadoria adquirida ou recebida de terceiros',     'E', 'INTERNO'),
  ('1411', 'Devolucao de venda de mercadoria sujeita a ST, na condicao de substituido','E', 'INTERNO'),
  ('2202', 'Devolucao de venda de mercadoria adquirida ou recebida de terceiros',     'E', 'INTERESTADUAL');

-- cfg_uf_autorizador — UF PILOTO: PARANÁ.
-- ✅ Confirmado no portal oficial Sped-PR: o PR tem autorizador PRÓPRIO para NFC-e, NÃO usa SVRS
--    (sped.fazenda.pr.gov.br/NFCe/Pagina/Web-Services-NFC-e).
-- ⚠️ Alíquota interna 19,5% (Lei 21.850/2023) e FECOP 2% (Lei 11.580/1996 art. 14-A) vieram de
--    fonte secundária — conferir no RICMS-PR na F0 antes de o motor confiar nelas.
-- ⚠️ Os endpoints de NF-e (modelo 55) NÃO foram confirmados em fonte oficial: as linhas nascem com
--    URL NULA de propósito, para a F0 preenchê-las. Motor e emissão devem falhar explicitamente
--    quando a URL for nula, nunca chutar um domínio.
INSERT INTO cfg_uf_autorizador (
  uf, modelo, ambiente, codigo_uf_ibge, autorizador,
  url_autorizacao, url_ret_autorizacao, url_status_servico,
  url_recepcao_evento, url_inutilizacao, url_consulta_protocolo,
  url_qrcode, url_consulta_publica,
  prazo_cancelamento_min, prazo_contingencia_horas, aliquota_interna, aliquota_fcp,
  permite_extemporaneo, observacao
) VALUES
  ('PR', 65, 1, 41, 'PROPRIO',
   'https://nfce.sefa.pr.gov.br/nfce/NFeAutorizacao4',
   'https://nfce.sefa.pr.gov.br/nfce/NFeRetAutorizacao4',
   'https://nfce.sefa.pr.gov.br/nfce/NFeStatusServico4',
   'https://nfce.sefa.pr.gov.br/nfce/NFeRecepcaoEvento4',
   'https://nfce.sefa.pr.gov.br/nfce/NFeInutilizacao4',
   'https://nfce.sefa.pr.gov.br/nfce/NFeConsultaProtocolo4',
   'http://www.fazenda.pr.gov.br/nfce/qrcode',
   'http://www.fazenda.pr.gov.br/nfce/consulta',
   30, 24, 19.50, 2.00, false,
   'NPF 100/2014 consolidada. Credenciamento de producao pelo Portal Receita/PR.'),
  ('PR', 65, 2, 41, 'PROPRIO',
   'https://homologacao.nfce.sefa.pr.gov.br/nfce/NFeAutorizacao4',
   'https://homologacao.nfce.sefa.pr.gov.br/nfce/NFeRetAutorizacao4',
   'https://homologacao.nfce.sefa.pr.gov.br/nfce/NFeStatusServico4',
   'https://homologacao.nfce.sefa.pr.gov.br/nfce/NFeRecepcaoEvento4',
   'https://homologacao.nfce.sefa.pr.gov.br/nfce/NFeInutilizacao4',
   'https://homologacao.nfce.sefa.pr.gov.br/nfce/NFeConsultaProtocolo4',
   -- ⚠️ Mesmas URLs de consulta da producao, de proposito: no PR o endereco de consulta publica
   -- NAO tem variante de homologacao, e o que distingue os ambientes e o tpAmb dentro do QR.
   -- Confirmado no B0 pela propria SEFAZ, na mensagem do cStat 878.
   'http://www.fazenda.pr.gov.br/nfce/qrcode',
   'http://www.fazenda.pr.gov.br/nfce/consulta',
   30, 24, 19.50, 2.00, false,
   'Credenciamento de homologacao no PR e AUTOMATICO para contribuinte ativo no cadastro de ICMS.'),
  ('PR', 55, 1, 41, 'PROPRIO',
   NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
   1440, NULL, 19.50, 2.00, false,
   'ENDPOINTS A CONFIRMAR NA F0 (fonte oficial Sped-PR). Prazo de cancelamento 24h = Ajuste SINIEF 07/2005.'),
  ('PR', 55, 2, 41, 'PROPRIO',
   NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
   1440, NULL, 19.50, 2.00, false,
   'ENDPOINTS A CONFIRMAR NA F0 (fonte oficial Sped-PR).');
