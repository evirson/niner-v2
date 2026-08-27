package com.vetor.niner.plataforma.onboarding;

import com.vetor.niner.comum.email.EmailService;
import com.vetor.niner.plataforma.diretorio.DiretorioLogin;
import com.vetor.niner.plataforma.diretorio.DiretorioLogin.ContaCandidata;
import com.vetor.niner.plataforma.onboarding.RecuperacaoSenhaDtos.RedefinirSenhaRequest;
import com.vetor.niner.plataforma.onboarding.RecuperacaoSenhaDtos.SolicitarRecuperacaoRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Recuperação de senha do usuário do lojista (bloqueador nº 5 de produção). Antes disto, quem
 * esquecia a senha ficava trancado para fora — só um {@code UPDATE} manual no banco resolvia.
 *
 * <p><b>Decisões que valem mais que o código:</b>
 * <ul>
 *   <li>a solicitação responde <b>sempre igual</b>, exista ou não a conta — resposta diferente
 *       transformaria o endpoint numa lista de quem é cliente;</li>
 *   <li>o banco guarda o <b>hash</b> do token, nunca o token: um dump vazado não vira invasão;</li>
 *   <li>token de <b>uso único</b> e validade curta; pedir um novo <b>invalida os anteriores</b>,
 *       senão cada pedido esquecido na caixa de entrada continuaria valendo;</li>
 *   <li>redefinir senha <b>encerra</b> qualquer pedido pendente daquele usuário.</li>
 * </ul>
 *
 * <p>O {@code app.id_tenant} é estabelecido à mão aqui: o pedido chega pela superfície pública,
 * que não tem JWT nem {@code TenantFilter}, e {@code usuario} está sob RLS (P8).
 */
@Service
public class RecuperacaoSenhaService {

    private static final Logger log = LoggerFactory.getLogger(RecuperacaoSenhaService.class);
    private static final Duration VALIDADE = Duration.ofHours(2);
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder senhas;
    private final EmailService email;
    private final String baseWeb;
    private final DiretorioLogin diretorio;

    public RecuperacaoSenhaService(JdbcClient jdbc, PasswordEncoder senhas, EmailService email,
            DiretorioLogin diretorio,
            @Value("${niner.web-base-url:http://localhost:5173}") String baseWeb) {
        this.jdbc = jdbc;
        this.senhas = senhas;
        this.email = email;
        this.diretorio = diretorio;
        this.baseWeb = baseWeb;
    }

