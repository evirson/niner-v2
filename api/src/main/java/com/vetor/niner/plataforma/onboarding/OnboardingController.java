package com.vetor.niner.plataforma.onboarding;

import com.vetor.niner.comum.web.IpDoCliente;
import com.vetor.niner.plataforma.acesso.AcessoLogService;
import com.vetor.niner.plataforma.acesso.LoginRecusadoException;
import com.vetor.niner.plataforma.acesso.ResultadoAcesso;
import com.vetor.niner.plataforma.aquisicao.AquisicaoService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OnboardingController.class);

    private final SignupService signup;
    private final AquisicaoService aquisicao;
    private final RecuperacaoSenhaService recuperacao;
    private final AcessoLogService acessos;
    private final IpDoCliente ipDoCliente;
    private final JwtDecoder jwtDecoder;

    /**
     * ⚠️ {@code @Qualifier("jwtDecoder")} é obrigatório: existem <b>dois</b> beans
     * {@code JwtDecoder} (tenant e staff), e sem o qualificador o Spring resolve por nome de
     * parâmetro ou pelo {@code @Primary} — que foi exatamente como um token de lojista quase
     * abriu o backoffice em 2026-08-19. Aqui o decoder certo é o de <b>tenant</b>: o token que
     * acabamos de emitir é dele.
     */
    public OnboardingController(SignupService signup, AquisicaoService aquisicao,
            RecuperacaoSenhaService recuperacao, AcessoLogService acessos, IpDoCliente ipDoCliente,
            @Qualifier("jwtDecoder") JwtDecoder jwtDecoder) {
        this.signup = signup;
        this.aquisicao = aquisicao;
        this.recuperacao = recuperacao;
        this.acessos = acessos;
        this.ipDoCliente = ipDoCliente;
        this.jwtDecoder = jwtDecoder;
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

    /**
     * Login de usuário do tenant (slug da loja + email + senha).
     *
     * <p>⛔ <b>O log de acesso é gravado AQUI, fora da transação do login</b>
     * (docs/MODULOLOGACESSO.md §4) — {@code SignupService.login} é {@code @Transactional}, e
     * gravar lá dentro é o defeito que fez o signup responder <b>201 com a conta inexistente</b>
     * em 2026-08-18: no Postgres um comando que falha aborta a transação inteira, e o
     * {@code try/catch} em Java não desfaz isso.
     *
     * <p>⚠️ <b>Só o login CONCLUÍDO é sucesso.</b> As voltas intermediárias (escolher conta,
     * escolher empresa, pedir o código de 2FA) devolvem {@code token == null} e não registram
     * nada — marcá-las como sucesso encheria a auditoria de linhas que não são entrada no sistema.
     */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req, jakarta.servlet.http.HttpServletRequest http) {
        var origem = origemDe(http);
        try {
            TokenResponse resposta = signup.login(req, ipDoCliente.de(http));
            if (resposta.token() != null) {
                registrarSucesso(resposta.token(), req.email(), origem);
            }
            return resposta;
        } catch (LoginRecusadoException e) {
            acessos.registrar(e.resultado(), null, null, null, req.email(), origem);
            throw e;
        }
    }

    /**
     * Segunda etapa do login em duas etapas (V079): o código de 4 dígitos que chegou por e-mail.
     *
     * <p>⚠️ Só o {@code desafio} (UUID opaco) e o código — a senha não trafega de novo.
     */
    @PostMapping("/login/codigo")
    public TokenResponse loginComCodigo(@Valid @RequestBody CodigoLoginRequest req,
            jakarta.servlet.http.HttpServletRequest http) {
        var origem = origemDe(http);
        try {
            TokenResponse resposta = signup.concluirLoginComCodigo(req);
            // ⚠️ Aqui o e-mail não vem do corpo (a segunda etapa só carrega o desafio opaco e o
            // código, de propósito) — vem do token recém-emitido, que é a fonte da verdade.
            registrarSucesso(resposta.token(), null, origem);
            return resposta;
        } catch (LoginRecusadoException e) {
            // Horário de acesso conferido de novo na segunda etapa.
            acessos.registrar(e.resultado(), null, null, null, null, origem);
            throw e;
        } catch (org.springframework.web.server.ResponseStatusException e) {
            // ⚠️ Tudo o que sobra neste endpoint é código errado, expirado ou já usado — o
            // `CodigoLoginService` lança a exceção genérica, e classificar pela mensagem seria
            // frágil. Aqui o ENDPOINT já diz qual é o assunto: quem chega neste ponto estava na
            // segunda etapa do login.
            acessos.registrar(ResultadoAcesso.CODIGO_2FA_INVALIDO, null, null, null, null, origem);
            throw e;
        }
    }

    private AcessoLogService.Origem origemDe(jakarta.servlet.http.HttpServletRequest http) {
        return new AcessoLogService.Origem(ipDoCliente.de(http), ipDoCliente.confiavel(),
                http.getHeader("User-Agent"));
    }

    /**
     * Lê do próprio token quem entrou.
     *
     * <p>⭐ Os ids vêm do <b>token recém-emitido</b>, não do corpo do pedido nem de um campo novo
     * no {@code TokenResponse}: é a fonte da verdade sobre quem de fato entrou, e evita acoplar o
     * DTO público ao log. {@code sub} = usuário, {@code tid} = conta, {@code eid} = empresa.
     *
     * <p>⚠️ Se a leitura falhar por qualquer motivo, o acesso ainda é registrado — sem os ids, mas
     * com e-mail, IP e aparelho. Perder a linha inteira por causa de um campo seria pior.
     */
    private void registrarSucesso(String token, String emailInformado, AcessoLogService.Origem origem) {
        Long idTenant = null;
        Long idUsuario = null;
        Long idEmpresa = null;
        String email = emailInformado;
        try {
            Jwt jwt = jwtDecoder.decode(token);
            idUsuario = Long.valueOf(jwt.getSubject());
            Object tid = jwt.getClaim("tid");
            Object eid = jwt.getClaim("eid");
            idTenant = tid == null ? null : ((Number) tid).longValue();
            idEmpresa = eid == null ? null : ((Number) eid).longValue();
            if (email == null) {
                email = jwt.getClaimAsString("email");
            }
        } catch (RuntimeException e) {
            log.warn("Acesso registrado sem os identificadores: o token não pôde ser lido.", e);
        }
        acessos.registrar(ResultadoAcesso.SUCESSO, idTenant, idUsuario, idEmpresa, email, origem);
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
     *
     * <p>⚠️ O IP vem do {@link IpDoCliente}, nunca de {@code getRemoteAddr()} cru: atrás do nginx
     * este último devolve o IP do <b>proxy</b>, e {@code recuperacao_senha.ip_solicitante} passaria
     * a gravar sempre o mesmo endereço — dado de auditoria inútil, e errado só em produção.
     */
    @PostMapping("/recuperar-senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recuperarSenha(
            @Valid @RequestBody SolicitarRecuperacaoRequest req, jakarta.servlet.http.HttpServletRequest http) {
        recuperacao.solicitar(req, ipDoCliente.de(http));
    }

    /** Redefine a senha com o token do e-mail (uso único, validade curta). */
    @PostMapping("/redefinir-senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void redefinirSenha(@Valid @RequestBody RedefinirSenhaRequest req) {
        recuperacao.redefinir(req);
    }
}
