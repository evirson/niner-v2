-- V028 — financeiro: conta_corrente/conta_corrente_movimento (revisão de Q5/ADR-010/ADR-012,
-- 2026-07-30). Com esta migration, o módulo financeiro do lojista (§3.3.7) fica completo — não
-- resta mais nenhuma tabela legada fora do v1.

-- cfg_banco — referência de bancos brasileiros (código FEBRABAN + nome), GLOBAL (sem
-- id_tenant/RLS, P9). Usada pelo autopreenchimento do nome do banco no cadastro de Conta
-- Corrente (2026-07-30, pedido do dono do produto). Diferente de cfg_produto_ncm (V017, ~50
-- mil códigos, carregada por script separado): aqui a lista é pequena e estável (códigos
-- FEBRABAN não mudam com frequência) e `conta_corrente.id_banco` é NOT NULL — toda conta
-- corrente exige um banco válido, então os dados precisam existir em qualquer ambiente que
-- rode as migrations (dev, teste/Testcontainers, CI), não só no banco de produção carregado
-- manualmente. Por isso o seed entra direto na migration, não em db/scripts/.
CREATE TABLE cfg_banco (
  codigo_banco text NOT NULL PRIMARY KEY,
  nome_banco   text NOT NULL
);
COMMENT ON TABLE cfg_banco IS 'Referência de bancos brasileiros (código FEBRABAN + nome), GLOBAL — igual para todos os tenants, sem RLS. Conjunto prático dos bancos mais usados no varejo, não a tabela oficial completa do Bacen/FEBRABAN.';
GRANT SELECT ON cfg_banco TO niner_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_banco TO niner_owner;

INSERT INTO cfg_banco (codigo_banco, nome_banco) VALUES
  ('001', 'BANCO DO BRASIL'),
  ('033', 'SANTANDER'),
  ('041', 'BANRISUL'),
  ('070', 'BRB - BANCO DE BRASILIA'),
  ('077', 'BANCO INTER'),
  ('104', 'CAIXA ECONOMICA FEDERAL'),
  ('197', 'STONE PAGAMENTOS'),
  ('208', 'BANCO BTG PACTUAL'),
  ('212', 'BANCO ORIGINAL'),
  ('237', 'BRADESCO'),
  ('260', 'NU PAGAMENTOS (NUBANK)'),
  ('290', 'PAGSEGURO (PAGBANK)'),
  ('318', 'BANCO BMG'),
  ('323', 'MERCADO PAGO'),
  ('336', 'BANCO C6'),
  ('341', 'ITAU UNIBANCO'),
  ('348', 'BANCO XP'),
  ('389', 'BANCO MERCANTIL DO BRASIL'),
  ('422', 'BANCO SAFRA'),
  ('477', 'CITIBANK'),
  ('505', 'CREDIT SUISSE BRASIL'),
  ('604', 'BANCO INDUSTRIAL DO BRASIL'),
  ('611', 'BANCO PAULISTA'),
  ('623', 'BANCO PAN'),
  ('633', 'BANCO RENDIMENTO'),
  ('637', 'BANCO SOFISA'),
  ('655', 'BANCO VOTORANTIM (BANCO BV)'),
  ('707', 'BANCO DAYCOVAL'),
  ('743', 'BANCO SEMEAR'),
  ('746', 'BANCO MODAL'),
  ('748', 'SICREDI'),
  ('751', 'SCOTIABANK BRASIL'),
  ('752', 'BNP PARIBAS BRASIL'),
  ('756', 'SICOOB');

