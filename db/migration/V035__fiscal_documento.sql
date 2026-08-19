-- V035 — Módulo fiscal, parte 2: TABELAS DE TENANT (2026-08-16).
-- Ver docs/MODULOFISCAL.md §7. Escopo do v1 (DF35): NFC-e ao consumidor final + NF-e de devolução
-- de venda, com cancelamento, inutilização, contingência, arquivamento e download.
--
-- Toda tabela daqui carrega id_tenant + RLS FORCE (P8). O que NÃO nasce aqui, de propósito:
-- documento_fiscal_transporte e documento_fiscal_intermediador — tabela vazia não adianta nada
-- (não há dado para migrar depois); o que é caro é remontar o XML, e disso o v1 já se protege
-- gravando finalidade/tipo_nf/indPres/indFinal/idDest no documento desde o começo.

-- =============================================================================================
-- TIPOS
-- =============================================================================================

CREATE TYPE ambiente_fiscal AS ENUM ('HOMOLOGACAO', 'PRODUCAO');

-- DF37: o Niner atende SOMENTE MEI e Simples Nacional. Não existe mais o tipo `regime_apuracao`
-- (SIMPLES/PRESUMIDO/REAL) que existia aqui: com Lucro Real e Presumido fora do produto, o CRT
-- (1/2/4) já diz tudo, e uma coluna que só aceita um valor é armadilha — alguém a preenche e
-- espera que mude alguma coisa. Ver docs/MODULOFISCAL.md §7.1.

-- Máquina de estados do §9.1. NAO_EMITIDO é estado TERMINAL para a operação que o v1 reconhece
-- mas ainda não sabe emitir (venda a contribuinte de ICMS, que exige NF-e 55): nenhum número é
-- alocado, a venda existe normalmente (F3) e o motivo fica gravado — o lojista enxerga na tela de
-- Documentos Fiscais o que ficou sem nota, em vez de descobrir na contabilidade.
CREATE TYPE situacao_documento_fiscal AS ENUM (
  'RASCUNHO', 'VALIDADO', 'ASSINADO', 'TRANSMITINDO', 'CONTINGENCIA',
  'AUTORIZADO', 'REJEITADO', 'DENEGADO', 'CANCELADO', 'NAO_EMITIDO');

-- Os dois primeiros valores são o v1; o resto já existe no enum porque acrescentar valor a ENUM
-- depois é ALTER TYPE, e o motor/relatório passam a ter o domínio completo desde já (§4.2).
CREATE TYPE tipo_operacao_fiscal AS ENUM (
  'VENDA_CONSUMIDOR', 'DEVOLUCAO_VENDA',
  'VENDA_CONTRIBUINTE', 'VENDA_NAO_PRESENCIAL', 'TRANSFERENCIA', 'DEVOLUCAO_FORNECEDOR',
  'REMESSA_CONSERTO', 'RETORNO_CONSERTO', 'BAIXA_PERDA', 'BONIFICACAO', 'COMPLEMENTAR');

CREATE TYPE tipo_destinatario_fiscal AS ENUM ('CONSUMIDOR_FINAL', 'CONTRIBUINTE', 'NAO_CONTRIBUINTE');

-- =============================================================================================
-- CONFIGURAÇÃO E CREDENCIAIS
-- =============================================================================================

-- Uma linha por EMPRESA, não por tenant: cada loja tem IE, série e numeração próprias, e o
-- ambiente (homologação/produção) é por empresa — nunca global, senão uma filial em teste derruba
-- a nota fiscal da outra.
CREATE TABLE fiscal_config_empresa (
  id_fiscal_config       integer         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant              smallint        NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa             integer         NOT NULL,
  crt                    smallint        NOT NULL DEFAULT 1,  -- 1 Simples · 2 Simples excesso de
                                         -- sublimite · 4 MEI. O 3 (Regime Normal) está FORA do
                                         -- produto por decisão de escopo (DF37) — ver o CHECK
  inscricao_estadual_st  text,
  suframa                text,
  ambiente               ambiente_fiscal NOT NULL DEFAULT 'HOMOLOGACAO',
  serie_nfce             smallint        NOT NULL DEFAULT 1,
  serie_nfe              smallint        NOT NULL DEFAULT 1,
  serie_contingencia     smallint        NOT NULL DEFAULT 9,  -- DF33: separa a fila de contingência
                                         -- da numeração normal e facilita a conferência
  csc_id                 text,                                -- identificador do CSC no portal da UF
  csc_token_cifrado      text,                                -- dado de CREDENCIAMENTO: o QR v3.00
                                         -- não usa mais CSC na montagem, mas ele não foi extinto
  emite_nfce             boolean         NOT NULL DEFAULT false,  -- gate por empresa (F12)
  emite_nfe              boolean         NOT NULL DEFAULT false,
  -- DF15 encerrada pela DF37: `equiparado_industrial` saiu junto com o cálculo de IPI. Optante do
  -- Simples recolhe IPI DENTRO do DAS (LC 123/2006 art. 13, II) e não destaca na saída — o flag
  -- não teria como ficar true em nenhuma empresa que este ERP atende.
  opcao_transferencia_tributada boolean  NOT NULL DEFAULT false,  -- Convenio ICMS 109/2024, opção
                                         -- anual (§8.4). Só usado quando a transferência entrar
  ano_opcao_transferencia smallint,
  versao_tabela_ibpt     text,                                -- rastreabilidade de F9
  contingencia_ativa     boolean         NOT NULL DEFAULT false,
  contingencia_desde     timestamptz,
  contingencia_justificativa text,
  criado_em              timestamptz     NOT NULL DEFAULT now(),
  atualizado_em          timestamptz     NOT NULL DEFAULT now(),
  CONSTRAINT fiscal_config_empresa_uk UNIQUE (id_tenant, id_empresa),
  -- DF37: MEI e Simples Nacional apenas. O CRT 3 é barrado no BANCO, não só no serviço — é a
  -- diferença entre "o produto não faz isso" e "o produto faz errado se alguém gravar 3 por fora".
  CONSTRAINT fiscal_config_empresa_crt_ck CHECK (crt IN (1, 2, 4)),
  -- FK composta (P8) — ver comentário em empresa_id_empresa_uk (V014).
  CONSTRAINT fiscal_config_empresa_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa)
);
CREATE INDEX fiscal_config_empresa_id_tenant_ix ON fiscal_config_empresa (id_tenant);

