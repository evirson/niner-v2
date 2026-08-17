package com.vetor.niner.fiscal.documento;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/** DTOs do painel de Contingência (§9.7, bloco B7) — estado + entrada/saída manual, ADMIN-only. */
public final class FiscalContingenciaDtos {

    private FiscalContingenciaDtos() {
    }

    /**
     * {@code duracaoMinutos} vem pronto do servidor (não recalculado no front a partir de
     * {@code desde}) — evita depender do relógio do navegador pra uma informação que o painel
     * exibe como "há quanto tempo a empresa está em contingência".
     */
    public record ContingenciaResponse(boolean ativa, Instant desde, String justificativa,
                                       int serieContingencia, int pendentes, long duracaoMinutos) {
    }

    public record EntrarSairRequest(@NotBlank String justificativa) {
    }
}
