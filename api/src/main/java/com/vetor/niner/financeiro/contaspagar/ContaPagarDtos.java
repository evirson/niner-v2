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
     * De onde saiu o dinheiro ao dar baixa (2026-08-23, Fluxo de Caixa). Obrigatório quando
     * {@code dataPagamento} é informada — é o que faz o pagamento virar movimento de dinheiro de
     * verdade, e sem isso o fluxo de caixa realizado ficaria sem as saídas.
     */
    public enum OrigemPagamento {
        /** Dinheiro do caixa da loja — exige caixa aberto (mesma convenção de PDV/Recebimento). */
        CAIXA,
        /** Conta corrente do banco — exige {@code idContaCorrente}. */
        CONTA_CORRENTE
    }

    /**
     * {@code dataPagamento}/{@code valorPago}/{@code documentoPago} vêm juntos com os demais
     * campos — não existe endpoint separado de "dar baixa" (a tela só pediu Visualizar/Editar/
     * Excluir); editar uma conta preenchendo esses três campos É a baixa.
     *
     * <p>Desde 2026-08-23 a baixa também exige {@code origemPagamento} (+ {@code idContaCorrente}
     * quando for CONTA_CORRENTE) — ver {@code docs/telas/fluxo-caixa.md}.
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
            OrigemPagamento origemPagamento,
            @Size(max = 20) String idContaCorrente,
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
            /** De onde saiu o dinheiro, quando já baixada — derivado do movimento gerado. */
            OrigemPagamento origemPagamento,
            String idContaCorrente,
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
