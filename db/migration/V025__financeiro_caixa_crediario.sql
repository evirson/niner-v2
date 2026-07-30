-- V025 — financeiro (parcial): crediário + caixa antecipados da Fase 2 (revisão de Q5/ADR-010,
-- decidida em 2026-07-16). Entram: tipo_carteira, contas_receber(_detalhe), caixa_mestre/
-- caixa_detalhe. NÃO entram ainda: contas_pagar, conta_corrente(_movimento) — seguem fora do
-- v1 (§3.3.7). Todas as tabelas nascem com id_tenant (P8); FKs entre tabelas de domínio são
-- compostas (id_tenant, id_x), nunca simples (achado de 2026-07-16, RLS não protege
-- integridade referencial).
--
-- 2026-07-28 (pedido do dono do produto): `moeda` foi absorvida por `tipo_carteira` e
-- `moeda_detalhe` foi removida. Motivo: a mesma "moeda" (ex.: bandeira "HIPER") podia estar
-- vinculada a mais de um tipo_carteira (Débito e Crédito), mas prazo/parcelas/taxa SÃO
-- diferentes entre essas categorias — o N:N permitia (e escondia) taxa/prazo errados
-- compartilhados entre linhas que na prática são configurações distintas. Cada combinação
-- categoria×bandeira agora é uma linha própria de `tipo_carteira` (nome_carteira pode se
-- repetir entre categorias — ex.: "HIPER" em CARTAO_DEBITO e "HIPER" em CARTAO_CREDITO — mas
-- não dentro da mesma categoria).

-- Natureza do lançamento de caixa (legado: RV/RP/DC/CC/TR).
CREATE TYPE tipo_operacao_caixa AS ENUM
  ('RECEBIMENTO_VENDA', 'RECEBIMENTO_PARCELA_CREDIARIO', 'DEBITO_CAIXA', 'CREDITO_CAIXA', 'TROCO');

-- Categoria fixa do tipo de carteira (2026-07-23, pedido do dono do produto — histórico do
-- cliente precisa separar "crediário" de cartão/à vista programaticamente; nome_carteira é
-- texto livre e não serve pra isso).
CREATE TYPE categoria_carteira AS ENUM ('AVISTA', 'CARTAO_DEBITO', 'CARTAO_CREDITO', 'CREDIARIO');

-- tipo_carteira (config): forma de pagamento — categoria, prazo/parcelas/taxa, e (2026-07-28,
-- absorvido de `moeda`) desconto/acréscimo. Cada linha já é uma combinação específica
-- categoria×bandeira (ex.: "HIPER"/CARTAO_DEBITO e "HIPER"/CARTAO_CREDITO são duas linhas).
CREATE TABLE tipo_carteira (
  id_carteira          integer             GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant            smallint            NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome_carteira        text                NOT NULL,
  categoria_carteira   categoria_carteira  NOT NULL DEFAULT 'AVISTA',  -- 2026-07-23
  prazo_pagamento      integer             NOT NULL,      -- dias entre parcelas
  pc_minima            integer             NOT NULL,      -- nº mínimo de parcelas
  pc_maxima            integer             NOT NULL,      -- nº máximo de parcelas
  taxa_administradora  numeric(5,2)        DEFAULT 0,  -- opcional (2026-07-23): nem todo tipo de carteira cobra taxa
  perc_desconto        numeric(5,2)        DEFAULT 0,  -- 2026-07-28, vindo de `moeda`: ou desconto ou acréscimo, nunca os dois juntos
  perc_acrescimo       numeric(5,2)        DEFAULT 0,  -- 2026-07-28, vindo de `moeda`
  -- 2026-07-29 (Recebimento de Crediário, RN007): só carteiras marcadas aqui aparecem como
  -- opção de pagamento naquela tela — controla quais formas de pagamento (categoria AVISTA/
  -- CARTAO_DEBITO/CARTAO_CREDITO) podem quitar parcela de crediário.
  permite_receber_crediario boolean        NOT NULL DEFAULT false,
  criado_em            timestamptz         NOT NULL DEFAULT now(),  -- 2026-07-23 (auditoria, convenção do domínio)
  atualizado_em        timestamptz         NOT NULL DEFAULT now(),
  -- base para FK composta (P8) de contas_receber/caixa_detalhe.
  -- 2026-07-28: categoria entra na chave — a mesma bandeira (nome_carteira) pode existir uma
  -- vez por categoria (é assim que "HIPER" débito e "HIPER" crédito coexistem).
  CONSTRAINT tipo_carteira_uk    UNIQUE (id_tenant, nome_carteira, categoria_carteira),
  CONSTRAINT tipo_carteira_id_uk UNIQUE (id_tenant, id_carteira),
  CONSTRAINT tipo_carteira_parcelas_ck CHECK (pc_minima >= 1 AND pc_maxima >= pc_minima)
);
CREATE INDEX tipo_carteira_id_tenant_ix ON tipo_carteira (id_tenant);

