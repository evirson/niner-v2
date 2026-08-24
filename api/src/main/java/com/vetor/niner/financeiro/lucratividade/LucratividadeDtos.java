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
     * Uma linha de despesa do período (item 5 do relatório), agrupada por conta analítica.
     *
     * @param derivada                  {@code false} = veio de {@code contas_pagar}, com data de
     *                                  <b>pagamento</b> no período. {@code true} = <b>calculada do
     *                                  movimento</b> (comissão e taxa de cartão), porque não existe
     *                                  lançamento em Contas a Pagar para elas. ⚠️ Linha derivada
     *                                  conta pela data da <b>venda</b> — é a única data que ela tem
     *                                  —, então o relatório mistura duas bases de data dentro da
     *                                  mesma tabela e a tela precisa dizer qual é qual.
     * @param percentualSobreVenda      {@code null} quando a venda líquida é zero.
     * @param percentualSobreLucroBruto {@code null} quando o lucro bruto é zero. ⚠️ Também é
     *                                  {@code null} — e não negativo — quando o lucro bruto é
     *                                  negativo: "esta despesa é 40% de um prejuízo" não é uma
     *                                  frase com significado, e imprimi-la faria o lojista somar
     *                                  percentuais que não somam.
     */
    public record LinhaDespesa(
            String idPlanoContas,
            String descricao,
            BigDecimal valor,
            boolean derivada,
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
            List<LinhaDespesa> despesas,
            BigDecimal totalDespesas,
            BigDecimal lucroLiquido,
            BigDecimal percentualSobreVendaBruta,
            BigDecimal percentualSobreVendaLiquida) {
    }
}
