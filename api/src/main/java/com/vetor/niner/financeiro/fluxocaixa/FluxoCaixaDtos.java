package com.vetor.niner.financeiro.fluxocaixa;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Contratos do Fluxo de Caixa (docs/telas/fluxo-caixa.md) — realizado e projeção. */
public final class FluxoCaixaDtos {

    private FluxoCaixaDtos() {
    }

    /** Filtro de onde o dinheiro está — a tela deixa olhar só o caixa ou só o banco. */
    public enum OrigemDinheiro {
        TODAS,
        CAIXA,
        CONTA_CORRENTE
    }

    public enum Agrupamento {
        DIA,
        SEMANA,
        MES
    }

    /** Uma conta dentro de uma atividade (operacional/investimento/financiamento). */
    public record LinhaAtividade(String chave, String rotulo, BigDecimal valor) {
    }

    /** {@code valor} já vem com sinal: entrada positiva, saída negativa. */
    public record Atividade(String grupo, String rotulo, BigDecimal total, List<LinhaAtividade> linhas) {
    }

    /**
     * @param saldoInicial        tudo que entrou/saiu ANTES do período
     * @param saldoFinal          saldoInicial + entradas − saídas do período
     * @param saldoRealAtual      saldo de hoje (caixa + conta corrente), para a conciliação
     * @param diferencaConciliacao {@code saldoFinal − saldoRealAtual} quando o período termina
     *                            hoje ou depois; {@code null} quando o período é antigo (aí não
     *                            faz sentido comparar com o saldo de hoje)
     */
    public record FluxoRealizadoResponse(
            LocalDate dataInicial,
            LocalDate dataFinal,
            BigDecimal saldoInicial,
            List<Atividade> atividades,
            BigDecimal totalEntradas,
            BigDecimal totalSaidas,
            BigDecimal saldoFinal,
            BigDecimal saldoRealAtual,
            BigDecimal diferencaConciliacao) {
    }

    /**
     * Uma faixa da projeção (dia, semana ou mês).
     *
     * @param emAtraso true no balde que carrega os vencidos — sem isso o saldo projetado mentiria
     *                 sobre o presente, jogando dívida vencida para a data original
     */
    public record LinhaProjecao(
            LocalDate data,
            String rotulo,
            BigDecimal entradas,
            BigDecimal saidas,
            BigDecimal saldoPeriodo,
            BigDecimal saldoAcumulado,
            boolean emAtraso) {
    }

    /**
     * @param primeiraDataNegativa primeira faixa em que o acumulado fica negativo; {@code null} se
     *                             nunca fica — é o alerta que justifica a aba existir
     * @param valorFaltante        quanto falta nessa data (valor absoluto do saldo negativo)
     */
    public record FluxoProjecaoResponse(
            LocalDate dataInicial,
            LocalDate dataFinal,
            BigDecimal saldoAtual,
            List<LinhaProjecao> linhas,
            BigDecimal totalEntradasPrevistas,
            BigDecimal totalSaidasPrevistas,
            BigDecimal saldoProjetadoFinal,
            LocalDate primeiraDataNegativa,
            BigDecimal valorFaltante) {
    }
}
