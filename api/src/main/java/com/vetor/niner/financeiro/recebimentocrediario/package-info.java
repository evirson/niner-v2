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
 * <p><b>O estorno mora neste mesmo pacote</b> ({@code RecebimentoCrediarioService.estornarLote}):
 * desfaz um <b>lote inteiro</b>, nunca uma parcela isolada — um lote pode cobrir parcelas de
 * vendas diferentes recebidas juntas, e o dono do produto exigiu que estornar uma exija estornar
 * todas. Reabre as parcelas, apaga o detalhe de cartão, os lançamentos de caixa do lote e o
 * próprio cabeçalho (apagar de verdade, mesmo padrão da exclusão de Transferência). Nunca mexe em
 * {@code caixa_mestre} — o caixa pode ter lançamentos de outros lotes do mesmo dia.
 *
 * <p><b>Caixa fechado bloqueia o estorno</b> (2026-08-14): o DELETE em {@code caixa_detalhe} era
 * incondicional e apagava, em silêncio, lançamento de um caixa já conferido — a conferência
 * gravada passava a afirmar um total que não existia mais. Hoje a primeira instrução do estorno é
 * {@link com.vetor.niner.financeiro.caixa.CaixaService#exigirCaixaAbertoParaDesfazer}, que
 * responde 409 mandando reabrir o caixa. Como a checagem roda <i>antes</i> do {@code FOR UPDATE}
 * e de qualquer DELETE/UPDATE, a recusa não desfaz nada pela metade.
 *
 * <p>Dados sujeitos ao RLS de tenant (V025/V024, P8).
 */
package com.vetor.niner.financeiro.recebimentocrediario;
