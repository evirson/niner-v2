/**
 * Relatório de Contas a Receber / Recebidas (docs/telas/relatorio-contas-receber.md) — uma linha
 * por parcela de {@code contas_receber} das categorias {@code CARTAO_DEBITO}, {@code
 * CARTAO_CREDITO} e {@code CREDIARIO} (À Vista e Vale-Mercadoria ficam de fora: nascem sempre
 * quitadas na hora, não fazem sentido num relatório de "a receber"). Três filtros de período
 * independentes (venda/vencimento/recebimento, cada um opcional, mas pelo menos um obrigatório),
 * mais empresa. {@code valorBruto} é sempre {@code contas_receber.valor_receber} (o nominal da
 * parcela); {@code taxaAdministrativa} vem de {@code tipo_carteira.taxa_administradora} —
 * **sempre o valor atual do cadastro**, não uma foto do que vigorava na data da venda (mesma
 * limitação já aceita para {@code funcionario.perc_comissao} no Relatório de Comissões, nenhuma
 * das duas fica "congelada" por transação neste ERP). Crediário não tem taxa administrativa
 * (sempre 0), então {@code valorLiquido == valorBruto} nessas linhas. Subtotal por empresa +
 * total geral calculados no backend (P4), mesmo padrão do Relatório de Comissões.
 */
package com.vetor.niner.vendas.relatoriocontasreceber;
