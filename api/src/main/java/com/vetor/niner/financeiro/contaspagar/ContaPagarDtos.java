package com.vetor.niner.financeiro.contaspagar;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs de Contas a Pagar / Pagas (docs/telas/contas-pagar.md). */
public final class ContaPagarDtos {

    private ContaPagarDtos() {
    }

    /**
     * {@code dataPagamento}/{@code valorPago}/{@code documentoPago} vêm juntos com os demais
     * campos — não existe endpoint separado de "dar baixa" (a tela só pediu Visualizar/Editar/
     * Excluir); editar uma conta preenchendo esses três campos É a baixa.
     */
    public record ContaPagarRequest(
            @NotNull Long idFornecedor,
            @NotNull Long idEmpresa,
            @NotBlank String idPlanoContas,
            Integer notaFiscal,
            @Size(max = 40) String numeroDuplicata,
            @NotNull OffsetDateTime dataLancamento,
            @NotNull OffsetDateTime dataVencimento,
            OffsetDateTime dataPagamento,
            @NotNull @DecimalMin(value = "0.01") BigDecimal valorPagar,
            BigDecimal valorPago,
            Boolean documentoPago,
            @Size(max = 1000) String observacoes) {
    }

    public record ContaPagarResponse(
            long idContaPagar,
            long idFornecedor,
            String nomeFornecedor,
            long idEmpresa,
            String nomeEmpresa,
            String idPlanoContas,
            String descricaoPlanoContas,
            Integer notaFiscal,
            String numeroDuplicata,
            OffsetDateTime dataLancamento,
            OffsetDateTime dataVencimento,
            OffsetDateTime dataPagamento,
            BigDecimal valorPagar,
            BigDecimal valorPago,
            boolean documentoPago,
            String observacoes,
            Long idMovimento,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por número de página — mesmo padrão de {@code cadastros.cliente}. */
    public record PaginaContasPagar(
            List<ContaPagarResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** Sem fallback de inativar — {@code contas_pagar} não tem {@code ativo}, nada referencia
     *  esta tabela (mesmo caso de {@code conta_corrente_movimento}), exclusão é sempre definitiva. */
    public record ExclusaoContaPagarResponse(String acao) {
    }
}
