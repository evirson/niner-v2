-- V025 — financeiro (parcial): crediário + caixa antecipados da Fase 2 (revisão de Q5/ADR-010,
-- decidida em 2026-07-16). Entram: tipo_carteira, moeda, moeda_detalhe, contas_receber(_detalhe),
-- caixa_mestre/caixa_detalhe. NÃO entram ainda: contas_pagar, conta_corrente(_movimento) — seguem
-- fora do v1 (§3.3.7). Todas as tabelas nascem com id_tenant (P8); FKs entre tabelas de domínio
-- são compostas (id_tenant, id_x), nunca simples (achado de 2026-07-16, RLS não protege
-- integridade referencial).

-- Natureza do lançamento de caixa (legado: RV/RP/DC/CC/TR).
CREATE TYPE tipo_operacao_caixa AS ENUM
  ('RECEBIMENTO_VENDA', 'RECEBIMENTO_PARCELA_CREDIARIO', 'DEBITO_CAIXA', 'CREDITO_CAIXA', 'TROCO');

-- tipo_carteira (config): prazo de pagamento (crediário, cartão etc.), parcelas min/max, taxa adm.
CREATE TABLE tipo_carteira (
  id_carteira          integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant            smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome_carteira        text         NOT NULL,
  prazo_pagamento      integer      NOT NULL,      -- dias entre parcelas
  pc_minima            integer      NOT NULL,      -- nº mínimo de parcelas
  pc_maxima            integer      NOT NULL,      -- nº máximo de parcelas
  taxa_administradora  numeric(5,2) DEFAULT 0,  -- opcional (2026-07-23): nem todo tipo de carteira cobra taxa
  criado_em            timestamptz  NOT NULL DEFAULT now(),  -- 2026-07-23 (auditoria, convenção do domínio)
  atualizado_em        timestamptz  NOT NULL DEFAULT now(),
  -- base para FK composta (P8) de moeda_detalhe/contas_receber.
  CONSTRAINT tipo_carteira_uk    UNIQUE (id_tenant, nome_carteira),
  CONSTRAINT tipo_carteira_id_uk UNIQUE (id_tenant, id_carteira),
  CONSTRAINT tipo_carteira_parcelas_ck CHECK (pc_minima >= 1 AND pc_maxima >= pc_minima)
);
CREATE INDEX tipo_carteira_id_tenant_ix ON tipo_carteira (id_tenant);

-- moeda (config): formas de recebimento válidas para venda. Seed POR TENANT no signup
-- (SignupService, mesmo padrão de cfg_geral) — não é seed global de Flyway, porque id_tenant
-- é obrigatório e não existe ainda no momento da migration.
CREATE TABLE moeda (
  id_moeda        integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant       smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome_moeda      text         NOT NULL,
  perc_desconto   numeric(5,2) DEFAULT 0,  -- opcional (2026-07-23): ou desconto ou acréscimo, nunca os dois juntos
  perc_acrescimo  numeric(5,2) DEFAULT 0,
  criado_em       timestamptz  NOT NULL DEFAULT now(),  -- 2026-07-23 (auditoria, convenção do domínio)
  atualizado_em   timestamptz  NOT NULL DEFAULT now(),
  -- base para FK composta (P8) de moeda_detalhe/caixa_detalhe.
  CONSTRAINT moeda_uk    UNIQUE (id_tenant, nome_moeda),
  CONSTRAINT moeda_id_uk UNIQUE (id_tenant, id_moeda)
);
CREATE INDEX moeda_id_tenant_ix ON moeda (id_tenant);

-- moeda_detalhe: associa a moeda ao(s) tipo(s) de carteira em que ela é válida.
CREATE TABLE moeda_detalhe (
  id_tenant   smallint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_moeda    integer  NOT NULL,
  id_carteira integer  NOT NULL,
  CONSTRAINT moeda_detalhe_pk          PRIMARY KEY (id_tenant, id_moeda, id_carteira),
  CONSTRAINT moeda_detalhe_moeda_fk    FOREIGN KEY (id_tenant, id_moeda)    REFERENCES moeda        (id_tenant, id_moeda),
  CONSTRAINT moeda_detalhe_carteira_fk FOREIGN KEY (id_tenant, id_carteira) REFERENCES tipo_carteira (id_tenant, id_carteira)
);
CREATE INDEX moeda_detalhe_id_tenant_ix ON moeda_detalhe (id_tenant);

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
  criado_em           timestamptz   NOT NULL DEFAULT now(),
  -- base para FK composta (P8) de contas_receber_detalhe.
  CONSTRAINT contas_receber_id_uk       UNIQUE (id_tenant, id_conta_receber),
  CONSTRAINT contas_receber_venda_fk    FOREIGN KEY (id_tenant, id_venda)    REFERENCES venda        (id_tenant, id_venda),
  CONSTRAINT contas_receber_carteira_fk FOREIGN KEY (id_tenant, id_carteira) REFERENCES tipo_carteira (id_tenant, id_carteira)
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

