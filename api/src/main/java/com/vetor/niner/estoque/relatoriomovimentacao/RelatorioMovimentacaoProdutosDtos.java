package com.vetor.niner.estoque.relatoriomovimentacao;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do Relatório de Movimentação de Produtos (Kardex) — ver {@code package-info.java} pro
 *  resumo de escopo. */
public final class RelatorioMovimentacaoProdutosDtos {

    private RelatorioMovimentacaoProdutosDtos() {
    }

    public enum ModeloRelatorioMovimentacao { ANALITICO, KARDEX, SINTETICO }

    /** Espelha o ENUM {@code tipo_movimento} do Postgres (V013). COMPRA e RESERVA/
     *  LIBERACAO_RESERVA ainda não têm nenhuma tela que grave esses tipos — o relatório já nasce
     *  preparado pra eles mesmo assim. */
    public enum TipoMovimentoProduto {
        COMPRA, TRANSFERENCIA, DEVOLUCAO, AJUSTE, VENDA, RESERVA, LIBERACAO_RESERVA, CANCELAMENTO,
        CANCELAMENTO_DEVOLUCAO
    }

    /** Uma linha por movimento (Analítico). {@code movimentoFisico} = falso só para RESERVA/
     *  LIBERACAO_RESERVA (não tiram/põem produto físico — ver package-info); {@code documento}
     *  já vem contextualizado por tipo (Java, não o front — P4). */
    public record LinhaAnalitica(
            long idEmpresa, String nomeEmpresa, OffsetDateTime dataMovimento, String tipoMovimento,
            boolean movimentoFisico, long idVariacao, String sku, String descricaoProduto, String marca,
            String variacaoCor, String variacaoTamanho, BigDecimal entrada, BigDecimal saida,
            BigDecimal custoUnitario, BigDecimal valorMovimentado, String documento, String nomeFuncionario) {
    }

    public record CabecalhoKardex(
            long idVariacao, String sku, String descricaoProduto, String marca,
            String variacaoCor, String variacaoTamanho, long idEmpresa, String nomeEmpresa,
            BigDecimal saldoInicial, BigDecimal saldoFinal) {
    }

    public record LinhaKardex(
            OffsetDateTime dataMovimento, String tipoMovimento, boolean movimentoFisico, String documento,
            String nomeFuncionario, BigDecimal entrada, BigDecimal saida, BigDecimal saldoApos) {
    }

    public record LinhaSintetica(
            String tipoMovimento, boolean movimentoFisico,
            BigDecimal qtdEntrada, BigDecimal qtdSaida, BigDecimal valorEntrada, BigDecimal valorSaida) {
    }

    /** Par rótulo/valor genérico — mesmo padrão de {@code PontoGrafico} do Relatório de Vendas. */
    public record PontoGrafico(String rotulo, BigDecimal valor) {
    }

    public record Graficos(
            List<PontoGrafico> porTipo, List<PontoGrafico> porDia, List<PontoGrafico> topAjustesNegativos) {
    }

    /** Só considera tipos com {@code movimentoFisico = true} (exclui RESERVA/LIBERACAO_RESERVA —
     *  ver package-info). */
    public record Kpis(
            BigDecimal qtdEntradaFisica, BigDecimal qtdSaidaFisica,
            BigDecimal valorEntradaFisica, BigDecimal valorSaidaFisica, BigDecimal saldoLiquidoFisico) {
    }

    /** Resultado de busca de variação pro seletor do Kardex (popup {@code PesquisaVariacaoModal}). */
    public record VariacaoEncontrada(
            long idVariacao, String sku, String descricaoProduto, String marca,
            String variacaoCor, String variacaoTamanho) {
    }

    /** Só os campos do {@code modelo} pedido vêm preenchidos — mesmo padrão do Relatório de
     *  Estoque (campo discriminador + listas/objetos alternativos). {@code kpis}/{@code
     *  graficos} só valem pra ANALITICO/SINTETICO (nulos no Kardex, que tem seu próprio
     *  cabeçalho com saldo inicial/final). */
    public record RelatorioMovimentacaoProdutosResponse(
            ModeloRelatorioMovimentacao modelo,
            Kpis kpis,
            Graficos graficos,
            List<LinhaAnalitica> linhasAnalitico,
            CabecalhoKardex cabecalhoKardex,
            List<LinhaKardex> linhasKardex,
            List<LinhaSintetica> linhasSintetico) {
    }
}
