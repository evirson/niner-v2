package com.vetor.niner.financeiro.lucratividade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Contratos do Relatório de Lucratividade (docs/telas/relatorio-lucratividade.md). */
public final class LucratividadeDtos {

    private LucratividadeDtos() {
    }

    public record PeriodoLucratividade(LocalDate dataInicial, LocalDate dataFinal) {
    }

    /**
     * Uma conta analítica com pagamento no período (item 5 do relatório).
     *
     * @param percentualSobreVenda      {@code null} quando a venda líquida é zero.
     * @param percentualSobreLucroBruto {@code null} quando o lucro bruto é zero. ⚠️ Também é
     *                                  {@code null} — e não negativo — quando o lucro bruto é
     *                                  negativo: "esta despesa é 40% de um prejuízo" não é uma
     *                                  frase com significado, e imprimi-la faria o lojista somar
     *                                  percentuais que não somam.
     */
    public record ContaPaga(
            String idPlanoContas,
            String descricao,
            BigDecimal valor,
            BigDecimal percentualSobreVenda,
            BigDecimal percentualSobreLucroBruto) {
    }

    /**
     * O relatório inteiro. Os nomes seguem o relatório impresso: {@code vendaLiquida} é o
     * <b>item 1</b> ("valor total da venda" = venda − devoluções), e é a base do % de lucro bruto
     * e do % de despesa. {@code vendaBruta} existe só para o segundo percentual do item 6.
     *
     * <p>Todo percentual é {@code null} quando a base é zero — nunca {@code 0}. Um zero ali
     * afirmaria "margem zero" onde na verdade não houve venda nenhuma.
     */
    public record LucratividadeResponse(
            PeriodoLucratividade periodo,
            BigDecimal vendaBruta,
            BigDecimal devolucoes,
            BigDecimal vendaLiquida,
            BigDecimal custoMercadoriaVendida,
            BigDecimal lucroBruto,
            BigDecimal percentualLucroBruto,
            List<ContaPaga> contasPagas,
            BigDecimal totalContasPagas,
            BigDecimal lucroLiquido,
            BigDecimal percentualSobreVendaBruta,
            BigDecimal percentualSobreVendaLiquida) {
    }
}
