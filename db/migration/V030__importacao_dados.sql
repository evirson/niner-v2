-- V030 — Rotina de Importação de Dados (docs/telas/importacao-dados.md, 2026-08-06).
-- Só a auditoria do lote de importação vira tabela — o desenho final da feature evitou
-- propositalmente qualquer alteração nas tabelas existentes (cliente/fornecedor/produto/
-- contas_receber/venda continuam exatamente como estavam: a resolução de referências entre
-- arquivos usa chaves de negócio que já existem, CPF/CNPJ, em vez de um "de-para" persistido).

-- importacao_lote: auditoria de cada execução confirmada da rotina de importação (P3).
-- `tabela` é texto livre, não ENUM: o objetivo declarado da feature é crescer pra novas tabelas
-- sem precisar de migration a cada uma; a whitelist de tabelas elegíveis vive na aplicação.
CREATE TABLE importacao_lote (
  id_lote            integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  tabela             text        NOT NULL,
  nome_arquivo       text        NOT NULL,
  id_usuario         integer     NOT NULL,
  total_linhas       integer     NOT NULL,
  linhas_importadas  integer     NOT NULL,
  linhas_rejeitadas  integer     NOT NULL,
  criado_em          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT importacao_lote_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario)
);
CREATE INDEX importacao_lote_id_tenant_ix ON importacao_lote (id_tenant);
CREATE INDEX importacao_lote_tabela_ix    ON importacao_lote (id_tenant, tabela, criado_em);

-- RLS (P8), mesmo padrão de V025/V026/V028.
ALTER TABLE importacao_lote ENABLE ROW LEVEL SECURITY;
ALTER TABLE importacao_lote FORCE  ROW LEVEL SECURITY;
CREATE POLICY importacao_lote_rls ON importacao_lote
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT ON importacao_lote TO niner_app;

-- Guarda-corpo (P8), mesmo padrão de V024/V025/V026/V028.
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

COMMENT ON TABLE importacao_lote IS 'Auditoria (P3) de cada execução confirmada da Rotina de Importação de Dados — quem, quando, qual tabela, quantas linhas ok/rejeitadas. Nunca UPDATE/DELETE pela aplicação (só INSERT).';