-- Certificado A1 do lojista. F7: segredo de TERCEIRO, não dado de aplicação.
--
-- DF21 revisada em 2026-08-17 (decisão do dono do produto): o `.pfx` fica no BANCO do cliente,
-- não em bucket. O bucket privado passa a ser só dos XML autorizados. Duas consequências boas:
-- o certificado entra no mesmo backup/restore do resto do tenant, e o RLS (P8) já o isola por
-- tenant sem depender de política de bucket bem configurada.
--
-- ⚠️ O arquivo é gravado CIFRADO (AES-256-GCM, comum.seguranca.SegredoCifrador), com a chave
-- FORA do banco — e não só a senha. Motivo: o PKCS12 já é protegido por senha, mas por uma senha
-- escolhida pelo lojista/AC, quase sempre curta e sujeita a força bruta OFFLINE por quem tiver o
-- arquivo. Um dump do banco entregaria exatamente isso. Cifrado, o dump sozinho é inútil.
--
-- Nenhum endpoint devolve o arquivo nem a senha — nem para ADMIN. Certificado antigo NUNCA é
-- apagado (histórico de qual certificado assinou qual nota).
CREATE TABLE fiscal_certificado (
  id_certificado       integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant            smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa           integer     NOT NULL,
  arquivo_cifrado      bytea       NOT NULL,      -- o .pfx inteiro, cifrado (nunce||AES-GCM)
  senha_cifrada        text        NOT NULL,      -- AES-256-GCM (SegredoCifrador), NUNCA em claro
  cnpj_titular         text,                      -- extraído do certificado e conferido contra a
  razao_social_titular text,                      -- empresa: certificado de outro CNPJ é recusado
  valido_de            timestamptz,
  valido_ate           timestamptz,
  impressao_digital    text,                      -- SHA-256: auditoria e deduplicação
  ativo                boolean     NOT NULL DEFAULT true,
  criado_em            timestamptz NOT NULL DEFAULT now(),
  id_usuario           integer,
  CONSTRAINT fiscal_certificado_id_uk UNIQUE (id_tenant, id_certificado),
  CONSTRAINT fiscal_certificado_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT fiscal_certificado_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario)
);
CREATE INDEX fiscal_certificado_id_tenant_ix ON fiscal_certificado (id_tenant);
-- Um certificado ATIVO por empresa. Índice parcial: os inativos continuam existindo aos montes.
CREATE UNIQUE INDEX fiscal_certificado_ativo_uk
  ON fiscal_certificado (id_tenant, id_empresa) WHERE ativo;

-- F7: todo USO do certificado deixa rastro (quem, quando, para qual documento). Vazamento de
-- certificado é alguém emitindo nota no CNPJ do lojista — sem log não há como investigar.
CREATE TABLE fiscal_certificado_uso (
  id_uso              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant           smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_certificado      integer     NOT NULL,
  id_documento_fiscal bigint,                     -- sem FK: o log sobrevive a qualquer coisa
  finalidade          text        NOT NULL,       -- 'ASSINATURA' / 'MTLS' / 'VALIDACAO'
  id_usuario          integer,
  ocorrido_em         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fiscal_certificado_uso_certificado_fk FOREIGN KEY (id_tenant, id_certificado)
    REFERENCES fiscal_certificado (id_tenant, id_certificado)
);
CREATE INDEX fiscal_certificado_uso_id_tenant_ix ON fiscal_certificado_uso (id_tenant, ocorrido_em);

-- =============================================================================================
-- CADASTRO TRIBUTÁRIO — o perfil fiscal (DF3: a inteligência tributária mora no Niner)
-- =============================================================================================

-- Em vez de espalhar CST/CFOP por coluna de produto: o produto aponta para um PERFIL reutilizável
-- ("Revenda tributada normal", "Revenda com ST retido", "Isento"). Um perfil, centenas de
-- produtos, uma correção só.
CREATE TABLE cfg_perfil_fiscal (
  id_perfil_fiscal integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant        smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome             text        NOT NULL,
  descricao        text,
  ativo            boolean     NOT NULL DEFAULT true,
  criado_em        timestamptz NOT NULL DEFAULT now(),
  atualizado_em    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT cfg_perfil_fiscal_nome_uk UNIQUE (id_tenant, nome),
  CONSTRAINT cfg_perfil_fiscal_id_uk   UNIQUE (id_tenant, id_perfil_fiscal)
);
CREATE INDEX cfg_perfil_fiscal_id_tenant_ix ON cfg_perfil_fiscal (id_tenant);

