package com.vetor.niner.plataforma.onboarding;

import com.vetor.niner.plataforma.onboarding.OnboardingDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Superfície pública de aquisição (§3.4). Anônima e rate-limited.
 * Fluxo do trial (R12): assinar → cria tenant + libera sistema + token de 1º acesso.
 */
@RestController
@RequestMapping("/api/publico")
public class OnboardingController {

    private final SignupService signup;

    public OnboardingController(SignupService signup) {
        this.signup = signup;
    }

    /** Assinatura-teste self-service (14 dias, sem cartão). Cria o tenant e loga o cliente. */
    @PostMapping("/assinar")
    @ResponseStatus(HttpStatus.CREATED)
    public AssinarResponse assinar(@Valid @RequestBody AssinarRequest req) {
        return signup.assinar(req);
    }

    /** Login de usuário do tenant (slug da loja + email + senha). */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return signup.login(req);
    }
}
