-- V038 — Medição de vendas por tenant (ADR-015).
--
-- A cota do plano é consumida por VENDA EMITIDA NO PDV, somando todas as empresas do tenant.
-- Regras fixadas na spec (docs/telas/painel-assinatura.md) e refletidas aqui:
--   · o contador ZERA na virada do mês (reset lazy, feito pelo LimiteVendasService);
--   · cancelar venda NÃO devolve cota (incremento puro — nunca decrementa);
--   · importação de dados legada e devolução NÃO contam.

-- ---------------------------------------------------------------------------------------------
-- Contador corrente (uma linha por tenant).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE plataforma.uso_tenant
  ADD COLUMN competencia_vendas date     NOT NULL DEFAULT date_trunc('month', now())::date,
  ADD COLUMN qtd_vendas_mes     integer  NOT NULL DEFAULT 0 CHECK (qtd_vendas_mes >= 0),
  ADD COLUMN qtd_empresas       smallint NOT NULL DEFAULT 1 CHECK (qtd_empresas >= 0);

COMMENT ON COLUMN plataforma.uso_tenant.competencia_vendas IS 'Mes de referencia de qtd_vendas_mes; ao virar o mes o contador zera e o mes fechado vai para uso_venda_mes.';
COMMENT ON COLUMN plataforma.uso_tenant.qtd_vendas_mes     IS 'Vendas emitidas no PDV na competencia, somando todas as empresas. Incremento puro: cancelamento nao devolve (ADR-015).';
COMMENT ON COLUMN plataforma.uso_tenant.qtd_empresas       IS 'Quantidade de empresas/CNPJs do tenant. Ilimitado em todos os planos (D4 revisada) — o contador existe para o painel e para metrica de uso.';

-- ---------------------------------------------------------------------------------------------
-- Histórico mensal fechado — alimenta o gráfico de 12 meses do painel e a recomendação de faixa.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE plataforma.uso_venda_mes (
  id_tenant   smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  competencia date        NOT NULL,
  qtd_vendas  integer     NOT NULL DEFAULT 0 CHECK (qtd_vendas >= 0),
  fechado_em  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uso_venda_mes_pk PRIMARY KEY (id_tenant, competencia)
);

COMMENT ON TABLE plataforma.uso_venda_mes IS
  'Historico mensal de vendas por tenant (ADR-015). Escrito quando a competencia vira; base do painel Minha Conta e da faixa recomendada.';

-- GRANT explícito, mesmo com o ALTER DEFAULT PRIVILEGES de V011: aquele só vale para tabela
-- criada por niner_owner, e nos testes (Testcontainers) as migrations rodam como superusuário do
-- container — a tabela nasce de outro dono e niner_app fica sem acesso. Foi assim que
-- CotaVendasTest quebrou com "permission denied for table uso_venda_mes". Mesmo padrão de V027.
-- Sem DELETE de propósito: histórico de uso é a base de cobrança, não se apaga pela aplicação.
GRANT SELECT, INSERT, UPDATE ON plataforma.uso_venda_mes TO niner_app;

-- ---------------------------------------------------------------------------------------------
-- Tolerância negociável por cliente. NULL = usa o parâmetro global (o caso normal).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE plataforma.assinatura
  ADD COLUMN tolerancia_vendas integer CHECK (tolerancia_vendas IS NULL OR tolerancia_vendas >= 0);

COMMENT ON COLUMN plataforma.assinatura.tolerancia_vendas IS
  'Override da tolerancia de vendas deste cliente. NULL = parametro_comercial.tolerancia_vendas.';

-- ---------------------------------------------------------------------------------------------
-- Migração dos tenants existentes: não existe mais trial por tempo (ADR-015 supera D2).
-- Quem estava em TRIAL passa a ser conta Gratuita ATIVA, sem data de expiração.
-- ---------------------------------------------------------------------------------------------
UPDATE plataforma.assinatura a
   SET id_plano        = (SELECT id_plano FROM plataforma.plano WHERE gratuito ORDER BY id_plano LIMIT 1),
       status          = 'ATIVA',
       trial_expira_em = NULL,
       atualizado_em   = now()
 WHERE a.status = 'TRIAL';

UPDATE plataforma.tenant SET status = 'ATIVA', atualizado_em = now() WHERE status = 'TRIAL';

-- ---------------------------------------------------------------------------------------------
-- Backfill do contador. Roda tenant a tenant com o contexto de RLS setado (P8): as tabelas de
-- domínio (empresa, venda) têm FORCE ROW LEVEL SECURITY, então nem o dono lê sem app.id_tenant.
--
-- Vendas: só o que veio do PDV (`id_caixa IS NOT NULL`). Venda inserida pela Rotina de
-- Importação de Dados (ContasReceberImportador) não tem caixa e NÃO conta — contá-la queimaria
-- a cota do lojista no dia da migração do sistema antigo.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
  t         record;
  n_empresa integer;
  n_venda   integer;
BEGIN
  FOR t IN SELECT id_tenant FROM plataforma.tenant LOOP
    PERFORM set_config('app.id_tenant', t.id_tenant::text, true);

    SELECT count(*) INTO n_empresa FROM empresa WHERE id_tenant = t.id_tenant;
    SELECT count(*) INTO n_venda   FROM venda
     WHERE id_tenant = t.id_tenant
       AND id_caixa IS NOT NULL
       AND data_venda >= date_trunc('month', now());

    INSERT INTO plataforma.uso_tenant (id_tenant, qtd_empresas, qtd_vendas_mes)
    VALUES (t.id_tenant, n_empresa, n_venda)
    ON CONFLICT (id_tenant) DO UPDATE
      SET qtd_empresas   = EXCLUDED.qtd_empresas,
          qtd_vendas_mes = EXCLUDED.qtd_vendas_mes,
          competencia_vendas = date_trunc('month', now())::date,
          atualizado_em  = now();
  END LOOP;
  PERFORM set_config('app.id_tenant', '', true);
END $$;
