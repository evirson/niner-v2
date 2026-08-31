-- V104 — Relatório de Ordens de Serviço no catálogo de telas (RBAC).
--
-- Fecha a lacuna registrada em docs/PENDENCIAS.md #56 ("não há relatório de OS"). Spec da tela em
-- docs/telas/relatorio-ordem-servico.md.
--
-- Fica em Relatórios › Faturamento, ao lado de Vendas e Comissões — é onde o lojista procura
-- "quanto a oficina produziu". No menu ele carrega `moduloServicos: true`, então some para a loja
-- que não vende serviço, do mesmo jeito que a tela de Ordens de Serviço.
--
-- ⚠️ As três ações vão FALSE: é tela de leitura, e o `AcoesPorTelaConferemTest` deriva a ação
-- exigida direto das anotações do controller (@Tela/@Acao/@Livre) — marcar uma ação que o código
-- não exige criaria uma caixa que não governa nada, e faria quem configura desconfiar da grade
-- inteira. O contrário (ação exigida e não oferecida) trancaria a tela: foi assim que a V076
-- travou o PDV.

INSERT INTO cfg_tela (chave, nome, grupo, subgrupo, ordem, admin_apenas,
                      tem_incluir, tem_alterar, tem_excluir)
VALUES ('relatorio-ordens-servico', 'Ordens de Serviço', 'Relatórios', 'Faturamento', 49, false,
        false, false, false)
ON CONFLICT (chave) DO NOTHING;