-- conta_corrente: PK de negócio (id_tenant, id_conta_corrente) — o próprio número/código da
-- conta bancária, digitado pelo usuário na criação e imutável depois (mesmo padrão de
-- cfg_plano_contas). id_banco referencia cfg_banco (autopreenchimento do nome, 2026-07-30);
-- id_agencia continua texto livre — não existe uma lista global finita de agências.
CREATE TABLE conta_corrente (
  id_tenant         smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_conta_corrente text        NOT NULL,
  id_empresa        integer     NOT NULL,
  id_banco          text        NOT NULL REFERENCES cfg_banco (codigo_banco),
  id_agencia        text        NOT NULL,
  descricao         text        NOT NULL,
  ativo             boolean     NOT NULL DEFAULT true,
  data_abertura     date,
  criado_em         timestamptz NOT NULL DEFAULT now(),
  atualizado_em     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT conta_corrente_pk         PRIMARY KEY (id_tenant, id_conta_corrente),
  CONSTRAINT conta_corrente_empresa_fk FOREIGN KEY (id_tenant, id_empresa) REFERENCES empresa (id_tenant, id_empresa)
);
CREATE INDEX conta_corrente_id_tenant_ix ON conta_corrente (id_tenant);
CREATE INDEX conta_corrente_descricao_ix ON conta_corrente (id_tenant, descricao);
CREATE INDEX conta_corrente_empresa_ix   ON conta_corrente (id_tenant, id_empresa);
CREATE INDEX conta_corrente_banco_ix     ON conta_corrente (id_banco);

-- conta_corrente_movimento: lançamento (extrato manual) — localizador surrogate (mesmo padrão
-- de caixa_detalhe/contas_pagar), credito_debito reaproveita o ENUM já criado em V013.
-- criado_em/atualizado_em (não só criado_em como em caixa_detalhe) porque esta tela permite
-- editar um lançamento já gravado (ex.: marcar como compensado), diferente do ledger de caixa,
-- que é só leitura depois de gravado.
CREATE TABLE conta_corrente_movimento (
  localizador        integer        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint       NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_conta_corrente  text           NOT NULL,
  id_plano_contas    text           NOT NULL,
  data_movimento     timestamptz    NOT NULL,
  numero_documento   text           NOT NULL,
  credito_debito     credito_debito NOT NULL,
  compensado         boolean        NOT NULL DEFAULT false,
  valor              numeric(12,2)  NOT NULL,
  observacao         text,
  criado_em          timestamptz    NOT NULL DEFAULT now(),
  atualizado_em      timestamptz    NOT NULL DEFAULT now(),
  CONSTRAINT conta_corrente_movimento_conta_fk FOREIGN KEY (id_tenant, id_conta_corrente)
    REFERENCES conta_corrente (id_tenant, id_conta_corrente),
  CONSTRAINT conta_corrente_movimento_plano_contas_fk FOREIGN KEY (id_tenant, id_plano_contas)
    REFERENCES cfg_plano_contas (id_tenant, id_plano_contas)
);
CREATE INDEX conta_corrente_movimento_id_tenant_ix        ON conta_corrente_movimento (id_tenant);
CREATE INDEX conta_corrente_movimento_conta_ix             ON conta_corrente_movimento (id_tenant, id_conta_corrente);
CREATE INDEX conta_corrente_movimento_data_movimento_ix     ON conta_corrente_movimento (id_tenant, data_movimento);
CREATE INDEX conta_corrente_movimento_numero_documento_ix   ON conta_corrente_movimento (id_tenant, numero_documento);

-- RLS (P8) — mesmo padrão de V025/V026.
ALTER TABLE conta_corrente ENABLE ROW LEVEL SECURITY;
ALTER TABLE conta_corrente FORCE  ROW LEVEL SECURITY;
CREATE POLICY conta_corrente_rls ON conta_corrente
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT, UPDATE, DELETE ON conta_corrente TO niner_app;

ALTER TABLE conta_corrente_movimento ENABLE ROW LEVEL SECURITY;
ALTER TABLE conta_corrente_movimento FORCE  ROW LEVEL SECURITY;
CREATE POLICY conta_corrente_movimento_rls ON conta_corrente_movimento
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT, UPDATE, DELETE ON conta_corrente_movimento TO niner_app;

-- Guarda-corpo (P8), mesmo padrão de V024/V025/V026.
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

COMMENT ON TABLE conta_corrente           IS 'Conta corrente bancária do lojista (RLS). PK de negócio id_conta_corrente (número/código da conta, imutável após criado — mesmo padrão de cfg_plano_contas).';
COMMENT ON TABLE conta_corrente_movimento IS 'Lançamento (extrato manual) de conta corrente (RLS). credito_debito reaproveita o ENUM de V013.';