-- contas_receber: parcelas a receber da venda, ligadas ao tipo de carteira (crediário, cartão...).
CREATE TABLE contas_receber (
  id_conta_receber    integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant           smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_venda            integer       NOT NULL,
  id_carteira         integer       NOT NULL,
  numero_parcela      integer       NOT NULL,
  data_vencimento     timestamptz   NOT NULL,
  data_recebimento    timestamptz,
  valor_receber       numeric(12,2) NOT NULL,
  valor_juros         numeric(12,2) NOT NULL DEFAULT 0,
  valor_desconto      numeric(12,2) NOT NULL DEFAULT 0,
  valor_recebido      numeric(12,2) NOT NULL DEFAULT 0,
  documento_recebido  boolean       NOT NULL DEFAULT false,
  id_lote_recebimento integer,        -- sem FK — nº de gerador externo, mesmo padrão de id_transferencia (V019)
  id_empresa_pagamento integer,       -- 2026-07-23: loja onde a parcela foi paga; null até existir a baixa
  criado_em           timestamptz   NOT NULL DEFAULT now(),
  -- base para FK composta (P8) de contas_receber_detalhe.
  CONSTRAINT contas_receber_id_uk       UNIQUE (id_tenant, id_conta_receber),
  CONSTRAINT contas_receber_venda_fk    FOREIGN KEY (id_tenant, id_venda)    REFERENCES venda        (id_tenant, id_venda),
  CONSTRAINT contas_receber_carteira_fk FOREIGN KEY (id_tenant, id_carteira) REFERENCES tipo_carteira (id_tenant, id_carteira),
  CONSTRAINT contas_receber_empresa_pagamento_fk FOREIGN KEY (id_tenant, id_empresa_pagamento)
    REFERENCES empresa (id_tenant, id_empresa)
);
CREATE INDEX contas_receber_id_tenant_ix        ON contas_receber (id_tenant);
CREATE INDEX contas_receber_vencimento_ix       ON contas_receber (id_tenant, data_vencimento);
CREATE INDEX contas_receber_recebimento_ix      ON contas_receber (id_tenant, data_recebimento);
CREATE INDEX contas_receber_lote_recebimento_ix ON contas_receber (id_tenant, id_lote_recebimento);

-- contas_receber_detalhe: detalhe 1:1 da parcela paga em cartão (taxa adm/autorização).
CREATE TABLE contas_receber_detalhe (
  id_tenant           smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_conta_receber    integer       NOT NULL,
  numero_autorizacao  text,
  valor_bruto         numeric(12,2) NOT NULL DEFAULT 0,
  taxa_administradora numeric(5,2)  NOT NULL DEFAULT 0,
  valor_liquido       numeric(12,2) NOT NULL DEFAULT 0,
  CONSTRAINT contas_receber_detalhe_pk PRIMARY KEY (id_tenant, id_conta_receber),
  CONSTRAINT contas_receber_detalhe_fk FOREIGN KEY (id_tenant, id_conta_receber)
    REFERENCES contas_receber (id_tenant, id_conta_receber)
);
CREATE INDEX contas_receber_detalhe_autorizacao_ix ON contas_receber_detalhe (id_tenant, numero_autorizacao);

