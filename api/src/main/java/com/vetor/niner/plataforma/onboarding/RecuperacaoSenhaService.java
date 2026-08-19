package com.vetor.niner.plataforma.onboarding;

import com.vetor.niner.comum.email.EmailService;
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
import java.util.Base64;
import java.util.HexFormat;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Recuperação de senha do usuário do lojista (bloqueador nº 5 de produção). Antes disto, quem
 * esquecia a senha ficava trancado para fora — só um {@code UPDATE} manual no banco resolvia.
 *
 * <p><b>Decisões que valem mais que o código:</b>
 * <ul>
 *   <li>a solicitação responde <b>sempre igual</b>, exista ou não a conta — resposta diferente
 *       transformaria o endpoint numa lista de quem é cliente (e de quais lojas existem);</li>
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

    public RecuperacaoSenhaService(JdbcClient jdbc, PasswordEncoder senhas, EmailService email,
            @Value("${niner.web-base-url:http://localhost:5173}") String baseWeb) {
        this.jdbc = jdbc;
        this.senhas = senhas;
        this.email = email;
        this.baseWeb = baseWeb;
    }

    @Transactional
    public void solicitar(SolicitarRecuperacaoRequest req, String ip) {
        Long idTenant = jdbc.sql("SELECT id_tenant FROM plataforma.tenant WHERE slug = ?")
                .param(req.slug()).query(Long.class).optional().orElse(null);
        if (idTenant == null) {
            log.info("Recuperação de senha pedida para loja inexistente ({})", req.slug());
            return;                                   // resposta ao cliente é idêntica ao sucesso
        }
        jdbc.sql("SELECT set_config('app.id_tenant', ?, true)")
                .param(Long.toString(idTenant)).query(String.class).single();

        var usuario = jdbc.sql("""
                        SELECT id_usuario, nome_usuario, email, ativo
                          FROM usuario
                         WHERE id_tenant = ? AND lower(email) = lower(?)
                        """)
                .params(idTenant, req.email())
                .query((rs, n) -> new UsuarioAlvo(rs.getLong("id_usuario"), rs.getString("nome_usuario"),
                        rs.getString("email"), rs.getBoolean("ativo")))
                .optional().orElse(null);
        if (usuario == null || !usuario.ativo()) {
            log.info("Recuperação de senha pedida para conta inexistente/inativa no tenant {}", idTenant);
            return;
        }

        // Pedido novo invalida os anteriores: link antigo esquecido no e-mail deixa de valer.
        jdbc.sql("""
                        UPDATE plataforma.recuperacao_senha SET usado_em = now()
                         WHERE id_tenant = ? AND id_usuario = ? AND usado_em IS NULL
                        """)
                .params(idTenant, usuario.idUsuario()).update();

        String token = gerarToken();
        jdbc.sql("""
                        INSERT INTO plataforma.recuperacao_senha
                            (id_tenant, id_usuario, token_hash, expira_em, ip_solicitante)
                        VALUES (?, ?, ?, now() + make_interval(mins => ?), ?)
                        """)
                .params(idTenant, usuario.idUsuario(), hash(token), (int) VALIDADE.toMinutes(), ip)
                .update();

        String link = "%s/redefinir-senha?token=%s".formatted(baseWeb.replaceAll("/+$", ""), token);
        email.enviar(usuario.email(), "Redefinir a senha do Niner", """
                <p>Olá, %s.</p>
                <p>Recebemos um pedido para redefinir a senha da sua conta no Niner.
                O link abaixo vale por <b>%d horas</b> e só pode ser usado uma vez:</p>
                <p><a href="%s">Redefinir minha senha</a></p>
                <p style="color:#5c6660;font-size:13px">Se não foi você que pediu, ignore este e-mail —
                nada muda enquanto o link não for usado.</p>
                """.formatted(usuario.nome(), VALIDADE.toHours(), link));
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

        jdbc.sql("""
                        UPDATE usuario SET senha_hash = ?, atualizado_em = now()
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