    /**
     * Pede a redefinição a partir do <b>e-mail</b> — sem identificador de loja (2026-08-27).
     *
     * <p><b>Por que o identificador saiu.</b> Ele era exigido aqui e este era o pior lugar
     * possível: quem esqueceu a senha não vai lembrar de um valor que o sistema inventou no
     * signup. E como a resposta é sempre 204, errar o identificador significava <b>nenhum e-mail
     * chegando, sem explicação nenhuma</b> — a pessoa ficava esperando uma mensagem que nunca foi
     * enviada.
     *
     * <p><b>Um e-mail, um link por conta.</b> O mesmo e-mail pode existir em várias contas (o dono
     * que vende cosméticos numa e sapatos noutra). Cada conta ganha seu próprio token e o e-mail
     * lista uma linha por conta, nomeando-a — assim ele redefine a senha da conta certa. Continua
     * não havendo como descobrir por fora quantas contas aquele e-mail tem: quem não é dono do
     * e-mail não recebe a mensagem.
     */
    @Transactional
    public void solicitar(SolicitarRecuperacaoRequest req, String ip) {
        List<ContaCandidata> contas = diretorio.porEmail(req.email());
        if (contas.isEmpty()) {
            log.info("Recuperação de senha pedida para e-mail sem conta nenhuma.");
            return;                                   // resposta ao cliente é idêntica ao sucesso
        }

        List<String> linhas = new ArrayList<>();
        String nome = null;
        for (ContaCandidata conta : contas) {
            jdbc.sql("SELECT set_config('app.id_tenant', ?, true)")
                    .param(Long.toString(conta.idTenant())).query(String.class).single();

            var usuario = jdbc.sql("""
                            SELECT id_usuario, nome_usuario, email, ativo
                              FROM usuario
                             WHERE id_tenant = ? AND lower(email) = lower(?)
                            """)
                    .params(conta.idTenant(), req.email())
                    .query((rs, n) -> new UsuarioAlvo(rs.getLong("id_usuario"), rs.getString("nome_usuario"),
                            rs.getString("email"), rs.getBoolean("ativo")))
                    .optional().orElse(null);
            if (usuario == null || !usuario.ativo()) {
                // Conta inativa (ou índice defasado) é pulada em silêncio: mandar um link que não
                // vai funcionar é pior do que não mandar linha nenhuma para ela.
                continue;
            }
            nome = usuario.nome();

            // Pedido novo invalida os anteriores: link antigo esquecido no e-mail deixa de valer.
            jdbc.sql("""
                            UPDATE plataforma.recuperacao_senha SET usado_em = now()
                             WHERE id_tenant = ? AND id_usuario = ? AND usado_em IS NULL
                            """)
                    .params(conta.idTenant(), usuario.idUsuario()).update();

            String token = gerarToken();
            jdbc.sql("""
                            INSERT INTO plataforma.recuperacao_senha
                                (id_tenant, id_usuario, token_hash, expira_em, ip_solicitante)
                            VALUES (?, ?, ?, now() + make_interval(mins => ?), ?)
                            """)
                    .params(conta.idTenant(), usuario.idUsuario(), hash(token), (int) VALIDADE.toMinutes(), ip)
                    .update();

            String link = "%s/redefinir-senha?token=%s".formatted(baseWeb.replaceAll("/+$", ""), token);
            linhas.add("<p><a href=\"%s\">Redefinir a senha de <b>%s</b></a></p>"
                    .formatted(link, escapar(conta.nomeConta())));
        }

        if (linhas.isEmpty()) {
            log.info("Recuperação de senha: e-mail existe no diretório, mas nenhuma conta ativa.");
            return;
        }

        String cabecalhoMultiplo = linhas.size() > 1
                ? "<p>Este e-mail está em mais de uma conta — escolha qual você quer redefinir:</p>"
                : "";
        email.enviar(req.email(), "Redefinir a senha do Nainer", """
                <p>Olá, %s.</p>
                <p>Recebemos um pedido para redefinir a senha da sua conta no Nainer.
                O link abaixo vale por <b>%d horas</b> e só pode ser usado uma vez:</p>
                %s%s
                <p style="color:#5c6660;font-size:13px">Se não foi você que pediu, ignore este e-mail —
                nada muda enquanto o link não for usado.</p>
                """.formatted(nome, VALIDADE.toHours(), cabecalhoMultiplo, String.join("", linhas)));
    }

    /** O nome da conta é digitado pelo lojista e vai dentro de HTML — nunca entra cru. */
    private static String escapar(String texto) {
        return texto == null ? "" : texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Transactional
    public void redefinir(RedefinirSenhaRequest req) {
        var pedido = jdbc.sql("""
                        SELECT id_recuperacao, id_tenant, id_usuario
                          FROM plataforma.recuperacao_senha
                         WHERE token_hash = ? AND usado_em IS NULL AND expira_em > now()
                        """)
                .param(hash(req.token()))
                .query((rs, n) -> new Pedido(rs.getLong("id_recuperacao"), rs.getLong("id_tenant"),
                        rs.getLong("id_usuario")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,
                        "Link inválido ou expirado. Peça a redefinição novamente."));

        jdbc.sql("SELECT set_config('app.id_tenant', ?, true)")
                .param(Long.toString(pedido.idTenant())).query(String.class).single();

        // ⚠️ `sessao_valida_desde = now()` (V080) derruba toda sessão aberta com a senha antiga —
        // é o caso principal da revogação: quem redefine a senha costuma estar redefinindo porque
        // alguém entrou na conta dele, e trocar a senha sem expulsar quem já está dentro não
        // resolve nada.
        jdbc.sql("""
                        UPDATE usuario SET senha_hash = ?, sessao_valida_desde = now(), atualizado_em = now()
                         WHERE id_tenant = ? AND id_usuario = ?
                        """)
                .params(senhas.encode(req.novaSenha()), pedido.idTenant(), pedido.idUsuario())
                .update();

        // Marca o usado e encerra os demais pendentes do mesmo usuário na mesma tacada.
        jdbc.sql("""
                        UPDATE plataforma.recuperacao_senha SET usado_em = now()
                         WHERE id_tenant = ? AND id_usuario = ? AND usado_em IS NULL
                        """)
                .params(pedido.idTenant(), pedido.idUsuario()).update();

        log.info("Senha redefinida para o usuário {} do tenant {}", pedido.idUsuario(), pedido.idTenant());
    }

    /** 32 bytes aleatórios em base64url — vai na URL do e-mail e nunca é gravado. */
    private static String gerarToken() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    private record UsuarioAlvo(long idUsuario, String nome, String email, boolean ativo) {
    }

    private record Pedido(long idRecuperacao, long idTenant, long idUsuario) {
    }
}
