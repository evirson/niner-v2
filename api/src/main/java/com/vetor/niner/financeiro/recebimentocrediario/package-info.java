/**
 * Recebimento de Crediário (2026-07-29) — busca o cliente (nome/CPF/celular), lista as
 * parcelas de crediário em aberto ({@code tipo_carteira.categoria_carteira = 'CREDIARIO'}) com
 * multa/juros calculados a partir de {@code cfg_geral} e da carência de cada um, e efetiva o
 * recebimento de uma ou várias parcelas selecionadas numa única transação: baixa {@code
 * contas_receber}, grava o detalhe de cartão quando aplicável ({@code
 * contas_receber_detalhe}), lança em {@code caixa_detalhe} (exige caixa aberto — ver {@code
 * financeiro.caixa.CaixaService}, 2026-07-30; antes desta data abria o caixa sozinho, em
 * silêncio, sempre com saldo zero) e agrupa tudo sob um {@code contas_receber_lote} (cabeçalho
 * que dá número real a {@code id_lote_recebimento}).
 *
 * <p>Só tipos de carteira com {@code permite_receber_crediario = true} e categoria AVISTA/
 * CARTAO_DEBITO/CARTAO_CREDITO podem quitar parcela aqui — nunca crediário pagando crediário.
 * {@code perc_desconto}/{@code perc_acrescimo} do tipo de carteira NÃO entram nesta rotina
 * (RN008) — o valor recebido é sempre o valor exato da parcela (original + multa + juros).
 *
 * <p>Dados sujeitos ao RLS de tenant (V025/V024, P8).
 */
package com.vetor.niner.financeiro.recebimentocrediario;
