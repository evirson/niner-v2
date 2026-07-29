package com.vetor.niner.plataforma.onboarding;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

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

    /**
     * Login de usuário do tenant (identificado pelo slug da loja). {@code idEmpresa}
     * (2026-07-28) é opcional — só é usado na segunda chamada, depois que a primeira responde
     * {@code escolherEmpresa=true} porque o usuário tem acesso a mais de uma empresa (ver
     * {@code SignupService.login}).
     */
    public record LoginRequest(
            @NotBlank String slug,
            @NotBlank @Email String email,
            @NotBlank String senha,
            Long idEmpresa) {
    }

    /** Uma empresa que o usuário pode escolher ao logar (`usuario_empresa`, 2026-07-28). */
    public record EmpresaOpcaoLogin(long idEmpresa, String nomeEmpresa) {
    }

    /**
     * {@code token} vem preenchido quando o login se completa. Quando o usuário tem acesso a
     * mais de uma empresa e a requisição não informou {@code idEmpresa}, {@code token} vem
     * {@code null} e {@code escolherEmpresa=true} — o front deve reapresentar as credenciais
     * com o {@code idEmpresa} escolhido em {@code empresas}.
     */
    public record TokenResponse(
            String token, long idTenant, String slug, boolean escolherEmpresa, List<EmpresaOpcaoLogin> empresas) {
    }
}
