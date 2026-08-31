/**
 * Relatório de Ordens de Serviço (docs/telas/relatorio-ordem-servico.md) — fecha a lacuna #56.
 *
 * <p>Duas perguntas, <b>dois eixos de data</b>: o bloco de <i>Movimento</i> conta cada evento pela
 * sua própria data (aberta/concluída/faturada/cancelada) e a grade de <i>produtividade</i> conta
 * pelo trabalho <b>entregue</b> ({@code data_conclusao}). Uma OS aberta em julho e concluída em
 * agosto é movimento de julho e produção de agosto — eixo único faria o relatório mentir sobre uma
 * das duas perguntas, sem nada na tela denunciando.
 *
 * <p>Agrupa pelo executor do <b>item</b>, nunca pelo funcionário do cabeçalho (que é quem atendeu).
 * Não calcula comissão: quem paga é o Relatório de Comissões, e dois cálculos do mesmo conceito
 * divergem no dia em que só um for corrigido.
 */
package com.vetor.niner.vendas.relatorioordemservico;
