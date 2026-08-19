package com.vetor.niner.plataforma.onboarding;

import com.vetor.niner.plataforma.aquisicao.AquisicaoService;
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
    private final AquisicaoService aquisicao;

    public OnboardingController(SignupService signup, AquisicaoService aquisicao) {
        this.signup = signup;
        this.aquisicao = aquisicao;
    }

    /**
     * Criação de conta self-service (plano Gratuito, sem cartão e sem prazo — ADR-015). Cria o
     * tenant e já loga o cliente.
     *
     * <p>O fechamento do funil (ADR-017) acontece <b>aqui, depois</b> de {@code assinar()}
     * retornar — e o lugar não é acidental. Dentro da transação do signup, dois caminhos dão
     * errado: (a) na <b>mesma</b> transação, qualquer erro de SQL da medição aborta a transação
     * no Postgres, e capturar a exceção em Java não desfaz isso — o {@code COMMIT} vira
     * {@code ROLLBACK} devolvendo sucesso, e a conta inteira some depois de um 201 (bug real,
     * 2026-08-18); (b) em transação <b>separada</b> (REQUIRES_NEW), a FK
     * {@code lead.id_tenant → tenant} falha, porque o tenant ainda não foi commitado. Depois do
     * commit resolve os dois: o tenant existe e a medição não tem como derrubar nada.
     */
    @PostMapping("/assinar")
    @ResponseStatus(HttpStatus.CREATED)
    public AssinarResponse assinar(@Valid @RequestBody AssinarRequest req) {
        AssinarResponse resposta = signup.assinar(req);
        aquisicao.converter(req.visitanteId(), req.email(), req.nomeAdmin(), req.nomeLoja(), resposta.idTenant());
        return resposta;
    }

    /** Login de usuário do tenant (slug da loja + email + senha). */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return signup.login(req);
    }
}