-- A regra dentro do perfil, resolvida por contexto. A mais específica ganha (UF exata > '*').
-- SEM REGRA QUE CASE => erro explícito no motor, nunca chute (F11).
CREATE TABLE cfg_perfil_fiscal_regra (
  id_regra           integer                  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint                 NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_perfil_fiscal   integer                  NOT NULL,
  crt                smallint                 NOT NULL,
  uf_destino         text                     NOT NULL DEFAULT '*',   -- '*' = qualquer UF
  tipo_destinatario  tipo_destinatario_fiscal NOT NULL,
  tipo_operacao      tipo_operacao_fiscal     NOT NULL,
  -- saída da regra
  cfop               char(4)                  NOT NULL,
  -- CSOSN é o caminho normal (CRT 1 e 4). `cst_icms` sobrevive à DF37 por causa do CRT 2 apenas:
  -- a empresa com excesso de sublimite recolhe ICMS FORA do Simples, e se ela emite com CSOSN ou
  -- com CST é divergência de mercado ainda não resolvida (F0, §8.2). Quem decide é o contador, no
  -- perfil fiscal — o ERP não chuta por ela. Ver o CHECK cfg_perfil_fiscal_regra_icms_crt_ck.
  cst_icms           char(2),                 -- CRT 2 (excesso de sublimite)
  csosn              char(3),                 -- CRT 1/2/4
  aliquota_icms      numeric(5,2)             NOT NULL DEFAULT 0,
  perc_reducao_bc    numeric(5,2)             NOT NULL DEFAULT 0,
  mva_st             numeric(5,2)             NOT NULL DEFAULT 0,
  aliquota_fcp       numeric(5,2)             NOT NULL DEFAULT 0,
  -- PIS/COFINS: com Real e Presumido fora do produto (DF37), o caso normal de todo Simples/MEI é
  -- CST 99 — o tributo está dentro do DAS, sem apuração separada, base/alíquota/valor zerados.
  -- As alíquotas abaixo só valem para os CST de tratamento próprio (04 monofásico, 06 alíquota
  -- zero) que o Simples segrega da receita: aí a alíquota é do PRODUTO mesmo. O CST 01 (saída
  -- tributada normal) NÃO existe neste produto — é o que a DF36 tentava resolver, e a DF37 apagou.
  cst_pis            char(2),
  aliquota_pis       numeric(5,2)             NOT NULL DEFAULT 0,
  cst_cofins         char(2),
  aliquota_cofins    numeric(5,2)             NOT NULL DEFAULT 0,
  -- Sem cst_ipi/aliquota_ipi: optante do Simples recolhe IPI dentro do DAS e não destaca (DF37).
  cst_ibscbs         char(3),
  cclasstrib         char(6),
  codigo_beneficio   text,                    -- cBenef, exigido por algumas UFs
  criado_em          timestamptz              NOT NULL DEFAULT now(),
  atualizado_em      timestamptz              NOT NULL DEFAULT now(),
  CONSTRAINT cfg_perfil_fiscal_regra_uk UNIQUE
    (id_tenant, id_perfil_fiscal, crt, uf_destino, tipo_destinatario, tipo_operacao),
  -- Ou CST ou CSOSN — nunca os dois, nunca nenhum.
  CONSTRAINT cfg_perfil_fiscal_regra_icms_ck CHECK (
    (cst_icms IS NOT NULL AND csosn IS NULL) OR (cst_icms IS NULL AND csosn IS NOT NULL)
  ),
  -- DF37: CRT 1 (Simples) e 4 (MEI) só emitem com CSOSN. Só o CRT 2 pode usar CST, e ainda assim
  -- por causa de uma divergência de mercado em aberto (§8.2). Sem este CHECK, um perfil mal
  -- configurado gravaria CST numa empresa do Simples e a nota seria rejeitada no caixa.
  CONSTRAINT cfg_perfil_fiscal_regra_icms_crt_ck CHECK (
    crt = 2 OR cst_icms IS NULL
  ),
  CONSTRAINT cfg_perfil_fiscal_regra_crt_ck CHECK (crt IN (1, 2, 4)),
  CONSTRAINT cfg_perfil_fiscal_regra_perfil_fk FOREIGN KEY (id_tenant, id_perfil_fiscal)
    REFERENCES cfg_perfil_fiscal (id_tenant, id_perfil_fiscal)
);
CREATE INDEX cfg_perfil_fiscal_regra_id_tenant_ix ON cfg_perfil_fiscal_regra (id_tenant);
CREATE INDEX cfg_perfil_fiscal_regra_perfil_ix    ON cfg_perfil_fiscal_regra (id_tenant, id_perfil_fiscal);

-- produto.id_perfil_fiscal fica AQUI, e não em V017, porque referencia cfg_perfil_fiscal, que só
-- existe a partir deste arquivo. Todas as outras colunas fiscais do produto (unidade_comercial,
-- origem_mercadoria, cest, ex_tipi, cnpj_fabricante) estão em V017, a migration dona da tabela.
ALTER TABLE produto ADD COLUMN id_perfil_fiscal integer;
ALTER TABLE produto ADD CONSTRAINT produto_perfil_fiscal_fk
  FOREIGN KEY (id_tenant, id_perfil_fiscal) REFERENCES cfg_perfil_fiscal (id_tenant, id_perfil_fiscal);
COMMENT ON COLUMN produto.id_perfil_fiscal IS 'Perfil fiscal do produto (DF3). Nullable: produto sem perfil nao pode ser vendido com nota, mas a regra e da aplicacao (Conformidade Fiscal), nunca NOT NULL retroativo (F12).';

-- =============================================================================================
-- NUMERAÇÃO (F4) — recurso crítico, tratado como tal
-- =============================================================================================

-- Sequencial por (empresa, modelo, série), SEM buraco, alocado sob trava e nunca reutilizado.
-- A alocação é uma transação CURTA e separada da transmissão (F2): quanto menos tempo entre
-- alocar e transmitir, menos buraco para inutilizar depois.
CREATE TABLE fiscal_numeracao (
  id_tenant       smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa      integer     NOT NULL,
  modelo          smallint    NOT NULL,
  serie           smallint    NOT NULL,
  proximo_numero  integer     NOT NULL DEFAULT 1,
  atualizado_em   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fiscal_numeracao_pk PRIMARY KEY (id_tenant, id_empresa, modelo, serie),
  CONSTRAINT fiscal_numeracao_modelo_ck CHECK (modelo IN (55, 65)),
  CONSTRAINT fiscal_numeracao_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa)
);

