package com.vetor.niner.financeiro.relatoriocontaspagar;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do Relatório de Contas a Pagar / Pagas (docs/telas/relatorio-contas-pagar.md). */
public final class RelatorioContasPagarDtos {

    private RelatorioContasPagarDtos() {
    }

    /**
     * Uma linha por duplicata.
     *
     * @param valorEmAberto {@code valor_pagar − valor_pago}. Pagamento parcial existe, e tratar a
     *                      conta como binária esconderia o saldo devedor
     * @param situacao      {@code PAGA} / {@code VENCIDA} / {@code A_VENCER}
     * @param divergente    ⚠️ {@code documento_pago} e {@code data_pagamento} discordam. As duas
     *                      colunas existem e o CRUD deixa divergirem; o relatório <b>mostra</b>
     *                      em vez de escolher em silêncio qual está certa
     */
    public record LinhaContaPagar(
            long idContaPagar,
            long idEmpresa,
            String nomeEmpresa,
            long idFornecedor,
            String nomeFornecedor,
            String idPlanoContas,
            String descricaoPlanoContas,
            Integer notaFiscal,
            String numeroDuplicata,
            OffsetDateTime dataLancamento,
            OffsetDateTime dataVencimento,
            OffsetDateTime dataPagamento,
            BigDecimal valorPagar,
            BigDecimal valorPago,
            BigDecimal valorEmAberto,
            boolean documentoPago,
            String situacao,
            boolean divergente) {
    }

    public record SubtotalEmpresaContaPagar(
            long idEmpresa,
            String nomeEmpresa,
            BigDecimal valorPagar,
            BigDecimal valorPago,
            BigDecimal valorEmAberto) {
    }

    public record TotalGeralContaPagar(
            BigDecimal valorPagar,
            BigDecimal valorPago,
            BigDecimal valorEmAberto) {
    }

    /**
     * Os cinco números do topo.
     *
     * <p>⚠️ <b>{@code vencido} + {@code aVencer} = {@code emAberto}</b>, e <b>nunca</b>
     * {@code totalPeriodo}. Somar os três daria o dobro do que a loja deve — por isso eles são
     * campos separados, e a tela nunca os agrega num "total de problemas". Mesmo raciocínio dos
     * três contadores do painel de saúde dos canais.
     */
    public record KpisContaPagar(
            BigDecimal totalPeriodo,
            BigDecimal emAberto,
            BigDecimal vencido,
            BigDecimal aVencer,
            BigDecimal pagoNoPeriodo) {
    }

    /**
     * Uma fatia de gráfico.
     *
     * <p>⚠️ Soma {@code valor_pagar}, <b>não</b> o valor em aberto: a pergunta é "em que / para
     * quem eu comprometi dinheiro no período", e um gráfico que encolhesse conforme se paga
     * responderia outra coisa.
     */
    public record FatiaGrafico(String rotulo, BigDecimal valor) {
    }

    public record RelatorioContasPagarResponse(
            List<LinhaContaPagar> linhas,
            List<SubtotalEmpresaContaPagar> subtotaisPorEmpresa,
            TotalGeralContaPagar totalGeral,
            KpisContaPagar kpis,
            List<FatiaGrafico> graficoPorPlanoContas,
            List<FatiaGrafico> graficoPorFornecedor) {
    }
}
