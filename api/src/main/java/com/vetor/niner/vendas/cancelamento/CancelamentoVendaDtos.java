package com.vetor.niner.vendas.cancelamento;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do Cancelamento de Venda (docs/telas/cancelamento-venda.md). */
public final class CancelamentoVendaDtos {

    private CancelamentoVendaDtos() {
    }

    /** Uma linha da grade de resultados. {@code bloqueadaCredario} evita um segundo round-trip
     *  só pra saber se o botão "Cancelar" deve nascer desabilitado (RN-03). */
    public record VendaParaCancelamentoResponse(
            long idVenda,
            long idEmpresa,
            String nomeEmpresa,
            OffsetDateTime dataVenda,
            Long idCliente,
            String nomeCliente,
            Long idFuncionario,
            String nomeFuncionario,
            BigDecimal valorVenda,
            boolean cancelada,
            boolean bloqueadaCredario) {
    }

    public record PaginaVendasCancelamento(
            List<VendaParaCancelamentoResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    public record ItemVendaDetalhe(
            String descricaoProduto, String variacaoCor, String variacaoTamanho,
            BigDecimal qtd, BigDecimal precoVenda, BigDecimal valorItem) {
    }

    public record PagamentoVendaDetalhe(
            long idCarteira, String nomeCarteira, String categoriaCarteira, BigDecimal valorPago, boolean quitado) {
    }

    /** Detalhe de uma parcela de crediário já recebida — só preenchido quando {@code
     *  bloqueadaCredario = true}, pro operador conferir sem sair da tela (RN-03). */
    public record ParcelaRecebidaDetalhe(
            int numeroParcela, OffsetDateTime dataVencimento, OffsetDateTime dataRecebimento, BigDecimal valorRecebido) {
    }

    /** {@code nfce*} vêm nulos quando a venda não tem NFC-e autorizada (F12/DF13) — nesse caso o
     *  cancelamento não depende de prazo nenhum da SEFAZ. Quando preenchidos,
     *  {@code nfcePrazoCancelamentoExpirado} já vem calculado pelo servidor (não pelo relógio do
     *  navegador) — é ele que a tela usa pra bloquear o botão, não uma conta feita no front. */
    public record VendaDetalheCancelamentoResponse(
            long idVenda,
            long idEmpresa,
            String nomeEmpresa,
            OffsetDateTime dataVenda,
            Long idCliente,
            String nomeCliente,
            Long idFuncionario,
            String nomeFuncionario,
            BigDecimal valorVenda,
            boolean cancelada,
            OffsetDateTime dataCancelamento,
            String nomeUsuarioCancelamento,
            String motivoCancelamento,
            List<ItemVendaDetalhe> itens,
            List<PagamentoVendaDetalhe> pagamentos,
            boolean bloqueadaCredario,
            List<ParcelaRecebidaDetalhe> parcelasRecebidas,
            OffsetDateTime nfceDataAutorizacao,
            Integer nfcePrazoCancelamentoMinutos,
            OffsetDateTime nfcePrazoCancelamentoExpiraEm,
            boolean nfcePrazoCancelamentoExpirado) {
    }

    public record CancelarVendaRequest(@NotBlank String motivo) {
    }

    public record CancelamentoEfetivadoResponse(long idVenda, OffsetDateTime dataCancelamento) {
    }
}
