/**
 * Relatório de Comissões (docs/telas/relatorio-comissoes.md) — filtros (empresa/período) → grid
 * banda clássica: uma linha por (empresa, funcionário) com valor de venda, valor de devolução,
 * valor líquido, % de comissão (`funcionario.perc_comissao`, puramente cadastral) e valor de
 * comissão calculado ({@code valorLiquido × percComissao / 100}). Subtotal por empresa + total
 * geral, sempre calculados no backend (P4). Sem KPIs nem gráficos — mais simples que o Relatório
 * de Vendas, que serviu de referência só pro padrão de tela (filtros-bar, PDF por captura
 * visual), não pro conteúdo.
 *
 * <p>Nenhuma comissão é de fato "paga"/lançada em lugar nenhum do sistema — é só um cálculo de
 * consulta em cima de dado já existente (mesma ressalva de {@code CancelamentoVendaService}/
 * {@code DevolucaoProdutoService}: comissão nunca existiu como lançamento real neste ERP).
 */
package com.vetor.niner.vendas.relatoriocomissao;