-- Buraco na sequência só se resolve por inutilização formal, até o 10º dia do mês seguinte.
-- Como documento_fiscal_evento (B8): grava TODA tentativa, mesmo recusada pela SEFAZ (P3) — não
-- se apaga nem se sobrescreve uma linha, só se insere outra (F11 "erro explicito, nunca chute").
CREATE TABLE fiscal_inutilizacao (
  id_inutilizacao  integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant        smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa       integer     NOT NULL,
  modelo           smallint    NOT NULL,
  serie            smallint    NOT NULL,
  ano              smallint    NOT NULL,
  numero_inicial   integer     NOT NULL,
  numero_final     integer     NOT NULL,
  justificativa    text        NOT NULL,
  autorizado       boolean     NOT NULL,          -- P3: grava a tentativa mesmo quando a SEFAZ recusa
  protocolo        text,
  status_sefaz     text,
  motivo_sefaz     text,
  -- XML da inutilização já assinado (o que foi enviado) — mesmo par indissociável de
  -- protocolo/motivo que documento_fiscal_evento.xml_evento (B8).
  xml_inutilizacao text,
  xml_objeto_bucket text,
  id_usuario       integer,
  criado_em        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fiscal_inutilizacao_faixa_ck CHECK (numero_final >= numero_inicial),
  CONSTRAINT fiscal_inutilizacao_justificativa_ck CHECK (length(justificativa) >= 15),
  CONSTRAINT fiscal_inutilizacao_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT fiscal_inutilizacao_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario)
);
CREATE INDEX fiscal_inutilizacao_id_tenant_ix ON fiscal_inutilizacao (id_tenant);

-- =============================================================================================
-- O DOCUMENTO FISCAL
-- =============================================================================================

CREATE TABLE documento_fiscal (
  id_documento_fiscal   bigint                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant             smallint                 NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa            integer                  NOT NULL,
  modelo                smallint                 NOT NULL,
  serie                 smallint,                            -- nulos até a alocação (§9.2):
  numero                integer,                             -- RASCUNHO/NAO_EMITIDO não queimam número
  chave_acesso          char(44),
  codigo_numerico       char(8),                             -- cNF: aleatório, DIFERENTE do nNF
  digito_verificador    smallint,
  tipo_operacao         tipo_operacao_fiscal     NOT NULL,
  finalidade            smallint                 NOT NULL DEFAULT 1,  -- finNFe: 1 normal · 2 complementar
                                                 -- · 3 ajuste · 4 DEVOLUCAO
  tipo_nf               smallint                 NOT NULL DEFAULT 1,  -- tpNF: 0 entrada · 1 saida
  situacao              situacao_documento_fiscal NOT NULL DEFAULT 'RASCUNHO',
  tipo_emissao          smallint                 NOT NULL DEFAULT 1,  -- tpEmis: 1 normal
                                                 -- · 9 contingencia offline (só NFC-e)
  ambiente              ambiente_fiscal          NOT NULL,   -- CONGELADO no momento da emissão
  indicador_presenca    smallint                 NOT NULL DEFAULT 1,  -- indPres
  indicador_final       smallint                 NOT NULL DEFAULT 1,  -- indFinal
  indicador_destino     smallint                 NOT NULL DEFAULT 1,  -- idDest: 1 interna
  -- Liga à operação de ORIGEM (F1: o documento fiscal é consequência, nunca causa).
  -- Exatamente uma delas é preenchida; sem CHECK de exclusividade porque operações futuras podem
  -- nascer de outras origens (transferência usa id_movimento, por exemplo).
  id_venda              integer,
  id_devolucao          integer,
  id_movimento          integer,
  id_cliente            integer,
  id_fornecedor         integer,
  data_emissao          timestamptz              NOT NULL DEFAULT now(),
  data_autorizacao      timestamptz,
  protocolo             text,
  status_sefaz          text,                    -- resposta CRUA da SEFAZ, sempre gravada
  motivo_sefaz          text,
  valor_produtos        numeric(12,2)            NOT NULL DEFAULT 0,
  valor_desconto        numeric(12,2)            NOT NULL DEFAULT 0,
  valor_frete           numeric(12,2)            NOT NULL DEFAULT 0,
  valor_seguro          numeric(12,2)            NOT NULL DEFAULT 0,
  valor_outros          numeric(12,2)            NOT NULL DEFAULT 0,
  valor_total           numeric(12,2)            NOT NULL DEFAULT 0,
  valor_troco           numeric(12,2)            NOT NULL DEFAULT 0,
  valor_icms            numeric(12,2)            NOT NULL DEFAULT 0,
  valor_icms_st         numeric(12,2)            NOT NULL DEFAULT 0,
  valor_fcp             numeric(12,2)            NOT NULL DEFAULT 0,
  valor_pis             numeric(12,2)            NOT NULL DEFAULT 0,
  valor_cofins          numeric(12,2)            NOT NULL DEFAULT 0,
  -- Sem valor_ipi: nota emitida por optante do Simples/MEI não destaca IPI (DF37). O IPI que a
  -- LOJA paga na COMPRA é outra coisa, vive no XML do fornecedor e é assunto da DF30.
  -- Reforma (§8.5). ⚠️ DF32 em aberto: se estes valores COMPÕEM o vNF em 2026. A coluna existe
  -- de qualquer forma — o emissor calcula e destaca mesmo com carga efetiva zero.
  base_ibs_cbs          numeric(12,2)            NOT NULL DEFAULT 0,  -- soma dos itens (DANFE, 2026-08-19)
  valor_ibs_uf          numeric(12,2)            NOT NULL DEFAULT 0,
  valor_ibs_mun         numeric(12,2)            NOT NULL DEFAULT 0,
  valor_cbs             numeric(12,2)            NOT NULL DEFAULT 0,
  valor_is              numeric(12,2)            NOT NULL DEFAULT 0,
  -- Detalhamento federal/estadual/municipal da Lei 12.741 (DANFE, 2026-08-19) — os 3 somam
  -- valor_total_tributos. Não faz parte do XML (vTotTrib é um valor só no XSD), só do DANFE.
  valor_trib_federal    numeric(12,2)            NOT NULL DEFAULT 0,
  valor_trib_estadual   numeric(12,2)            NOT NULL DEFAULT 0,
  valor_trib_municipal  numeric(12,2)            NOT NULL DEFAULT 0,
  valor_total_tributos  numeric(12,2)            NOT NULL DEFAULT 0,  -- vTotTrib, Lei 12.741
  xml_objeto_bucket     text,                    -- ponteiro IMUTÁVEL (F6)
  xml_hash              text,
  -- XML assinado ANTES da autorização. Lacuna descoberta no B7: o bucket guarda o XML
  -- AUTORIZADO (F6), mas entre assinar e autorizar existe uma janela em que o documento precisa
  -- sobreviver a um restart da API — e na contingência offline (§9.7) essa janela é de até 24 h,
  -- com o cupom já na mão do consumidor. Sem esta coluna, reiniciar a API durante uma queda da
  -- SEFAZ apagaria o XML de notas que o lojista já entregou: nada a transmitir, e prazo correndo.
  -- Fica no banco (e não no bucket) porque é dado ainda mutável — o bucket é só do imutável.
  xml_assinado          text,
  tentativas            integer                  NOT NULL DEFAULT 0,
  ultima_tentativa_em   timestamptz,
  ultimo_erro           text,
  motivo_nao_emissao    text,                    -- por que ficou NAO_EMITIDO (§9.1)
  id_usuario            integer,
  criado_em             timestamptz              NOT NULL DEFAULT now(),
  atualizado_em         timestamptz              NOT NULL DEFAULT now(),
  CONSTRAINT documento_fiscal_id_uk     UNIQUE (id_tenant, id_documento_fiscal),
  CONSTRAINT documento_fiscal_modelo_ck CHECK (modelo IN (55, 65)),
  CONSTRAINT documento_fiscal_tpnf_ck   CHECK (tipo_nf IN (0, 1)),
  CONSTRAINT documento_fiscal_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT documento_fiscal_venda_fk FOREIGN KEY (id_tenant, id_venda)
    REFERENCES venda (id_tenant, id_venda),
  CONSTRAINT documento_fiscal_devolucao_fk FOREIGN KEY (id_tenant, id_devolucao)
    REFERENCES venda_devolucao (id_tenant, id_devolucao),
  CONSTRAINT documento_fiscal_movimento_fk FOREIGN KEY (id_tenant, id_movimento)
    REFERENCES produto_movimento_mestre (id_tenant, id_movimento),
  CONSTRAINT documento_fiscal_cliente_fk FOREIGN KEY (id_tenant, id_cliente)
    REFERENCES cliente (id_tenant, id_cliente),
  CONSTRAINT documento_fiscal_fornecedor_fk FOREIGN KEY (id_tenant, id_fornecedor)
    REFERENCES fornecedor (id_tenant, id_fornecedor),
  CONSTRAINT documento_fiscal_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario)
);
CREATE INDEX documento_fiscal_id_tenant_ix ON documento_fiscal (id_tenant);
CREATE INDEX documento_fiscal_empresa_ix   ON documento_fiscal (id_tenant, id_empresa, data_emissao);
CREATE INDEX documento_fiscal_situacao_ix  ON documento_fiscal (id_tenant, situacao);
CREATE INDEX documento_fiscal_venda_ix     ON documento_fiscal (id_tenant, id_venda);
-- Chave de acesso é única no mundo; aqui basta única por tenant. Parcial: nula até a montagem.
CREATE UNIQUE INDEX documento_fiscal_chave_uk
  ON documento_fiscal (id_tenant, chave_acesso) WHERE chave_acesso IS NOT NULL;
