package com.vetor.niner.financeiro.caixa;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
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
            List<LinhaConferenciaResponse> conferencia) {
    }

    /** Valor contado pelo operador para UMA carteira — o fechamento "às cegas" (2026-07-30)
     *  pede um valor por carteira com movimento no dia, não só dinheiro. */
    public record ValorContadoRequest(
            @NotNull Long idCarteira,
            @NotNull @DecimalMin(value = "0") BigDecimal valorContado) {
    }

    public record FecharCaixaRequest(
            @NotNull Long idCaixa,
            @NotEmpty List<@Valid ValorContadoRequest> valoresContados) {
    }

    /** Uma linha de conferência: {@code diferenca = valorContado - valorEsperado}. Só é
     *  persistida (`caixa_fechamento_conferencia`) quando TODAS as linhas fecham em zero —
     *  senão o caixa continua aberto e a tela mostra a divergência sem gravar nada. */
    public record LinhaConferenciaResponse(
            long idCarteira, String nomeCarteira, BigDecimal valorEsperado, BigDecimal valorContado, BigDecimal diferenca) {
    }

    /** {@code fechado = false} quando alguma carteira não bateu — o caixa continua aberto e
     *  {@code linhas} traz a divergência de cada carteira pra tela mostrar. */
    public record ResultadoFechamentoResponse(long idCaixa, boolean fechado, List<LinhaConferenciaResponse> linhas) {
    }

    /** Lançamento analítico de uma carteira dentro do caixa — drill-down pedido na divergência,
     *  pra o operador conferir lançamento a lançamento o que compõe o valor esperado. */
    public record LancamentoCarteiraResponse(
            OffsetDateTime dataHora, String tipoOperacao, String creditoDebito, BigDecimal valor, String origem) {
    }
}
