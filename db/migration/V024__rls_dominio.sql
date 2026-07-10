-- V024 — RLS de domínio (P8). ARQUIVO ÚNICO E FINAL: garante que NENHUMA tabela de
-- tenant fica sem política — auditável num só ponto e testável ("toda tabela de tenant
-- tem RLS" vira teste de P8). As tabelas de `plataforma` são globais (P9) e NÃO entram aqui.
--
-- Para cada tabela: ENABLE + FORCE ROW LEVEL SECURITY (o FORCE sujeita até a dona
-- niner_owner à política) + policy USING/WITH CHECK (id_tenant = plataforma.tenant_atual())
-- + grants de CRUD para niner_app. A app (niner_app, SEM BYPASSRLS) só enxerga o próprio
-- tenant; o contexto vem de `SET LOCAL app.id_tenant` por transação (TenantAwareTransactionManager).

DO $$
DECLARE
  t text;
  tabelas text[] := ARRAY[
    -- identidade
    'empresa', 'usuario', 'usuario_rotina',
    -- cadastros
    'cliente', 'fornecedor', 'funcionario',
    -- catalogo
    'cfg_categoria_produto', 'cfg_variante_linha', 'cfg_variante_coluna',
    'produto', 'produto_categoria', 'produto_barra',
    -- vendas
    'venda', 'venda_devolucao',
    -- estoque
    'produto_estoque', 'produto_movimento_mestre', 'produto_movimento_detalhe', 'produto_balanco',
    -- canais / pedidos
    'canal', 'anuncio', 'pedido', 'pedido_item',
    -- integracao
    'outbox_evento', 'webhook_recebido',
    -- config
    'cfg_geral'
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

-- Guarda-corpo (P8): falha a migration se alguma tabela de tenant (tem coluna id_tenant,
-- fora do schema plataforma) ficar SEM RLS habilitado. Torna a garantia auto-verificável.
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