-- contas_receber_lote: cabeçalho de um recebimento de crediário (2026-07-29) — dá um número
-- de verdade a `id_lote_recebimento` (até aqui só um "gerador externo" placeholder, mesmo
-- padrão que `produto_transferencia` deu a `id_transferencia` em V019). Uma linha por operação
-- de "Receber" na tela, cobrindo uma ou várias parcelas/vendas do mesmo cliente. Sem FK de
-- `contas_receber`/`caixa_detalhe` pra cá — proposital, mesma decisão de não acoplar o ledger
-- genérico a uma tabela de negócio específica (ver comentário de `produto_movimento_mestre.
-- id_transferencia`).
CREATE TABLE contas_receber_lote (
  id_lote_recebimento integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant           smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_cliente          integer       NOT NULL,
  id_empresa          integer       NOT NULL,
  id_usuario          integer       NOT NULL,
  data_recebimento    timestamptz   NOT NULL DEFAULT now(),
  valor_total         numeric(12,2) NOT NULL,
  CONSTRAINT contas_receber_lote_id_uk       UNIQUE (id_tenant, id_lote_recebimento),
  CONSTRAINT contas_receber_lote_cliente_fk  FOREIGN KEY (id_tenant, id_cliente) REFERENCES cliente  (id_tenant, id_cliente),
  CONSTRAINT contas_receber_lote_empresa_fk  FOREIGN KEY (id_tenant, id_empresa) REFERENCES empresa  (id_tenant, id_empresa),
  CONSTRAINT contas_receber_lote_usuario_fk  FOREIGN KEY (id_tenant, id_usuario) REFERENCES usuario  (id_tenant, id_usuario)
);
CREATE INDEX contas_receber_lote_id_tenant_ix ON contas_receber_lote (id_tenant);
CREATE INDEX contas_receber_lote_cliente_ix   ON contas_receber_lote (id_tenant, id_cliente);

-- caixa_mestre: header de uma sessão de caixa (abertura/fechamento, usuário responsável).
CREATE TABLE caixa_mestre (
  id_caixa        integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant       smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa      integer       NOT NULL,
  id_usuario      integer       NOT NULL,
  id_carteira     integer       NOT NULL,
  saldo_inicial   numeric(12,2) NOT NULL DEFAULT 0,
  data_abertura   timestamptz   NOT NULL DEFAULT now(),
  data_fechamento timestamptz,
  caixa_fechado   boolean       NOT NULL DEFAULT false,
  observacoes     text,
  -- base para FK composta (P8) de caixa_detalhe.
  CONSTRAINT caixa_mestre_id_uk       UNIQUE (id_tenant, id_caixa),
  CONSTRAINT caixa_mestre_empresa_fk  FOREIGN KEY (id_tenant, id_empresa)  REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT caixa_mestre_usuario_fk  FOREIGN KEY (id_tenant, id_usuario)  REFERENCES usuario (id_tenant, id_usuario),
  CONSTRAINT caixa_mestre_carteira_fk FOREIGN KEY (id_tenant, id_carteira) REFERENCES tipo_carteira (id_tenant, id_carteira)
);
CREATE INDEX caixa_mestre_id_tenant_ix       ON caixa_mestre (id_tenant);
CREATE INDEX caixa_mestre_data_abertura_ix   ON caixa_mestre (id_tenant, data_abertura);
CREATE INDEX caixa_mestre_data_fechamento_ix ON caixa_mestre (id_tenant, data_fechamento);

-- caixa_detalhe: lançamentos dentro da sessão de caixa.
CREATE TABLE caixa_detalhe (
  localizador         integer             GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant           smallint            NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_caixa            integer             NOT NULL,
  id_carteira         integer             NOT NULL,  -- 2026-07-28: era id_moeda, moeda foi absorvida por tipo_carteira
  id_venda            integer,
  id_lote_recebimento integer,             -- sem FK — mesmo padrão de id_transferencia
  id_plano_contas     text,
  valor               numeric(12,2)       NOT NULL,
  tipo_operacao       tipo_operacao_caixa NOT NULL,
  credito_debito      credito_debito      NOT NULL,  -- reaproveita o ENUM já criado em V013
  observacoes         text,
  criado_em           timestamptz         NOT NULL DEFAULT now(),  -- P3: "quando" de cada lançamento
  CONSTRAINT caixa_detalhe_caixa_fk        FOREIGN KEY (id_tenant, id_caixa)        REFERENCES caixa_mestre     (id_tenant, id_caixa),
  CONSTRAINT caixa_detalhe_carteira_fk     FOREIGN KEY (id_tenant, id_carteira)     REFERENCES tipo_carteira    (id_tenant, id_carteira),
  CONSTRAINT caixa_detalhe_venda_fk        FOREIGN KEY (id_tenant, id_venda)        REFERENCES venda            (id_tenant, id_venda),
  CONSTRAINT caixa_detalhe_plano_contas_fk FOREIGN KEY (id_tenant, id_plano_contas) REFERENCES cfg_plano_contas (id_tenant, id_plano_contas)
);
CREATE INDEX caixa_detalhe_id_tenant_ix        ON caixa_detalhe (id_tenant);
CREATE INDEX caixa_detalhe_id_venda_ix         ON caixa_detalhe (id_tenant, id_venda);
CREATE INDEX caixa_detalhe_lote_recebimento_ix ON caixa_detalhe (id_tenant, id_lote_recebimento);
CREATE INDEX caixa_detalhe_plano_contas_ix     ON caixa_detalhe (id_tenant, id_plano_contas);