-- F4: o número, uma vez alocado, é de UM documento só. Parcial pelo mesmo motivo.
CREATE UNIQUE INDEX documento_fiscal_numero_uk
  ON documento_fiscal (id_tenant, id_empresa, modelo, serie, numero) WHERE numero IS NOT NULL;

-- O item E a memória de cálculo (F9: "todo cálculo é auditável e reproduzível").
-- Esta é a CAMADA 3 das três camadas de tributo (§7.4): o que foi calculado NAQUELA nota, imutável.
-- Perfil corrigido em 2027 não muda nota de 2026 — e é daqui que a NF-e de DEVOLUÇÃO tira a
-- tributação que precisa espelhar, então não é luxo de auditoria, é insumo do v1.
CREATE TABLE documento_fiscal_item (
  id_documento_item   bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant           smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_documento_fiscal bigint        NOT NULL,
  numero_item         smallint      NOT NULL,           -- nItem
  id_variacao         integer,                          -- de onde veio (produto_barra)
  codigo_produto      text          NOT NULL,           -- cProd = produto_barra.sku
  codigo_ean          text          NOT NULL DEFAULT 'SEM GTIN',  -- cEAN. NUNCA o sku interno:
                                    -- ele tem prefixo 9 e não é registrado na GS1 — a SEFAZ
                                    -- valida o GTIN contra a base oficial e rejeita
  descricao           text          NOT NULL,           -- xProd (descrição + cor + tamanho)
  codigo_ncm          text,
  cest                text,
  ex_tipi             text,
  cfop                char(4)       NOT NULL,
  unidade_comercial   text          NOT NULL,
  quantidade          numeric(14,4) NOT NULL,
  valor_unitario      numeric(14,6) NOT NULL,           -- vUnCom aceita mais casas que dinheiro
  valor_produto       numeric(12,2) NOT NULL,
  valor_desconto      numeric(12,2) NOT NULL DEFAULT 0,
  unidade_tributavel  text,
  quantidade_trib     numeric(14,4),
  valor_unitario_trib numeric(14,6),
  origem_mercadoria   smallint      NOT NULL DEFAULT 0,
  -- ICMS
  cst_icms            char(2),
  csosn               char(3),
  base_calculo_icms   numeric(12,2) NOT NULL DEFAULT 0,
  perc_reducao_bc     numeric(5,2)  NOT NULL DEFAULT 0,
  aliquota_icms       numeric(5,2)  NOT NULL DEFAULT 0,
  valor_icms          numeric(12,2) NOT NULL DEFAULT 0,
  base_calculo_st     numeric(12,2) NOT NULL DEFAULT 0,
  mva_st              numeric(5,2)  NOT NULL DEFAULT 0,
  valor_icms_st       numeric(12,2) NOT NULL DEFAULT 0,
  base_st_retido      numeric(12,2) NOT NULL DEFAULT 0,   -- vBCSTRet (CST 60 / CSOSN 500)
  icms_st_retido      numeric(12,2) NOT NULL DEFAULT 0,   -- vICMSSTRet
  perc_st             numeric(5,2)  NOT NULL DEFAULT 0,   -- pST
  aliquota_fcp        numeric(5,2)  NOT NULL DEFAULT 0,
  valor_fcp           numeric(12,2) NOT NULL DEFAULT 0,
  perc_credito_sn     numeric(5,2)  NOT NULL DEFAULT 0,   -- pCredSN (CSOSN 101)
  valor_credito_sn    numeric(12,2) NOT NULL DEFAULT 0,   -- vCredICMSSN
  -- PIS / COFINS (sem IPI — DF37)
  cst_pis             char(2),
  base_calculo_pis    numeric(12,2) NOT NULL DEFAULT 0,
  aliquota_pis        numeric(5,2)  NOT NULL DEFAULT 0,
  valor_pis           numeric(12,2) NOT NULL DEFAULT 0,
  cst_cofins          char(2),
  base_calculo_cofins numeric(12,2) NOT NULL DEFAULT 0,
  aliquota_cofins     numeric(5,2)  NOT NULL DEFAULT 0,
  valor_cofins        numeric(12,2) NOT NULL DEFAULT 0,
  -- IBS / CBS / IS (reforma)
  cst_ibscbs          char(3),
  cclasstrib          char(6),
  base_ibscbs         numeric(12,2) NOT NULL DEFAULT 0,
  aliquota_ibs_uf     numeric(7,4)  NOT NULL DEFAULT 0,
  valor_ibs_uf        numeric(12,2) NOT NULL DEFAULT 0,
  aliquota_ibs_mun    numeric(7,4)  NOT NULL DEFAULT 0,
  valor_ibs_mun       numeric(12,2) NOT NULL DEFAULT 0,
  aliquota_cbs        numeric(7,4)  NOT NULL DEFAULT 0,
  valor_cbs           numeric(12,2) NOT NULL DEFAULT 0,
  valor_is            numeric(12,2) NOT NULL DEFAULT 0,
  -- Rastro (F9): de onde veio cada número
  id_perfil_fiscal_aplicado integer,
  id_regra_aplicada         integer,
  versao_motor              text,
  versao_tabela_ibpt        text,
  versao_tabela_cclasstrib  text,
  valor_total_tributos      numeric(12,2) NOT NULL DEFAULT 0,
  CONSTRAINT documento_fiscal_item_uk UNIQUE (id_tenant, id_documento_fiscal, numero_item),
  CONSTRAINT documento_fiscal_item_documento_fk FOREIGN KEY (id_tenant, id_documento_fiscal)
    REFERENCES documento_fiscal (id_tenant, id_documento_fiscal),
  CONSTRAINT documento_fiscal_item_variacao_fk FOREIGN KEY (id_tenant, id_variacao)
    REFERENCES produto_barra (id_tenant, id_variacao)
);
CREATE INDEX documento_fiscal_item_id_tenant_ix ON documento_fiscal_item (id_tenant);
CREATE INDEX documento_fiscal_item_documento_ix ON documento_fiscal_item (id_documento_fiscal);

