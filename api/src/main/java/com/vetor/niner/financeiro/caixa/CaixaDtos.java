package com.vetor.niner.financeiro.caixa;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs de Abertura/Fechamento de Caixa (2026-07-30). */
public final class CaixaDtos {

    private CaixaDtos() {
    }

    /** {@code aberto = false} deixa os demais campos nulos — não há caixa hoje para o usuário/empresa. */
    public record CaixaStatusResponse(
            boolean aberto,
            Long idCaixa,
            OffsetDateTime dataAbertura,
            Long idCarteira,
            String nomeCarteira,
            BigDecimal saldoInicial) {
    }

    public record AbrirCaixaRequest(
            @NotNull Long idCarteira,
            @NotNull @DecimalMin(value = "0") BigDecimal saldoInicial) {
    }

    public record CarteiraParaAberturaResponse(long idCarteira, String nomeCarteira) {
    }

    /**
     * Uma linha de totais do Fechamento de Caixa, por tipo de carteira. {@code saldoInicial}
     * só é diferente de zero na linha da carteira escolhida na abertura (V025). {@code
     * valorEsperado = saldoInicial + totalCredito - totalDebito} — nunca gravado, sempre
     * recalculado na hora a partir de {@code caixa_detalhe}.
     */
    public record LinhaTotalCarteiraResponse(
            long idCarteira, String nomeCarteira, BigDecimal saldoInicial,
            BigDecimal totalCredito, BigDecimal totalDebito, BigDecimal valorEsperado) {
    }

    public record FechamentoCaixaResponse(
            long idCaixa,
            long idUsuario,
            String nomeUsuario,
            String nomeEmpresa,
            OffsetDateTime dataAbertura,
            OffsetDateTime dataFechamento,
            boolean fechado,
            List<LinhaTotalCarteiraResponse> linhas,
            BigDecimal valorContadoDinheiro) {
    }

    public record FecharCaixaRequest(
            @NotNull Long idCaixa,
            @NotNull @DecimalMin(value = "0") BigDecimal valorContadoDinheiro) {
    }
}
