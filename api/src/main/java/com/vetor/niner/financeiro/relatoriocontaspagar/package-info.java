/**
 * Relatório de Contas a Pagar / Pagas (docs/telas/relatorio-contas-pagar.md) — leitura sobre
 * {@code contas_pagar}, uma linha por duplicata, com KPIs, dois gráficos e subtotal por empresa.
 *
 * <p>⭐ Duas decisões que a próxima pessoa vai querer "consertar", e não deve:
 *
 * <ol>
 *   <li><b>"Paga" é {@code data_pagamento IS NOT NULL}, não {@code documento_pago}</b> — mesmo
 *       critério do Fluxo de Caixa. As duas colunas podem divergir, e a divergência é
 *       <b>sinalizada</b> em vez de resolvida em silêncio.</li>
 *   <li><b>NÃO respeita {@code cfg_plano_contas.inclui_dre}</b>, ao contrário da DRE e da
 *       Lucratividade — aqui a pergunta é "quanto sai do caixa", e compra de mercadoria é
 *       desembolso real.</li>
 * </ol>
 *
 * <p>⛔ Non-goals: não dá baixa (isso é a tela de Contas a Pagar), não projeta (Fluxo de Caixa) e
 * não calcula resultado (DRE / Lucratividade).
 */
package com.vetor.niner.financeiro.relatoriocontaspagar;