-- detPag: OBRIGATÓRIO na NFC-e. Tabela própria em vez de derivar de caixa_detalhe na hora de
-- reprocessar — a nota tem que ser reproduzível byte a byte mesmo depois de o caixa ser fechado,
-- reaberto ou conferido.
CREATE TABLE documento_fiscal_pagamento (
  id_documento_pagamento bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant              smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_documento_fiscal    bigint        NOT NULL,
  id_carteira            integer,                     -- de onde veio (tipo_carteira)
  codigo_tpag            text          NOT NULL,
  valor                  numeric(12,2) NOT NULL,
  codigo_bandeira        text,
  cnpj_credenciadora     text,
  codigo_autorizacao     text,                        -- cAut
  tipo_integracao        smallint,
  CONSTRAINT documento_fiscal_pagamento_documento_fk FOREIGN KEY (id_tenant, id_documento_fiscal)
    REFERENCES documento_fiscal (id_tenant, id_documento_fiscal),
  CONSTRAINT documento_fiscal_pagamento_carteira_fk FOREIGN KEY (id_tenant, id_carteira)
    REFERENCES tipo_carteira (id_tenant, id_carteira)
);
CREATE INDEX documento_fiscal_pagamento_id_tenant_ix ON documento_fiscal_pagamento (id_tenant);
CREATE INDEX documento_fiscal_pagamento_documento_ix ON documento_fiscal_pagamento (id_documento_fiscal);

