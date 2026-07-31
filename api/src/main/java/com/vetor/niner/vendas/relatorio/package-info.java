/**
 * Relatório de Vendas (docs/telas/relatorio-vendas.md) — 1ª tela do grupo de menu Relatórios,
 * define o padrão de tela de filtro de relatório: painel de filtros (período/empresas/vendedor/
 * totalizador) → KPIs → composição do faturamento → gráficos → grid totalizável com drill-down
 * analítico. Somente leitura, qualquer papel (mesmo espírito de {@code vendas.pesquisa}).
 *
 * <p>Dataset-base é 1 linha por venda não cancelada dentro do filtro — mesma fórmula de valor
 * líquido usada em {@code vendas.pesquisa}/{@code vendas.cancelamento}. Vendedor e Operador de
 * Caixa são resolvidos como "1 por venda" (primeiro item do ledger / sessão de caixa que recebeu
 * o pagamento), nunca rateados por item.
 *
 * <p>Dados sujeitos ao RLS de tenant (P8).
 */
package com.vetor.niner.vendas.relatorio;
