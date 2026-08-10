package com.vetor.niner.vendas.cancelamentodevolucao;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do Cancelamento de Devolução de Produtos. */
public final class CancelamentoDevolucaoDtos {

    private CancelamentoDevolucaoDtos() {
    }

    /** Uma linha da grade de resultados — só vales ainda não usados e ainda não cancelados
     *  aparecem por padrão (RN do dono do produto: "só existe cancelamento se o vale ainda não
     *  foi usado"); a busca direta por número mostra o vale mesmo que já usado/cancelado, para
     *  o operador entender por que não pode cancelar. */
    public record DevolucaoParaCancelamentoResponse(
            long idDevolucao,
            long idEmpresa,
            String nomeEmpresa,
            OffsetDateTime dataDevolucao,
            BigDecimal valorVale,
            Long idVendaCredito,
            boolean valeUsado,
            boolean cancelada) {
    }

    public record PaginaDevolucoesCancelamento(
            List<DevolucaoParaCancelamentoResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    public record ItemDevolucaoDetalhe(
            String descricaoProduto, String variacaoCor, String variacaoTamanho,
            BigDecimal qtd, BigDecimal precoVenda, BigDecimal valorItem) {
    }

    public record DevolucaoDetalheCancelamentoResponse(
            long idDevolucao,
            long idEmpresa,
            String nomeEmpresa,
            OffsetDateTime dataDevolucao,
            BigDecimal valorVale,
            Long idVendaCredito,
            boolean valeUsado,
            boolean cancelada,
            OffsetDateTime dataCancelamento,
            String nomeUsuarioCancelamento,
            String motivoCancelamento,
            List<ItemDevolucaoDetalhe> itens) {
    }

    public record CancelarDevolucaoRequest(@NotBlank String motivo) {
    }

    public record CancelamentoDevolucaoEfetivadoResponse(long idDevolucao, OffsetDateTime dataCancelamento) {
    }
}