-- Eventos: cancelamento (110111) no v1; CC-e (110110) e cancelamento por substituição (110112)
-- quando chegarem. A tabela já serve aos três — o que muda é a regra, não o formato.
CREATE TABLE documento_fiscal_evento (
  id_evento           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant           smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_documento_fiscal bigint      NOT NULL,
  tipo_evento         text        NOT NULL,          -- '110111' cancelamento · '110110' CC-e
  sequencia           smallint    NOT NULL DEFAULT 1,
  -- ⚠️ Achado real cancelando uma venda de verdade (2026-08-19): cancelamento (110111) sempre usa
  -- `sequencia = 1` (é assim que a SEFAZ exige — não é como a CC-e, que incrementa a cada
  -- correção). Isso por si só está certo; o erro estava em UNIQUE não prever RETENTATIVA da MESMA
  -- sequência: uma 1ª tentativa recusada (ou, como aconteceu aqui, um bug local lendo errado uma
  -- resposta que a SEFAZ na verdade autorizou) ocupava a única linha permitida e travava qualquer
  -- tentativa seguinte com "duplicate key" — mesmo a SEFAZ tendo aceitado o reenvio idêntico.
  -- `tentativa` é o número da tentativa LOCAL (1, 2, 3…) da mesma `sequencia`; cada tentativa vira
  -- uma linha própria, preservando o rastro de todas (P3) sem bloquear a retentativa.
  tentativa            smallint   NOT NULL DEFAULT 1,
  justificativa       text        NOT NULL,
  autorizado          boolean     NOT NULL,          -- P3: grava a tentativa mesmo quando a SEFAZ
                                                      -- recusa (F11 "erro explicito, nunca chute")
  protocolo           text,
  status_sefaz        text,
  motivo_sefaz        text,
  -- XML do EVENTO já assinado (o que foi enviado) — B8, mesma razão de documento_fiscal.
  -- xml_assinado (B7): sobrevive a restart, e é o par indissociável do protocolo/motivo acima.
  xml_evento          text,
  xml_objeto_bucket   text,
  id_usuario          integer,
  criado_em           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT documento_fiscal_evento_uk UNIQUE (id_tenant, id_documento_fiscal, tipo_evento, sequencia, tentativa),
  -- Mínimo legal da justificativa de cancelamento; vale para CC-e também.
  CONSTRAINT documento_fiscal_evento_justificativa_ck CHECK (length(justificativa) >= 15),
  CONSTRAINT documento_fiscal_evento_documento_fk FOREIGN KEY (id_tenant, id_documento_fiscal)
    REFERENCES documento_fiscal (id_tenant, id_documento_fiscal),
  CONSTRAINT documento_fiscal_evento_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario)
);
CREATE INDEX documento_fiscal_evento_id_tenant_ix ON documento_fiscal_evento (id_tenant);
CREATE INDEX documento_fiscal_evento_documento_ix ON documento_fiscal_evento (id_documento_fiscal);

-- Nota referenciada. No v1: a devolução referencia a chave da NFC-e original — é o que amarra o
-- estorno fiscal ao estorno de estoque. `chave_referenciada` é texto puro (44 dígitos) e NÃO tem
-- FK para documento_fiscal de propósito: a nota referenciada pode ter sido emitida por outro
-- sistema, antes de o Niner existir.
CREATE TABLE documento_fiscal_referencia (
  id_referencia       bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant           smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_documento_fiscal bigint      NOT NULL,
  chave_referenciada  char(44)    NOT NULL,
  id_documento_referenciado bigint,                  -- preenchido quando a nota é do próprio Niner
  criado_em           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT documento_fiscal_referencia_uk UNIQUE (id_tenant, id_documento_fiscal, chave_referenciada),
  CONSTRAINT documento_fiscal_referencia_documento_fk FOREIGN KEY (id_tenant, id_documento_fiscal)
    REFERENCES documento_fiscal (id_tenant, id_documento_fiscal)
);
CREATE INDEX documento_fiscal_referencia_id_tenant_ix ON documento_fiscal_referencia (id_tenant);

-- =============================================================================================
-- F6 — O XML AUTORIZADO É IMUTÁVEL
-- =============================================================================================

-- Uma vez autorizado, o que identifica a nota perante a SEFAZ não muda mais. O documento continua
-- editável (situacao vai de AUTORIZADO para CANCELADO, tentativas aumentam), mas chave, número,
-- protocolo e ponteiro do XML são pedra. Trigger em vez de confiar no serviço: o banco é a última
-- linha, e este é o invariante que, se quebrar, quebra em silêncio.
CREATE OR REPLACE FUNCTION fn_documento_fiscal_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.chave_acesso IS NOT NULL AND NEW.chave_acesso IS DISTINCT FROM OLD.chave_acesso THEN
    RAISE EXCEPTION 'F6: chave_acesso de documento fiscal e imutavel (documento %)', OLD.id_documento_fiscal;
  END IF;
  IF OLD.numero IS NOT NULL AND (NEW.numero IS DISTINCT FROM OLD.numero
                                 OR NEW.serie IS DISTINCT FROM OLD.serie) THEN
    RAISE EXCEPTION 'F4: numero/serie alocados nao podem ser trocados (documento %)', OLD.id_documento_fiscal;
  END IF;
  IF OLD.protocolo IS NOT NULL AND NEW.protocolo IS DISTINCT FROM OLD.protocolo THEN
    RAISE EXCEPTION 'F6: protocolo de autorizacao e imutavel (documento %)', OLD.id_documento_fiscal;
  END IF;
  IF OLD.xml_objeto_bucket IS NOT NULL
     AND (NEW.xml_objeto_bucket IS DISTINCT FROM OLD.xml_objeto_bucket
          OR NEW.xml_hash IS DISTINCT FROM OLD.xml_hash) THEN
    RAISE EXCEPTION 'F6: XML autorizado nunca e reescrito (documento %)', OLD.id_documento_fiscal;
  END IF;
  -- xml_assinado E mutavel enquanto a nota nao foi autorizada (rejeitada -> corrige -> reassina),
  -- mas depois do protocolo ele E o documento que a SEFAZ autorizou: trocar significaria guardar
  -- um XML diferente do que foi validado, com o mesmo protocolo. F6 vale aqui tambem.
  IF OLD.protocolo IS NOT NULL AND NEW.xml_assinado IS DISTINCT FROM OLD.xml_assinado THEN
    RAISE EXCEPTION 'F6: XML de documento autorizado nao pode ser trocado (documento %)', OLD.id_documento_fiscal;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER documento_fiscal_imutavel_tg
  BEFORE UPDATE ON documento_fiscal
  FOR EACH ROW EXECUTE FUNCTION fn_documento_fiscal_imutavel();

