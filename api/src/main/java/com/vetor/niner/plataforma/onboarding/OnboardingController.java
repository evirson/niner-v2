package com.vetor.niner.plataforma.onboarding;

import com.vetor.niner.plataforma.aquisicao.AquisicaoService;
import com.vetor.niner.plataforma.onboarding.OnboardingDtos.*;
import com.vetor.niner.plataforma.onboarding.RecuperacaoSenhaDtos.RedefinirSenhaRequest;
import com.vetor.niner.plataforma.onboarding.OnboardingDtos.CodigoLoginRequest;
import com.vetor.niner.plataforma.onboarding.OnboardingDtos.ReenviarCodigoRequest;
import com.vetor.niner.plataforma.onboarding.RecuperacaoSenhaDtos.SolicitarRecuperacaoRequest;
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
    private final RecuperacaoSenhaService recuperacao;

    public OnboardingController(SignupService signup, AquisicaoService aquisicao,
            RecuperacaoSenhaService recuperacao) {
        this.signup = signup;
        this.aquisicao = aquisicao;
        this.recuperacao = recuperacao;
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
    public TokenResponse login(@Valid @RequestBody LoginRequest req, jakarta.servlet.http.HttpServletRequest http) {
        return signup.login(req, http.getRemoteAddr());
    }

    /**
     * Segunda etapa do login em duas etapas (V079): o código de 4 dígitos que chegou por e-mail.
     *
     * <p>⚠️ Só o {@code desafio} (UUID opaco) e o código — a senha não trafega de novo.
     */
    @PostMapping("/login/codigo")
    public TokenResponse loginComCodigo(@Valid @RequestBody CodigoLoginRequest req) {
        return signup.concluirLoginComCodigo(req);
    }

    /**
     * Reenvia o código, gerando um novo e invalidando o anterior.
     *
     * <p>⚠️ Responde <b>204</b> e nunca diz se o desafio existe — informar isso transformaria o
     * endpoint num verificador de desafios alheios.
     */
    @PostMapping("/login/codigo/reenviar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reenviarCodigo(@Valid @RequestBody ReenviarCodigoRequest req) {
        signup.reenviarCodigoLogin(req);
    }

    /**
     * Pede a redefinição de senha. Responde <b>204 sempre</b> — inclusive para loja ou e-mail
     * inexistente: status diferente transformaria este endpoint numa lista de quem é cliente.
     */
    @PostMapping("/recuperar-senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recuperarSenha(
            @Valid @RequestBody SolicitarRecuperacaoRequest req, jakarta.servlet.http.HttpServletRequest http) {
        recuperacao.solicitar(req, http.getRemoteAddr());
    }

    /** Redefine a senha com o token do e-mail (uso único, validade curta). */
    @PostMapping("/redefinir-senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void redefinirSenha(@Valid @RequestBody RedefinirSenhaRequest req) {
        recuperacao.redefinir(req);
    }
}
