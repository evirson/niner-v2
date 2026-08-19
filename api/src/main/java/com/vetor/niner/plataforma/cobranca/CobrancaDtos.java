package com.vetor.niner.plataforma.cobranca;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** DTOs da assinatura paga (ADR-015/016). */
public final class CobrancaDtos {

    private CobrancaDtos() {
    }

    public enum Ciclo {
        MENSAL, ANUAL
    }

    /** Pedido de upgrade: qual faixa e em que ciclo. O valor nunca vem daqui — é relido do plano. */
    public record IniciarPagamentoRequest(@NotNull Long idPlano, @NotNull Ciclo ciclo) {
    }

    /** O que a tela mostra depois de pedir o PIX. */
    public record PagamentoPixResponse(
            long idFatura, String plano, Ciclo ciclo, BigDecimal valor, LocalDate competencia,
            String copiaECola, String qrCodeBase64, String linkPagamento, OffsetDateTime expiraEm,
            String situacao) {
    }

    /** Situação da fatura para o polling da tela enquanto o cliente paga. */
    public record SituacaoFaturaResponse(long idFatura, String situacao, OffsetDateTime pagoEm, String planoAtual) {
    }

    /** Linha do histórico de faturas do próprio tenant. */
    public record FaturaResponse(
            long idFatura, LocalDate competencia, String plano, Ciclo ciclo, BigDecimal valor,
            LocalDate vencimento, String situacao, OffsetDateTime pagoEm) {
    }
}