-- RLS (P8) — V024 já rodou antes destas tabelas existirem, então o guard-corpo dele NÃO as
-- alcança; este arquivo garante RLS aqui, mesmo padrão (ENABLE+FORCE+policy+grants) de V024.
DO $$
DECLARE
  t text;
  tabelas text[] := ARRAY[
    'tipo_carteira',
    'contas_receber', 'contas_receber_detalhe', 'contas_receber_lote',
    'caixa_mestre', 'caixa_detalhe'
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
END $$;

-- Guarda-corpo (P8), mesmo padrão de V024: falha a migration se QUALQUER tabela com
-- id_tenant (incluindo as de migrations anteriores) tiver ficado sem RLS habilitado.
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

COMMENT ON TABLE tipo_carteira          IS 'Forma de pagamento — categoria, prazo/parcelas/taxa e desconto/acréscimo (RLS). Absorveu `moeda` em 2026-07-28: cada linha já é uma combinação categoria×bandeira específica.';
COMMENT ON COLUMN tipo_carteira.categoria_carteira IS 'Categoria fixa (AVISTA/CARTAO_DEBITO/CARTAO_CREDITO/CREDIARIO) — usada pelo histórico do cliente pra isolar parcelas de crediário (2026-07-23).';
COMMENT ON COLUMN tipo_carteira.perc_desconto IS 'Opcional (2026-07-28, vindo de `moeda`) — ou desconto ou acréscimo, nunca os dois juntos.';
COMMENT ON COLUMN tipo_carteira.perc_acrescimo IS 'Opcional (2026-07-28, vindo de `moeda`).';
COMMENT ON COLUMN tipo_carteira.permite_receber_crediario IS 'Recebimento de Crediário (2026-07-29, RN007) — só carteiras marcadas aqui aparecem como opção de pagamento naquela tela.';
COMMENT ON TABLE contas_receber         IS 'Parcelas a receber da venda (RLS). Revisão de Q5/ADR-010 (2026-07-16): crediário antecipado da Fase 2.';
COMMENT ON COLUMN contas_receber.id_empresa_pagamento IS 'Loja onde a parcela foi paga — null até existir a baixa de parcela (Recebimento de Crediário, 2026-07-29).';
COMMENT ON TABLE contas_receber_detalhe IS 'Detalhe 1:1 de contas_receber para taxas/autorização de cartão (RLS).';
COMMENT ON TABLE contas_receber_lote    IS 'Cabeçalho de um recebimento de crediário (RLS, 2026-07-29) — dá número real a id_lote_recebimento, pode cobrir várias parcelas/vendas do mesmo cliente numa só operação de "Receber".';
COMMENT ON TABLE caixa_mestre           IS 'Header de sessão de caixa (RLS). Revisão de Q5/ADR-010 (2026-07-16).';
COMMENT ON TABLE caixa_detalhe          IS 'Lançamentos da sessão de caixa (RLS). tipo_operacao: RV=RECEBIMENTO_VENDA, RP=RECEBIMENTO_PARCELA_CREDIARIO, DC=DEBITO_CAIXA, CC=CREDITO_CAIXA, TR=TROCO (legado).';
COMMENT ON COLUMN caixa_detalhe.id_carteira IS 'Forma de pagamento do lançamento (2026-07-28: era id_moeda, apontava pra `moeda`, absorvida por tipo_carteira).';
