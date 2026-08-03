package com.vetor.niner.vendas.relatoriocomissao;

import java.math.BigDecimal;
import java.util.List;

/** DTOs do Relatório de Comissões (docs/telas/relatorio-comissoes.md). */
public final class RelatorioComissoesDtos {

    private RelatorioComissoesDtos() {
    }

    /** Uma linha por (empresa, funcionário) — só aparece se houve venda ou devolução dele
     *  naquela empresa dentro do período. */
    public record LinhaComissao(
            long idEmpresa,
            String nomeEmpresa,
            long idFuncionario,
            String nomeFuncionario,
            BigDecimal valorVenda,
            BigDecimal valorDevolucao,
            BigDecimal valorLiquido,
            BigDecimal percComissao,
            BigDecimal valorComissao) {
    }

    public record SubtotalEmpresa(
            long idEmpresa,
            String nomeEmpresa,
            BigDecimal valorVenda,
            BigDecimal valorDevolucao,
            BigDecimal valorLiquido,
            BigDecimal valorComissao) {
    }

    public record TotalGeralComissao(
            BigDecimal valorVenda,
            BigDecimal valorDevolucao,
            BigDecimal valorLiquido,
            BigDecimal valorComissao) {
    }

    /** {@code linhas} já vem ordenada — por nome do funcionário quando há só uma empresa no
     *  resultado, por empresa + nome do funcionário quando há mais de uma (ver
     *  {@code RelatorioComissoesService.buscarLinhas}, ordenação única que cobre os dois casos).
     *  {@code subtotaisPorEmpresa} só é renderizado pelo front quando tem mais de uma empresa. */
    public record RelatorioComissoesResponse(
            List<LinhaComissao> linhas,
            List<SubtotalEmpresa> subtotaisPorEmpresa,
            TotalGeralComissao totalGeral) {
    }
}
