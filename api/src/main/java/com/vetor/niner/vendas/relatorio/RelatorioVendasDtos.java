package com.vetor.niner.vendas.relatorio;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do Relatório de Vendas (docs/telas/relatorio-vendas.md). */
public final class RelatorioVendasDtos {

    private RelatorioVendasDtos() {
    }

    public enum TotalizarPor { NAO_TOTALIZAR, DATA_VENDA, CLIENTE, VENDEDOR, OPERADOR_CAIXA, EMPRESA }

    public record Kpis(
            BigDecimal ticketMedioValor, long ticketMedioNVendas,
            BigDecimal percentualMedioDesconto, BigDecimal valorDesconto,
            BigDecimal percentualDevolucao, BigDecimal valorDevolucao,
            BigDecimal itensVendidos, BigDecimal mediaItensPorVenda) {
    }

    public record ComposicaoFaturamento(
            BigDecimal valorBruto, BigDecimal descontos, BigDecimal acrescimos,
            BigDecimal devolucoes, BigDecimal vendaLiquida) {
    }

    /** Par rótulo/valor genérico — usado pelos gráficos de série única (dia, marca, vendedor,
     *  cliente, hora, dia da semana). */
    public record PontoGrafico(String rotulo, BigDecimal valor) {
    }

    /** Linha do gráfico "por carteira" — nome e categoria separados (não um rótulo já pronto),
     *  porque a mesma bandeira pode existir em mais de uma categoria (ex.: "HIPER" em Cartão
     *  Débito e em Cartão Crédito, cada uma com seu próprio saldo) e precisam aparecer como
     *  barras distintas. O front combina os dois em texto (mesmo `rotuloCarteira()` já usado no
     *  Fechamento de Caixa) — o backend só entrega os dados, não a formatação (P4). */
    public record LinhaCarteiraGrafico(String nomeCarteira, String categoriaCarteira, BigDecimal valor) {
    }

    public record Graficos(
            List<PontoGrafico> porDia,
            List<PontoGrafico> topMarcas,
            List<PontoGrafico> topVendedores,
            List<PontoGrafico> topClientes,
            List<LinhaCarteiraGrafico> porCarteira,
            List<PontoGrafico> porHora,
            List<PontoGrafico> porDiaSemana) {
    }

    public record LinhaAgrupada(String chave, String nome, long nVendas, BigDecimal valorVenda) {
    }

    public record LinhaAnalitica(
            long idVenda, long idEmpresa, String nomeEmpresa, OffsetDateTime dataHoraVenda,
            String nomeCliente, String nomeVendedor, String nomeOperador, BigDecimal qtdProdutos,
            BigDecimal valorVenda, BigDecimal acrescimos, BigDecimal descontos, BigDecimal valorLiquido) {
    }

    /** {@code tipo}: {@code ANALITICO} (Não Totalizar — {@code linhasAnaliticas} já é a grid) ou
     *  {@code AGRUPADO} ({@code linhasAgrupadas} é a grid, clique numa linha busca o drill-down
     *  analítico daquele grupo em {@code /detalhe}). */
    public record Totalizador(String tipo, List<LinhaAgrupada> linhasAgrupadas, List<LinhaAnalitica> linhasAnaliticas) {
    }

    public record RelatorioVendasResponse(
            Kpis kpis, ComposicaoFaturamento composicaoFaturamento, Graficos graficos, Totalizador totalizador) {
    }

    public record DetalheTotalizadorResponse(List<LinhaAnalitica> itens) {
    }
}