-- caixa_mestre: header de uma sessão de caixa (abertura/fechamento, usuário responsável).
CREATE TABLE caixa_mestre (
  id_caixa        integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant       smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa      integer       NOT NULL,
  id_usuario      integer       NOT NULL,
  data_abertura   timestamptz   NOT NULL DEFAULT now(),
  data_fechamento timestamptz,
  saldo_inicial   numeric(12,2) NOT NULL DEFAULT 0,
  saldo_final     numeric(12,2) NOT NULL DEFAULT 0,
  caixa_fechado   boolean       NOT NULL DEFAULT false,
  observacoes     text,
  -- base para FK composta (P8) de caixa_detalhe.
  CONSTRAINT caixa_mestre_id_uk      UNIQUE (id_tenant, id_caixa),
  CONSTRAINT caixa_mestre_empresa_fk FOREIGN KEY (id_tenant, id_empresa) REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT caixa_mestre_usuario_fk FOREIGN KEY (id_tenant, id_usuario) REFERENCES usuario (id_tenant, id_usuario)
);
CREATE INDEX caixa_mestre_id_tenant_ix       ON caixa_mestre (id_tenant);
CREATE INDEX caixa_mestre_data_abertura_ix   ON caixa_mestre (id_tenant, data_abertura);
CREATE INDEX caixa_mestre_data_fechamento_ix ON caixa_mestre (id_tenant, data_fechamento);

-- caixa_detalhe: lançamentos dentro da sessão de caixa.
CREATE TABLE caixa_detalhe (
  localizador         integer             GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant           smallint            NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_caixa            integer             NOT NULL,
  id_moeda            integer             NOT NULL,
  id_venda            integer,
  id_lote_recebimento integer,             -- sem FK — mesmo padrão de id_transferencia
  id_plano_contas     text,
  valor               numeric(12,2)       NOT NULL,
  tipo_operacao       tipo_operacao_caixa NOT NULL,
  credito_debito      credito_debito      NOT NULL,  -- reaproveita o ENUM já criado em V013
  observacoes         text,
  criado_em           timestamptz         NOT NULL DEFAULT now(),  -- P3: "quando" de cada lançamento
  CONSTRAINT caixa_detalhe_caixa_fk        FOREIGN KEY (id_tenant, id_caixa)        REFERENCES caixa_mestre     (id_tenant, id_caixa),
  CONSTRAINT caixa_detalhe_moeda_fk        FOREIGN KEY (id_tenant, id_moeda)        REFERENCES moeda            (id_tenant, id_moeda),
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
    'tipo_carteira', 'moeda', 'moeda_detalhe',
    'contas_receber', 'contas_receber_detalhe',
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

COMMENT ON TABLE tipo_carteira          IS 'Tipo de prazo/forma de pagamento (crediário, cartão etc.) com parcelas min/max e taxa adm. RLS.';
COMMENT ON TABLE moeda                  IS 'Formas de recebimento válidas para venda (RLS). Seed por tenant no signup (SignupService), não global.';
COMMENT ON TABLE moeda_detalhe          IS 'Associação moeda × tipo_carteira (RLS).';
COMMENT ON TABLE contas_receber         IS 'Parcelas a receber da venda (RLS). Revisão de Q5/ADR-010 (2026-07-16): crediário antecipado da Fase 2.';
COMMENT ON TABLE contas_receber_detalhe IS 'Detalhe 1:1 de contas_receber para taxas/autorização de cartão (RLS).';
COMMENT ON TABLE caixa_mestre           IS 'Header de sessão de caixa (RLS). Revisão de Q5/ADR-010 (2026-07-16).';
COMMENT ON TABLE caixa_detalhe          IS 'Lançamentos da sessão de caixa (RLS). tipo_operacao: RV=RECEBIMENTO_VENDA, RP=RECEBIMENTO_PARCELA_CREDIARIO, DC=DEBITO_CAIXA, CC=CREDITO_CAIXA, TR=TROCO (legado).';
