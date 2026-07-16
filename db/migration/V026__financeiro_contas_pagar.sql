-- V026 — financeiro: contas_pagar (revisão de Q5/ADR-010/ADR-012, 2026-07-16). Com esta
-- migration, só conta_corrente/conta_corrente_movimento continuam fora do v1 (§3.3.7).

CREATE TABLE contas_pagar (
  id_conta_pagar     integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa         integer       NOT NULL,
  id_fornecedor      integer       NOT NULL,
  id_plano_contas    text          NOT NULL,
  nota_fiscal        integer,                     -- numero da NF; nullable, sem valor mágico (2026-07-16)
  numero_duplicata   text,
  data_lancamento    timestamptz   NOT NULL,
  data_vencimento    timestamptz   NOT NULL,
  data_pagamento     timestamptz,
  valor_pagar        numeric(12,2) NOT NULL,
  valor_pago         numeric(12,2) NOT NULL DEFAULT 0,
  documento_pago     boolean       NOT NULL DEFAULT false,
  observacoes        text,
  CONSTRAINT contas_pagar_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT contas_pagar_fornecedor_fk FOREIGN KEY (id_tenant, id_fornecedor)
    REFERENCES fornecedor (id_tenant, id_fornecedor),
  CONSTRAINT contas_pagar_plano_contas_fk FOREIGN KEY (id_tenant, id_plano_contas)
    REFERENCES cfg_plano_contas (id_tenant, id_plano_contas)
);
CREATE INDEX contas_pagar_id_tenant_ix        ON contas_pagar (id_tenant);
CREATE INDEX contas_pagar_nota_fiscal_ix      ON contas_pagar (id_tenant, nota_fiscal);
CREATE INDEX contas_pagar_numero_duplicata_ix ON contas_pagar (id_tenant, numero_duplicata);
CREATE INDEX contas_pagar_data_lancamento_ix  ON contas_pagar (id_tenant, data_lancamento);
CREATE INDEX contas_pagar_data_vencimento_ix  ON contas_pagar (id_tenant, data_vencimento);
CREATE INDEX contas_pagar_data_pagamento_ix   ON contas_pagar (id_tenant, data_pagamento);

-- RLS (P8) — mesmo padrão de V025 (V024 já tinha rodado antes desta tabela existir).
ALTER TABLE contas_pagar ENABLE ROW LEVEL SECURITY;
ALTER TABLE contas_pagar FORCE  ROW LEVEL SECURITY;
CREATE POLICY contas_pagar_rls ON contas_pagar
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT, UPDATE, DELETE ON contas_pagar TO niner_app;

-- Guarda-corpo (P8), mesmo padrão de V024/V025: falha a migration se qualquer tabela com
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

COMMENT ON TABLE contas_pagar IS 'Contas a pagar do lojista (RLS). Revisão de Q5/ADR-010/ADR-012 (2026-07-16). PK id_conta_pagar (renomeado de localizador do legado).';
