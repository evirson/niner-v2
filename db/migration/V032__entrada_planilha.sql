-- Entrada de Produtos por Compra — Fluxo Planilha (2026-08-11/12): configuração geral do
-- plano de contas usado nas contas a pagar geradas pela Entrada. Até aqui `ContasPagarService`
-- usava `fornecedor.id_plano_contas` (a conta contábil do FORNECEDOR), o que está errado — o
-- correto é uma conta de custo/despesa do próprio tenant (o "3.03.001 Compra de Mercadoria
-- para Revenda" já reservado no plano padrão, db/scripts/seed_plano_contas_padrao.sql).
--
-- 1) Semeia a árvore MÍNIMA até essa conta (3 níveis: nivel/id_plano_contas_pai são colunas
--    GERADAS a partir do código, não se informam) para todo tenant que ainda não a tenha —
--    `ON CONFLICT DO NOTHING` cobre tanto quem já rodou o script completo (ex. tenant 1, Loja
--    Teste Manual) quanto tenants sem nenhum plano de contas ainda. Percorre tenant a tenant
--    ajustando `app.id_tenant` a cada iteração (mesmo idioma de `SignupService`/
--    `seed_plano_contas_padrao.sql`) em vez de tentar inserir todos de uma vez — `niner_owner`
--    (quem roda as migrations, CLAUDE.md) NÃO tem `BYPASSRLS` (P8), então uma única query
--    cruzando `plataforma.tenant` violaria a policy de `cfg_plano_contas`/`cfg_geral` (achado
--    real ao aplicar esta migration manualmente no banco de dev). O FK de hierarquia
--    (`cfg_plano_contas_pai_fk`) e o trigger de guarda já são `DEFERRABLE INITIALLY DEFERRED`
--    (V016), então inserir os 3 níveis juntos resolve a árvore sem ordem especial.
-- 2) `cfg_geral` ganha `id_plano_contas_compra_mercadoria` (FK composta, mesmo padrão de
--    `contas_pagar`/`caixa_detalhe`/`conta_corrente`), editável em Parâmetros do Sistema.

ALTER TABLE cfg_geral ADD COLUMN id_plano_contas_compra_mercadoria text;

DO $$
DECLARE
    t_id smallint;
BEGIN
    FOR t_id IN SELECT id_tenant FROM plataforma.tenant LOOP
        PERFORM set_config('app.id_tenant', t_id::text, true);

        INSERT INTO cfg_plano_contas (
            id_tenant, id_plano_contas, descricao, tipo_movimento, natureza,
            inclui_dre, inclui_fluxo_caixa, grupo_dre, grupo_dfc, sinal,
            aceita_lancamento, padrao_sistema
        ) VALUES
            (t_id, '3.00.000', 'CUSTOS VARIÁVEIS', 'DEBITO', 'SINTETICA',
                true, true, 'CUSTO_VARIAVEL', 'OPERACIONAL', -1, false, true),
            (t_id, '3.03.000', 'Compras de Mercadoria', 'DEBITO', 'SINTETICA',
                false, true, 'NAO_APLICA', 'OPERACIONAL', -1, false, true),
            (t_id, '3.03.001', 'Compra de Mercadoria para Revenda', 'DEBITO', 'ANALITICA',
                false, true, 'NAO_APLICA', 'OPERACIONAL', -1, true, true)
        ON CONFLICT (id_tenant, id_plano_contas) DO NOTHING;

        UPDATE cfg_geral SET id_plano_contas_compra_mercadoria = '3.03.001'
         WHERE id_tenant = t_id AND id_plano_contas_compra_mercadoria IS NULL;
    END LOOP;
END $$;

ALTER TABLE cfg_geral ALTER COLUMN id_plano_contas_compra_mercadoria SET NOT NULL;

ALTER TABLE cfg_geral ADD CONSTRAINT cfg_geral_plano_contas_compra_fk
    FOREIGN KEY (id_tenant, id_plano_contas_compra_mercadoria)
    REFERENCES cfg_plano_contas (id_tenant, id_plano_contas);
