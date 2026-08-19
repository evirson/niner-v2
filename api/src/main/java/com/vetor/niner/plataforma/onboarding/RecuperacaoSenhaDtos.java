package com.vetor.niner.plataforma.onboarding;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTOs da recuperação de senha do usuário do lojista. */
public final class RecuperacaoSenhaDtos {

    private RecuperacaoSenhaDtos() {
    }

    /** Pedido: mesma identificação do login (loja + e-mail). */
    public record SolicitarRecuperacaoRequest(@NotBlank String slug, @NotBlank @Email String email) {
    }

    public record RedefinirSenhaRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 100) String novaSenha) {
    }
}
