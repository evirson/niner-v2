package com.vetor.niner.vendas.pesquisa;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs da Pesquisa de Vendas (docs/telas/pesquisa-vendas.md). */
public final class PesquisaVendaDtos {

    private PesquisaVendaDtos() {
    }

    public record VendaPesquisaResponse(
            long idVenda,
            long idEmpresa,
            String nomeEmpresa,
            OffsetDateTime dataVenda,
            Long idCliente,
            String nomeCliente,
            Long idFuncionario,
            String nomeFuncionario,
            BigDecimal valorVenda,
            boolean cancelada) {
    }

    /** {@code totalItensAtivos}/{@code somaValorAtivas} cobrem TODO o resultado filtrado (não só
     *  a página atual), sempre excluindo canceladas — é o número exibido no rodapé da grid. */
    public record PaginaVendasPesquisa(
            List<VendaPesquisaResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas,
            long totalItensAtivos, BigDecimal somaValorAtivas) {
    }

    public record ItemVendaPesquisaResponse(
            String codigo, String descricaoProduto, String variacaoLinha, String variacaoColuna,
            BigDecimal qtd, BigDecimal valorUnitario, BigDecimal valorDesconto, BigDecimal valorItem) {
    }

    public record MovimentoCaixaPesquisaResponse(
            OffsetDateTime dataHora, String tipoOperacao, String nomeCarteira, String origem,
            String creditoDebito, BigDecimal valor) {
    }

    /** {@code situacao}: ABERTA / PAGA / VENCIDA — sempre calculada na hora, nunca lida de uma
     *  coluna (vencimento no passado sem pagamento = vencida). */
    public record ParcelaPesquisaResponse(
            int numeroParcela, int totalParcelas, OffsetDateTime dataVencimento, BigDecimal valor,
            String situacao, OffsetDateTime dataPagamento, BigDecimal valorPago, BigDecimal valorJuros) {
    }

    public record VendaDetalhePesquisaResponse(
            long idVenda,
            long idEmpresa,
            String nomeEmpresa,
            OffsetDateTime dataVenda,
            Long idCliente,
            String nomeCliente,
            String cpfCnpj,
            Boolean fisicaJuridica,
            Long idFuncionario,
            String nomeFuncionario,
            String condicaoPagamento,
            BigDecimal desconto,
            BigDecimal valorTotal,
            BigDecimal recebido,
            BigDecimal aReceber,
            boolean cancelada,
            OffsetDateTime dataCancelamento,
            String nomeUsuarioCancelamento,
            String motivoCancelamento,
            List<ItemVendaPesquisaResponse> itens,
            List<MovimentoCaixaPesquisaResponse> movimentosCaixa,
            boolean temParcelasCredario,
            List<ParcelaPesquisaResponse> parcelas) {
    }
}
