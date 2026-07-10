package com.vetor.niner.plataforma.onboarding;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** DTOs do onboarding público (signup do trial + login). */
public final class OnboardingDtos {

    private OnboardingDtos() {
    }

    /** Pedido de assinatura-teste (trial self-service, R12). */
    public record AssinarRequest(
            @NotBlank @Size(max = 120) String nomeLoja,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 8, max = 100) String senha,
            @NotBlank @Size(max = 120) String nomeAdmin) {
    }

    /** Resposta do signup: já devolve o token de primeiro acesso (auto-login). */
    public record AssinarResponse(
            String token,
            long idTenant,
            String slug,
            String nomeLoja,
            String plano,
            OffsetDateTime trialExpiraEm) {
    }

    /** Login de usuário do tenant (identificado pelo slug da loja). */
    public record LoginRequest(
            @NotBlank String slug,
            @NotBlank @Email String email,
            @NotBlank String senha) {
    }

    public record TokenResponse(String token, long idTenant, String slug) {
    }
}
