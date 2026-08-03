package com.vetor.niner.estoque.relatorioestoque;

import java.math.BigDecimal;
import java.util.List;

/** DTOs do Relatório de Estoque — ver {@code package-info.java} pro resumo de escopo. */
public final class RelatorioEstoqueDtos {

    private RelatorioEstoqueDtos() {
    }

    public enum ModeloRelatorioEstoque { INVENTARIO, SINTETICO, ANALITICO }

    public enum TipoQuantidade { TODOS, DIFERENTE_DE_ZERO, ZERADA }

    public enum SituacaoProduto { ATIVOS, INATIVOS, TODOS }

    /** Uma coluna de empresa nos modelos Sintético/Analítico — mesma ordem usada em
     *  {@code qtdPorEmpresa} de cada linha (posicional, não por chave). */
    public record ColunaEmpresa(long idEmpresa, String nomeEmpresa) {
    }

    public record LinhaInventario(
            String descricaoProduto, String marca, String referencia,
            BigDecimal qtdTotal, BigDecimal custoUnitario, BigDecimal custoTotal) {
    }

    public record LinhaSintetica(
            String descricaoProduto, String marca, String referencia,
            List<BigDecimal> qtdPorEmpresa, BigDecimal qtdTotal) {
    }

    public record LinhaAnalitica(
            String descricaoProduto, String marca, String referencia,
            String variacaoLinha, String variacaoColuna,
            List<BigDecimal> qtdPorEmpresa, BigDecimal qtdTotal) {
    }

    public record TotalInventario(BigDecimal qtdTotal, BigDecimal custoTotal) {
    }

    public record TotalSintetico(List<BigDecimal> qtdPorEmpresa, BigDecimal qtdTotal) {
    }

    /** Só os campos do {@code modelo} solicitado vêm preenchidos — mesmo padrão do
     *  {@code Totalizador} do Relatório de Vendas (campo discriminador + listas alternativas). */
    public record RelatorioEstoqueResponse(
            ModeloRelatorioEstoque modelo,
            List<ColunaEmpresa> colunasEmpresa,
            List<LinhaInventario> linhasInventario,
            List<LinhaSintetica> linhasSintetico,
            List<LinhaAnalitica> linhasAnalitico,
            TotalInventario totalInventario,
            TotalSintetico totalSintetico) {
    }
}
