package com.vetor.niner.financeiro.caixa;

import com.vetor.niner.financeiro.TipoCarteiraDtos.CategoriaCarteira;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
     * Uma linha da grade "Caixas Abertos" (2026-08-19) — substitui a busca por data/usuário do
     * Fechamento de Caixa "às cegas". OPERADOR só vê os próprios; ADMIN vê de todo mundo, em
     * qualquer empresa do tenant (ver {@code CaixaService.listarAbertos}).
     */
    public record CaixaAbertoResponse(
            long idCaixa, long idUsuario, String nomeUsuario, long idEmpresa, String nomeEmpresa,
            OffsetDateTime dataAbertura, String nomeCarteira, BigDecimal saldoInicial) {
    }

    /**
     * Uma linha de totais do Fechamento de Caixa, por tipo de carteira. {@code saldoInicial}
     * só é diferente de zero na linha da carteira escolhida na abertura (V025). {@code
     * valorEsperado = saldoInicial + totalCredito - totalDebito} — nunca gravado, sempre
     * recalculado na hora a partir de {@code caixa_detalhe}. {@code categoriaCarteira}
     * (2026-07-31) distingue carteiras com o mesmo nome mas categorias diferentes — ex.:
     * "HIPER" cadastrada em débito e em crédito, cada uma como uma linha própria aqui.
     */
    public record LinhaTotalCarteiraResponse(
            long idCarteira, String nomeCarteira, CategoriaCarteira categoriaCarteira, BigDecimal saldoInicial,
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

    /**
     * {@code forcarComDivergencia} (2026-08-19, revisão da contagem às cegas): quando alguma
     * carteira não bate, uma primeira chamada sem essa flag devolve a divergência sem fechar
     * nada (mesmo contrato de sempre) — a tela pergunta se o operador quer fechar mesmo assim, e
     * só nesse "sim" é que ela reenvia com {@code forcarComDivergencia = true}, fechando de
     * propósito com a diferença registrada (fica na auditoria, ver {@code CaixaService.fechar}).
     */
    public record FecharCaixaRequest(
            @NotNull Long idCaixa,
            @NotEmpty List<@Valid ValorContadoRequest> valoresContados,
            /**
             * ⚠️ {@code Boolean}, não {@code boolean} (auditoria 2026-08-29). O javadoc acima
             * descreve o fluxo como <i>"uma primeira chamada SEM essa flag"</i> — e com o
             * primitivo, omitir o campo dava {@code MismatchedInputException} (400 com mensagem de
             * desserialização) no fechamento de caixa, em vez de valer "não forçar". O front sempre
             * manda a chave, então o defeito estava latente para qualquer outro cliente da API que
             * seguisse o contrato escrito.
             */
            Boolean forcarComDivergencia) {
    }

    /** Uma linha de conferência: {@code diferenca = valorContado - valorEsperado}. Só é
     *  persistida (`caixa_fechamento_conferencia`) quando TODAS as linhas fecham em zero —
     *  senão o caixa continua aberto e a tela mostra a divergência sem gravar nada.
     *  {@code categoriaCarteira} (2026-07-31), mesmo motivo de {@link LinhaTotalCarteiraResponse}. */
    public record LinhaConferenciaResponse(
            long idCarteira, String nomeCarteira, CategoriaCarteira categoriaCarteira,
            BigDecimal valorEsperado, BigDecimal valorContado, BigDecimal diferenca) {
    }

    /** {@code fechado = false} quando alguma carteira não bateu — o caixa continua aberto e
     *  {@code linhas} traz a divergência de cada carteira pra tela mostrar. */
    public record ResultadoFechamentoResponse(long idCaixa, boolean fechado, List<LinhaConferenciaResponse> linhas) {
    }

    /** Reabertura de caixa (2026-08-14) — ADMIN-only. O {@code motivo} é obrigatório porque
     *  reabrir invalida a conferência já gravada; sem o porquê registrado em
     *  {@code caixa_mestre.observacoes}, ninguém consegue auditar depois (P3). Mesmo par
     *  ADMIN + motivo do Cancelamento de Venda. */
    public record ReabrirCaixaRequest(
            @NotBlank @Size(max = 200) String motivo) {
    }

    /** {@code reaberto = true} sempre que a operação chega ao fim — os casos de recusa
     *  (não é ADMIN, caixa já aberto, operador com outro caixa aberto) saem como erro, não
     *  como resposta de sucesso com flag falsa. */
    public record ReaberturaCaixaResponse(long idCaixa, boolean reaberto) {
    }

    /** Lançamento analítico de uma carteira dentro do caixa — drill-down pedido na divergência,
     *  pra o operador conferir lançamento a lançamento o que compõe o valor esperado. */
    public record LancamentoCarteiraResponse(
            OffsetDateTime dataHora, String tipoOperacao, String creditoDebito, BigDecimal valor, String origem) {
    }
}
