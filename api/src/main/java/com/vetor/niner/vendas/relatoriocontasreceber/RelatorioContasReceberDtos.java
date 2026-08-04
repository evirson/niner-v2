package com.vetor.niner.vendas.relatoriocontasreceber;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do Relatório de Contas a Receber / Recebidas (docs/telas/relatorio-contas-receber.md). */
public final class RelatorioContasReceberDtos {

    private RelatorioContasReceberDtos() {
    }

    /** Uma linha por parcela. {@code dataRecebimento}/{@code nomeEmpresaPagamento} nulos =
     *  parcela ainda em aberto. {@code categoriaCarteira}: {@code CARTAO_DEBITO}, {@code
     *  CARTAO_CREDITO} ou {@code CREDIARIO} (únicas categorias que este relatório mostra).
     *  {@code totalParcelas} é o total de parcelas da mesma linha de pagamento (mesma venda +
     *  mesmo tipo de carteira) — junto com {@code numeroParcela} forma "01/06" na tela. */
    public record LinhaContaReceber(
            long idEmpresa,
            String nomeEmpresa,
            String nomeEmpresaPagamento,
            long idVenda,
            Long idCliente,
            String nomeCliente,
            String nomeCarteira,
            String categoriaCarteira,
            int numeroParcela,
            int totalParcelas,
            OffsetDateTime dataVenda,
            OffsetDateTime dataVencimento,
            OffsetDateTime dataRecebimento,
            BigDecimal valorBruto,
            BigDecimal taxaAdministrativa,
            BigDecimal valorTaxaAdministrativa,
            BigDecimal valorLiquido) {
    }

    public record SubtotalEmpresaContaReceber(
            long idEmpresa,
            String nomeEmpresa,
            BigDecimal valorBruto,
            BigDecimal valorTaxaAdministrativa,
            BigDecimal valorLiquido) {
    }

    public record TotalGeralContaReceber(
            BigDecimal valorBruto,
            BigDecimal valorTaxaAdministrativa,
            BigDecimal valorLiquido) {
    }

    /** Um ponto por carteira (nome + categoria, mesmo desambiguador de {@code
     *  LinhaCarteiraGrafico} do Relatório de Vendas — a mesma bandeira pode existir em mais de
     *  uma categoria) — soma de {@code valorLiquido} das parcelas do filtro atual, usado tanto
     *  pelo gráfico quanto pelos KPIs de vencido/a vencer. */
    public record PontoFormaPagamento(
            String nomeCarteira,
            String categoriaCarteira,
            BigDecimal valor) {
    }

    /** Só considera parcelas ainda não recebidas (dentro do filtro já aplicado na tela) —
     *  vencida = vencimento no passado, a vencer = vencimento hoje ou no futuro. */
    public record KpiFormaPagamento(
            String nomeCarteira,
            String categoriaCarteira,
            BigDecimal valorVencido,
            BigDecimal valorAVencer) {
    }

    public record RelatorioContasReceberResponse(
            List<LinhaContaReceber> linhas,
            List<SubtotalEmpresaContaReceber> subtotaisPorEmpresa,
            TotalGeralContaReceber totalGeral,
            List<PontoFormaPagamento> graficoPorFormaPagamento,
            List<KpiFormaPagamento> kpisPorFormaPagamento) {
    }
}