-- =============================================================================================
-- RLS (P8) + GRANTS
-- V024 já rodou antes destas tabelas existirem, então o guarda-corpo dele não as alcança;
-- este arquivo garante RLS aqui, mesmo padrão (ENABLE + FORCE + policy + grants) de V024/V025.
-- =============================================================================================
DO $$
DECLARE
  t text;
  -- Tabelas com CRUD normal.
  tabelas text[] := ARRAY[
    'fiscal_config_empresa', 'fiscal_certificado',
    'cfg_perfil_fiscal', 'cfg_perfil_fiscal_regra',
    'fiscal_numeracao',
    'documento_fiscal_pagamento', 'documento_fiscal_referencia'
  ];
  -- Tabelas SEM DELETE para niner_app (F6/F7: o documento fiscal, seus itens, seus eventos, a
  -- inutilização de numeração e o log de uso do certificado NUNCA são apagados — nem um RASCUNHO
  -- que falhou, que é trilha de suporte). Acrescentar o caso a PrivilegiosNinerAppTest:
  -- TestcontainersConfiguration conecta a aplicação como superusuário e GRANT/REVOKE fica
  -- invisível para o resto da suíte.
  tabelas_sem_delete text[] := ARRAY[
    'documento_fiscal', 'documento_fiscal_item', 'documento_fiscal_evento',
    'fiscal_certificado_uso', 'fiscal_inutilizacao'
  ];
BEGIN
  FOREACH t IN ARRAY tabelas LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE  ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY %I ON %I '
      || 'USING (id_tenant = plataforma.tenant_atual()) '
      || 'WITH CHECK (id_tenant = plataforma.tenant_atual())',
      t || '_rls', t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO niner_app', t);
  END LOOP;

  FOREACH t IN ARRAY tabelas_sem_delete LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE  ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY %I ON %I '
      || 'USING (id_tenant = plataforma.tenant_atual()) '
      || 'WITH CHECK (id_tenant = plataforma.tenant_atual())',
      t || '_rls', t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON %I TO niner_app', t);
  END LOOP;
END $$;

-- Guarda-corpo (P8), mesmo padrão de V024/V025/V026/V031: falha a migration se QUALQUER tabela
-- com id_tenant tiver ficado sem RLS habilitado.
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

-- =============================================================================================
-- COMENTÁRIOS
-- =============================================================================================
COMMENT ON TABLE fiscal_config_empresa IS 'Configuracao fiscal POR EMPRESA (nao por tenant): CRT, regime, serie, ambiente, CSC, gates de emissao. RLS.';
COMMENT ON TABLE fiscal_certificado    IS 'Certificado A1 do lojista. F7: .pfx cifrado no bucket, senha por referencia no KMS, nunca devolvido por API. Certificado antigo nunca e apagado. RLS.';
COMMENT ON TABLE fiscal_certificado_uso IS 'Log de uso do certificado (quem, quando, para qual documento) — F7. Sem DELETE para niner_app. RLS.';
COMMENT ON TABLE cfg_perfil_fiscal     IS 'Perfil fiscal reutilizavel (DF3): um perfil, centenas de produtos, uma correcao so. RLS.';
COMMENT ON TABLE cfg_perfil_fiscal_regra IS 'Regra do perfil por CRT x UF destino x tipo de destinatario x tipo de operacao. Sem regra que case, o motor falha explicitamente (F11). RLS.';
COMMENT ON TABLE fiscal_numeracao      IS 'Sequencial por (empresa, modelo, serie), alocado sob trava em transacao curta e separada da transmissao (F2/F4). RLS.';
COMMENT ON TABLE fiscal_inutilizacao   IS 'Inutilizacao de faixa de numeracao (buraco na sequencia), ate o 10o dia do mes seguinte. Imutavel (F6/F7), so INSERT. RLS.';
COMMENT ON TABLE documento_fiscal      IS 'Mestre do documento fiscal (NFC-e 65 e NF-e 55). F1: e consequencia de uma operacao ja registrada (id_venda/id_devolucao/id_movimento). Nunca apagado (F6) — sem GRANT de DELETE. RLS.';
COMMENT ON TABLE documento_fiscal_item IS 'Item + MEMORIA DE CALCULO (F9, camada 3 de docs/MODULOFISCAL.md 7.4). Imutavel: perfil corrigido depois nao muda nota antiga, e a NF-e de devolucao espelha a tributacao gravada aqui. RLS.';
COMMENT ON TABLE documento_fiscal_pagamento IS 'detPag (obrigatorio na NFC-e). Tabela propria para a nota ser reproduzivel byte a byte mesmo apos o caixa ser fechado/reaberto. RLS.';
COMMENT ON TABLE documento_fiscal_evento IS 'Eventos: 110111 cancelamento (v1), 110110 CC-e e 110112 substituicao (futuros). Nunca apagado. RLS.';
COMMENT ON TABLE documento_fiscal_referencia IS 'Chave da nota referenciada (a devolucao referencia a NFC-e original). Sem FK na chave: a nota referenciada pode ser de outro sistema. RLS.';
COMMENT ON COLUMN documento_fiscal.situacao IS 'Maquina de estados do 9.1. NAO_EMITIDO = operacao reconhecida que o v1 ainda nao emite (venda a contribuinte): nenhum numero alocado, venda gravada normalmente (F3), motivo em motivo_nao_emissao.';
COMMENT ON COLUMN documento_fiscal_item.codigo_ean IS 'cEAN. SEM GTIN quando o produto nao tem GTIN real — NUNCA o produto_barra.sku, que e EAN-13 interno com prefixo 9 e nao e registrado na GS1 (a SEFAZ valida contra a base oficial e rejeita).';
