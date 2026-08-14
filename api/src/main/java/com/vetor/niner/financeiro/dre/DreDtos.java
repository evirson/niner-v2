package com.vetor.niner.financeiro.dre;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Contratos do Relatório de DRE (docs/telas/relatorio-dre.md). */
public final class DreDtos {

    private DreDtos() {
    }

    /** Regime de reconhecimento — muda **quando** cada valor entra, nunca a estrutura de linhas. */
    public enum Regime {
        /** Fato gerador: a venda e o lançamento da despesa, independentemente do dinheiro. */
        COMPETENCIA,
        /** Movimento do dinheiro: parcela recebida e conta paga. */
        CAIXA
    }

    public enum Comparacao {
        NENHUM,
        /** Mesmo número de dias, terminando na véspera do início do período consultado. */
        PERIODO_ANTERIOR,
        /** Mesmo intervalo, um ano antes. */
        ANO_ANTERIOR
    }

    /** GRUPO e SUBTOTAL são calculados; CONTA é uma conta analítica com movimento no período. */
    public enum TipoLinha {
        GRUPO,
        CONTA,
        SUBTOTAL
    }

    /**
     * Uma linha da DRE. {@code valor} já vem com o sinal do efeito no resultado (dedução, custo e
     * despesa são negativos), então somar as linhas na ordem dá o resultado — a tela não precisa
     * saber a regra de sinal de cada grupo.
     *
     * @param percentualAv     % sobre a receita líquida do período (AV); {@code null} quando a
     *                         receita líquida é zero (não existe base para o percentual).
     * @param valorComparado   mesma linha no período de comparação; {@code null} sem comparação.
     * @param variacaoPercentual {@code null} quando o valor comparado é zero (variação infinita).
     */
    public record LinhaDre(
            String chave,
            String rotulo,
            int nivel,
            TipoLinha tipo,
            BigDecimal valor,
            BigDecimal percentualAv,
            BigDecimal valorComparado,
            BigDecimal variacaoAbsoluta,
            BigDecimal variacaoPercentual) {
    }

    public record PeriodoDre(LocalDate dataInicial, LocalDate dataFinal) {
    }

    public record DreResponse(
            Regime regime,
            PeriodoDre periodo,
            PeriodoDre periodoComparado,
            List<LinhaDre> linhas,
            BigDecimal receitaLiquida,
            BigDecimal resultadoLiquido) {
    }
}
