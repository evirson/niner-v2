package com.vetor.niner.comum.telaconfig;

import jakarta.validation.constraints.NotBlank;

/** DTOs da configuração de tela (docs/telas/configuracao-tela.md). */
public final class ConfiguracaoTelaDtos {

    private ConfiguracaoTelaDtos() {
    }

    public record ConfiguracaoCampoResponse(String campo, boolean visivel, boolean obrigatorio) {
    }

    public record ConfiguracaoCampoRequest(@NotBlank String campo, boolean visivel, boolean obrigatorio) {
    }
}
