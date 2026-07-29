package com.vetor.niner.estoque.transferencia;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs de transferência de estoque entre empresas (docs/telas/transferencia-estoque.md). */
public final class TransferenciaDtos {

    private TransferenciaDtos() {
    }

    public record ItemTransferenciaRequest(
            @NotNull Long idVariacao,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qtd) {
    }

    /**
     * {@code idEmpresaDestino} é a única empresa que o operador escolhe — a origem é sempre a
     * empresa ativa da sessão (claim {@code eid} do JWT), nunca vem do corpo da requisição.
     */
    public record CriarTransferenciaRequest(
            @NotNull Long idEmpresaDestino,
            @NotEmpty List<@Valid ItemTransferenciaRequest> itens,
            @Size(max = 500) String observacoes) {
    }

    public record EmpresaResumo(long idEmpresa, String nomeEmpresa) {
    }

    public record ItemTransferenciaResponse(
            long idVariacao,
            String descricaoProduto,
            String variacaoLinha,
            String variacaoColuna,
            String sku,
            BigDecimal qtd) {
    }

    public record TransferenciaResponse(
            long idTransferencia,
            EmpresaResumo empresaOrigem,
            EmpresaResumo empresaDestino,
            String nomeUsuario,
            OffsetDateTime dataTransferencia,
            String observacoes,
            List<ItemTransferenciaResponse> itens) {
    }

    /** Listagem paginada por número de página — mesmo padrão de {@code cadastros.funcionario}. */
    public record PaginaTransferencias(
            List<TransferenciaResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }
}
