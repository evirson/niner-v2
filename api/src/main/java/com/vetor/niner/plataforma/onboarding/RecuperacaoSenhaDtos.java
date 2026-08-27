package com.vetor.niner.plataforma.onboarding;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTOs da recuperação de senha do usuário do lojista. */
public final class RecuperacaoSenhaDtos {

    private RecuperacaoSenhaDtos() {
    }

    /** Pedido: mesma identificação do login (loja + e-mail). */
    /**
     * Só o e-mail — o identificador da loja saiu em 2026-08-27, junto com o do login. Era o pior
     * lugar para exigi-lo: quem esqueceu a senha não lembra de um valor que o sistema inventou, e
     * como a resposta é sempre 204, errá-lo significava nenhum e-mail chegando, sem explicação.
     */
    public record SolicitarRecuperacaoRequest(@NotBlank @Email String email) {
    }

    public record RedefinirSenhaRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 100) String novaSenha) {
    }
}
