-- V102 — o documento fiscal de serviço (bloco S6 de docs/MODULONFSE.md)
--
-- Tabelas próprias, não `documento_fiscal` — é a DS9 do MODULOSERVICOS.md §5.1, e o motivo está
-- coluna por coluna lá: `chave_acesso char(44)` contra 50, `codigo_numerico`/`digito_verificador`
-- que não existem, `tipo_nf`/`indicador_presenca`/`indicador_destino` que não existem, ICMS/ST/FCP
-- onde deveria haver ISS, e um `documento_fiscal_item` com 60+ colunas de mercadoria e zero de
-- serviço. Forçar ali produziria uma tabela com metade das colunas sempre nula.
--
-- ⭐⭐ A DESCOBERTA QUE DEFINE ESTE ARQUIVO: A DPS NÃO TEM ITENS
--
-- Medido no leiaute oficial (aba `LEIAUTE DPS` do Anexo I, 2026-08-31): `serv` é **1-1**, `cServ`
-- é 1-1, `xDescServ` é 1-1 (1000 caracteres) e `vServ` é 1-1. Uma DPS carrega **um** código de
-- serviço, **uma** descrição e **um** valor. Não existe lista de itens como na NF-e.
--
-- Consequência direta, e ela é de produto, não de schema: uma venda de petshop com banho e tosa
-- (`050801`) e consulta veterinária (`050101`) **não cabe numa NFS-e só**. Então:
--
--   ⭐ UMA NFS-e POR CÓDIGO DE SERVIÇO DISTINTO DA VENDA.
--
-- A alternativa seria escolher um código "dominante" e somar tudo nele — o que declararia serviço
-- errado para parte do valor, com alíquota e local de incidência errados junto (cada `cTribNac`
-- tem os seus, ver V099/V100). Recusado.
--
-- ⚠️ Isso quebra, AQUI, a invariante que a V082 estabeleceu para `documento_fiscal` ("uma nota por
-- venda"). É deliberado e é do layout, não escolha nossa: quem ler as duas tabelas esperando a
-- mesma cardinalidade se engana. Por isso está escrito na tabela, e não só neste cabeçalho.
--
-- ⛔ Não há contingência. A NFC-e tem `tpEmis=9` porque o caixa não pode parar; a NFS-e não tem
-- equivalente e nem precisa — serviço não é vendido em fila de supermercado. O enum abaixo não
-- tem o estado, de propósito, para ninguém procurar.

CREATE TYPE situacao_nfse AS ENUM (
  'RASCUNHO',      -- montada, ainda não assinada
  'ASSINADA',      -- assinada, ainda não transmitida
  'TRANSMITINDO',  -- POST em curso (protege de dois envios simultâneos)
  'AUTORIZADA',    -- o SEFIN devolveu chave e número
  'REJEITADA',     -- recusa de negócio: corrigir o dado apontado e reenviar
  'CANCELADA',     -- evento 101101 registrado
  'NAO_EMITIDA');  -- operação reconhecida que não gera NFS-e (F3: a venda nunca some)

COMMENT ON TYPE situacao_nfse IS
  'Máquina de estados da NFS-e. ⛔ Sem CONTINGENCIA de propósito: a NFS-e não tem equivalente ao '
  'tpEmis=9 da NFC-e (docs/MODULOSERVICOS.md §5.8).';

-- ---------------------------------------------------------------------------------------------
-- 1. Numeração do nDPS
-- ---------------------------------------------------------------------------------------------
-- Espelha `fiscal_numeracao`, com duas diferenças que importam:
--   · sem `modelo` — a NFS-e não tem modelo;
--   · `bigint`, porque o nDPS do layout tem até 15 dígitos (o `integer` da NF-e não serve).
--
-- ⚠️ A numeração é por (CNPJ, série) do lado do SEFIN, e aqui por (tenant, empresa, série) —
-- que é a mesma coisa, já que a empresa É o CNPJ. Não há sequência compartilhada entre tenants.

CREATE TABLE nfse_numeracao (
  id_tenant      smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa     integer     NOT NULL,
  serie          smallint    NOT NULL,
  proximo_numero bigint      NOT NULL DEFAULT 1,
  atualizado_em  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT nfse_numeracao_pk PRIMARY KEY (id_tenant, id_empresa, serie),
  CONSTRAINT nfse_numeracao_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT nfse_numeracao_serie_ck  CHECK (serie >= 1 AND serie <= 99999),
  CONSTRAINT nfse_numeracao_numero_ck CHECK (proximo_numero >= 1
                                             AND proximo_numero <= 999999999999999)
);

COMMENT ON TABLE nfse_numeracao IS
  'Sequencial do nDPS por (empresa, série), alocado sob trava em transação curta e separada da '
  'transmissão — mesmo desenho de fiscal_numeracao (F2/F4). RLS.';

-- ---------------------------------------------------------------------------------------------
-- 2. O documento
-- ---------------------------------------------------------------------------------------------

CREATE TABLE nfse_documento (
  id_nfse                bigint          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant              smallint        NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa             integer         NOT NULL,
  -- F1: o documento é CONSEQUÊNCIA de uma operação já registrada, nunca a origem dela.
  id_venda               integer         NOT NULL,

  -- Identificação
  serie                  smallint        NOT NULL,
  numero_dps             bigint          NOT NULL,
  id_dps                 char(45)        NOT NULL,
  chave_acesso           char(50),
  numero_nfse            bigint,

  situacao               situacao_nfse   NOT NULL DEFAULT 'RASCUNHO',
  -- CONGELADOS no momento da emissão: a nota tem de ser reproduzível byte a byte depois,
  -- mesmo que a configuração da empresa mude.
  ambiente               ambiente_fiscal NOT NULL,
  codigo_municipio_ibge  integer         NOT NULL,
  competencia            date            NOT NULL,
  data_emissao           timestamptz     NOT NULL,
  data_autorizacao       timestamptz,
  data_cancelamento      timestamptz,

  -- Serviço (lembrando: UM por documento — ver o cabeçalho)
  codigo_tributacao_nacional  char(6)    NOT NULL,
  codigo_tributacao_municipal text,
  descricao_servico      text            NOT NULL,

  -- Valores
  valor_servicos         numeric(12,2)   NOT NULL,
  valor_desconto         numeric(12,2)   NOT NULL DEFAULT 0,
  base_calculo           numeric(12,2)   NOT NULL,
  aliquota_iss           numeric(5,2),
  valor_iss              numeric(12,2),
  iss_retido             boolean         NOT NULL DEFAULT false,
  -- Tributação do emitente, congelada (o pTotTribSN que o E0712 tornou obrigatório)
  opta_simples           smallint        NOT NULL,
  aliquota_simples_efetiva numeric(5,2),

  -- Retorno do SEFIN
  codigo_status          text,
  motivo_status          text,
  tentativas             smallint        NOT NULL DEFAULT 0,
  ultima_tentativa_em    timestamptz,

  -- Guarda do XML: a COLUNA GUARDA A CHAVE no MinIO (AreaPrivada.FISCAL_XML), nunca a URL e
  -- nunca o conteúdo. Área imutável, guarda legal de 5 anos.
  xml_chave              text,
  xml_cancelamento_chave text,

  id_certificado         integer,
  criado_em              timestamptz     NOT NULL DEFAULT now(),
  atualizado_em          timestamptz     NOT NULL DEFAULT now(),

  CONSTRAINT nfse_documento_id_uk UNIQUE (id_tenant, id_nfse),
  -- ⭐ A guarda de idempotência, e ela reflete a cardinalidade nova: UMA nota por (venda, código
  -- de serviço). Reenvio depois de rejeição REUSA esta linha e o mesmo nDPS — DPS recusada não
  -- queima número no SEFIN (medido: 400 com erros[] é rejeição, e o número seguiu livre).
  CONSTRAINT nfse_documento_venda_servico_uk
    UNIQUE (id_tenant, id_venda, codigo_tributacao_nacional),
  CONSTRAINT nfse_documento_id_dps_uk  UNIQUE (id_tenant, id_dps),
  CONSTRAINT nfse_documento_chave_uk   UNIQUE (id_tenant, chave_acesso),
  CONSTRAINT nfse_documento_numeracao_uk UNIQUE (id_tenant, id_empresa, serie, numero_dps),

  CONSTRAINT nfse_documento_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT nfse_documento_venda_fk FOREIGN KEY (id_tenant, id_venda)
    REFERENCES venda (id_tenant, id_venda),
  CONSTRAINT nfse_documento_ctribnac_fk FOREIGN KEY (codigo_tributacao_nacional)
    REFERENCES cfg_servico_lc116 (codigo),

  CONSTRAINT nfse_documento_id_dps_ck CHECK (id_dps ~ '^DPS[0-9]{42}$'),
  CONSTRAINT nfse_documento_chave_ck  CHECK (chave_acesso IS NULL OR chave_acesso ~ '^[0-9]{50}$'),
  -- opSimpNac: 1 não optante · 2 MEI · 3 ME/EPP. O 1 não é atendido pelo produto (DF37), mas o
  -- domínio do campo é do layout, não nosso.
  CONSTRAINT nfse_documento_opsimp_ck CHECK (opta_simples IN (1, 2, 3)),
  CONSTRAINT nfse_documento_valores_ck CHECK (valor_servicos >= 0 AND valor_desconto >= 0
                                              AND base_calculo >= 0),
  CONSTRAINT nfse_documento_aliq_ck   CHECK (aliquota_iss IS NULL
                                             OR (aliquota_iss >= 0 AND aliquota_iss <= 5)),
  -- ⭐ A invariante que impede o pior estado possível: nota AUTORIZADA sem chave, ou CANCELADA sem
  -- chave, é uma nota que existe na prefeitura e que o ERP não sabe identificar.
  CONSTRAINT nfse_documento_autorizada_tem_chave_ck
    CHECK (situacao NOT IN ('AUTORIZADA', 'CANCELADA')
           OR (chave_acesso IS NOT NULL AND numero_nfse IS NOT NULL))
);

CREATE INDEX nfse_documento_id_tenant_ix ON nfse_documento (id_tenant, id_empresa, criado_em DESC);
CREATE INDEX nfse_documento_venda_ix     ON nfse_documento (id_tenant, id_venda);
-- Índice da fila de reprocessamento: a NFS-e pendente precisa ser visível e reprocessável, que é
-- a consequência de tela da DS13 (nota que ninguém emitiu por esquecimento é pior que nota que
-- falhou, porque não aparece em lugar nenhum).
CREATE INDEX nfse_documento_pendente_ix  ON nfse_documento (id_tenant, situacao, ultima_tentativa_em)
  WHERE situacao IN ('RASCUNHO', 'ASSINADA', 'TRANSMITINDO', 'REJEITADA');

COMMENT ON TABLE nfse_documento IS
  'Mestre da NFS-e. ⚠️ CARDINALIDADE DIFERENTE da documento_fiscal: uma nota por (venda, código de '
  'serviço), não uma por venda — a DPS carrega UM cServ (leiaute 1-1), então venda com dois '
  'serviços de códigos distintos gera duas notas. Ver o cabeçalho da V102. RLS.';

COMMENT ON COLUMN nfse_documento.id_dps IS
  'Identificador da DPS, 45 caracteres = "DPS" + cMun(7) + tpInsc(1) + CNPJ(14) + série(5) + '
  'nDPS(15). Determinístico, e é por ele que se recupera nota órfã (GET /dps/{id}) depois de um '
  'timeout — reenviar cego daria E0014.';

COMMENT ON COLUMN nfse_documento.chave_acesso IS
  '50 dígitos. ⛔ NÃO é derivável do id_dps, ao contrário do que a documentação do finance-v '
  'afirma: leva o nNFSe atribuído pelo SEFIN e um código numérico ALEATÓRIO de 9 posições '
  '(leiaute oficial, conferido campo a campo em docs/MODULONFSE.md §2.7). Quem tentar calculá-la '
  'implementa um caminho que não existe.';

COMMENT ON COLUMN nfse_documento.numero_nfse IS
  'nNFSe — o número da nota NA PREFEITURA, atribuído pelo SEFIN e devolvido na resposta. Não há '
  'consulta de "próximo número": até ser autorizada, a nota só tem o nosso numero_dps.';

COMMENT ON COLUMN nfse_documento.motivo_status IS
  'Motivo da recusa no formato "E0240 — <descrição do SEFIN>", com o código fiscal NO INÍCIO. ⚠️ O '
  'codigo_status é HTTP (400 para toda recusa) e nunca identifica a causa; o código que o usuário '
  'vê no portal vem dentro de erros[] (ou de "erro", singular, na resposta de EVENTO — o parser '
  'precisa cobrir as duas). Quando houver Complemento, ele entra aqui: no E1235 é ele que diz qual '
  'elemento falta.';

COMMENT ON COLUMN nfse_documento.xml_chave IS
  'Chave do XML autorizado no MinIO (AreaPrivada.FISCAL_XML), nunca URL e nunca o conteúdo. ⚠️ O '
  'XML que se guarda é o que o SEFIN DEVOLVE, não o que enviamos — é ele que tem a assinatura da '
  'Sefin e vale como prova fiscal pelos 5 anos.';

COMMENT ON COLUMN nfse_documento.aliquota_simples_efetiva IS
  'pTotTribSN congelado no momento da emissão. Congelar é obrigatório: o percentual muda com o '
  'RBT12 da empresa, e a nota de ontem tem de continuar dizendo o que disse (mesma razão da V088).';

-- ---------------------------------------------------------------------------------------------
-- 3. Os itens — que existem para NÓS, não para o XML
-- ---------------------------------------------------------------------------------------------
-- ⚠️ Esta tabela NÃO vira XML. A DPS não tem lista de itens (cabeçalho). Ela existe para
-- responder, em auditoria (P3), QUAIS linhas da venda entraram nesta nota — sem isso não há como
-- saber se um serviço da venda ficou de fora, nem reconstruir a descrição agregada que foi
-- enviada em xDescServ.

CREATE TABLE nfse_documento_item (
  id_nfse_item    bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant       smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_nfse         bigint        NOT NULL,
  numero_item     smallint      NOT NULL,
  id_variacao     integer       NOT NULL,
  descricao       text          NOT NULL,
  quantidade      numeric(14,3) NOT NULL,
  valor_unitario  numeric(12,2) NOT NULL,
  valor_desconto  numeric(12,2) NOT NULL DEFAULT 0,
  valor_total     numeric(12,2) NOT NULL,
  CONSTRAINT nfse_documento_item_uk UNIQUE (id_tenant, id_nfse, numero_item),
  CONSTRAINT nfse_documento_item_nfse_fk FOREIGN KEY (id_tenant, id_nfse)
    REFERENCES nfse_documento (id_tenant, id_nfse),
  CONSTRAINT nfse_documento_item_valores_ck CHECK (quantidade > 0 AND valor_unitario >= 0
                                                   AND valor_desconto >= 0 AND valor_total >= 0)
);

CREATE INDEX nfse_documento_item_id_tenant_ix ON nfse_documento_item (id_tenant, id_nfse);

COMMENT ON TABLE nfse_documento_item IS
  'Quais linhas da venda compuseram esta NFS-e. ⚠️ NÃO vira XML — a DPS não tem lista de itens. '
  'Existe para auditoria (P3) e para reconstruir o xDescServ agregado. RLS.';

-- ---------------------------------------------------------------------------------------------
-- 4. Eventos
-- ---------------------------------------------------------------------------------------------

CREATE TABLE nfse_documento_evento (
  id_nfse_evento  bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant       smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_nfse         bigint      NOT NULL,
  tipo_evento     char(6)     NOT NULL,
  sequencia       smallint    NOT NULL DEFAULT 1,
  id_pedido       char(59)    NOT NULL,
  data_evento     timestamptz NOT NULL,
  motivo_codigo   smallint,
  motivo_texto    text,
  situacao        text        NOT NULL,
  codigo_status   text,
  motivo_status   text,
  xml_chave       text,
  criado_em       timestamptz NOT NULL DEFAULT now(),
  id_usuario      integer,
  CONSTRAINT nfse_documento_evento_uk UNIQUE (id_tenant, id_nfse, tipo_evento, sequencia),
  CONSTRAINT nfse_documento_evento_nfse_fk FOREIGN KEY (id_tenant, id_nfse)
    REFERENCES nfse_documento (id_tenant, id_nfse),
  CONSTRAINT nfse_documento_evento_id_ck CHECK (id_pedido ~ '^PRE[0-9]{56}$'),
  -- xMotivo do evento 101101 tem faixa fixa no XSD, e mensagem curta demais volta como erro de
  -- schema depois de o operador já ter digitado.
  CONSTRAINT nfse_documento_evento_motivo_ck
    CHECK (motivo_texto IS NULL OR char_length(motivo_texto) BETWEEN 15 AND 255)
);

CREATE INDEX nfse_documento_evento_id_tenant_ix ON nfse_documento_evento (id_tenant, id_nfse);

COMMENT ON TABLE nfse_documento_evento IS
  'Eventos da NFS-e — no v1 só o 101101 (cancelamento pelo emitente). Nunca apagado (F6): sem '
  'GRANT de DELETE. RLS.';

COMMENT ON COLUMN nfse_documento_evento.id_pedido IS
  'Id do pedido de registro, 59 caracteres = "PRE" + chave(50) + tipoEvento(6), pattern '
  'PRE[0-9]{56}. ⚠️ O nSeqEvento NÃO entra no Id (vai só no corpo) — conferido no código do '
  'finance-v, que emite em produção; o esboço do MAPA.md diverge.';

-- ---------------------------------------------------------------------------------------------
-- 5. RLS, grants e o guarda-corpo do P8
-- ---------------------------------------------------------------------------------------------
-- F6: documento fiscal não se apaga. `nfse_documento` e os eventos não recebem DELETE; os itens
-- recebem, porque uma nota em RASCUNHO ainda é remontada antes de existir para a prefeitura.

DO $$
DECLARE
  t text;
  com_delete text[] := ARRAY['nfse_numeracao', 'nfse_documento_item'];
  sem_delete text[] := ARRAY['nfse_documento', 'nfse_documento_evento'];
BEGIN
  FOREACH t IN ARRAY com_delete LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE  ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY %I ON %I USING (id_tenant = plataforma.tenant_atual()) '
      'WITH CHECK (id_tenant = plataforma.tenant_atual())', t || '_rls', t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO niner_app', t);
  END LOOP;

  FOREACH t IN ARRAY sem_delete LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE  ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY %I ON %I USING (id_tenant = plataforma.tenant_atual()) '
      'WITH CHECK (id_tenant = plataforma.tenant_atual())', t || '_rls', t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON %I TO niner_app', t);
  END LOOP;
END $$;

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
