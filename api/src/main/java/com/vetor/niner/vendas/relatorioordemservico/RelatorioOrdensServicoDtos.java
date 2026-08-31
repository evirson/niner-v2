package com.vetor.niner.vendas.relatorioordemservico;

import java.math.BigDecimal;
import java.util.List;

/** DTOs do Relatório de Ordens de Serviço (docs/telas/relatorio-ordem-servico.md). */
public final class RelatorioOrdensServicoDtos {

    private RelatorioOrdensServicoDtos() {
    }

    /**
     * O que entrou e o que saiu no período. ⚠️ Cada contador conta pela SUA data — abertas por
     * {@code data_abertura}, concluídas por {@code data_conclusao}, faturadas por
     * {@code data_faturamento}, canceladas por {@code data_cancelamento}. Somá-los não faz
     * sentido e a tela não soma: são quatro fatos diferentes que por acaso cabem no mesmo período.
     */
    public record MovimentoOrdens(
            int qtdAbertas,
            int qtdConcluidas,
            int qtdFaturadas,
            int qtdCanceladas,
            BigDecimal valorFaturado,
            BigDecimal valorDesconto,
            BigDecimal ticketMedio,
            /** ⚠️ NULO quando não houve OS concluída no período — "não há o que medir" não é zero,
             *  e só o nulo pode virar "—" na tela. */
            BigDecimal tempoMedioHoras) {
    }

    /**
     * Uma linha por (empresa, executor), das OS <b>concluídas</b> no período e não canceladas.
     *
     * <p>{@code idFuncionario == 0} é a linha <b>"(SEM EXECUTOR)"</b>: o item da OS pode não ter
     * executor atribuído (peça normalmente não tem), e filtrar esses itens faria o total por
     * executor deixar de fechar com o total geral sem nada na tela explicando.
     *
     * <p>Os valores são <b>brutos</b> — o desconto do cabeçalho não é rateado por executor, porque
     * é concessão de quem fechou o negócio, não de quem trabalhou.
     */
    public record LinhaExecutor(
            long idEmpresa,
            String nomeEmpresa,
            long idFuncionario,
            String nomeFuncionario,
            int qtdOrdens,
            BigDecimal valorServicos,
            BigDecimal valorPecas,
            BigDecimal valorTotal,
            /** ⚠️ Nulo = sem medida. Ver {@code MovimentoOrdens#tempoMedioHoras}. */
            BigDecimal tempoMedioHoras) {
    }

    public record SubtotalEmpresaOrdens(
            long idEmpresa,
            String nomeEmpresa,
            int qtdOrdens,
            BigDecimal valorServicos,
            BigDecimal valorPecas,
            BigDecimal valorTotal) {
    }

    /** ⚠️ {@code qtdOrdens} aqui é de OS <b>distintas</b>, não a soma das linhas: uma OS com dois
     *  executores conta 1 no total e 1 para cada um. Sem esse aviso a coluna parece não fechar. */
    public record TotalGeralOrdens(
            int qtdOrdens,
            BigDecimal valorServicos,
            BigDecimal valorPecas,
            BigDecimal valorTotal) {
    }

    public record RelatorioOrdensServicoResponse(
            MovimentoOrdens movimento,
            List<LinhaExecutor> linhas,
            List<SubtotalEmpresaOrdens> subtotaisPorEmpresa,
            TotalGeralOrdens totalGeral) {
    }
}
