package com.vetor.niner.plataforma.onboarding;

import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.comum.seguranca.TokenService;
import com.vetor.niner.plataforma.onboarding.OnboardingDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Onboarding do trial (R12, §3.3.2). Numa ÚNICA transação cria a conta assinante e
 * libera o sistema com configurações padrão, e devolve o token de primeiro acesso.
 *
 * <p>Como o tenant nasce dentro desta transação, o serviço estabelece
 * {@code app.id_tenant} logo após criá-lo (via {@code set_config(..., true)}) para que
 * o RLS de domínio (V024) permita inserir empresa/usuário/cfg_geral do novo tenant (P8).
 */
@Service
public class SignupService {

    private final JdbcClient jdbc;
    private final PasswordEncoder senhas;
    private final TokenService tokens;
    private final NinerProperties props;

    public SignupService(JdbcClient jdbc, PasswordEncoder senhas, TokenService tokens, NinerProperties props) {
        this.jdbc = jdbc;
        this.senhas = senhas;
        this.tokens = tokens;
        this.props = props;
    }

    @Transactional
    public AssinarResponse assinar(AssinarRequest req) {
        // 1) tenant (global, P9) — status TRIAL. slug único derivado do nome da loja.
        String slug = slugUnico(req.nomeLoja());
        long idTenant = jdbc.sql("""
                        INSERT INTO plataforma.tenant (nome_conta, slug, email_contato, status)
                        VALUES (?, ?, ?, 'TRIAL')
                        RETURNING id_tenant
                        """)
                .params(req.nomeLoja(), slug, req.email())
                .query(Long.class).single();

        // 2) contexto do novo tenant para as tabelas de domínio (RLS) — local à transação.
        jdbc.sql("SELECT set_config('app.id_tenant', ?, true)")
                .param(Long.toString(idTenant)).query(String.class).single();

        // 3) assinatura TRIAL no plano-base (fallback: menor preço ativo).
        long idPlano = jdbc.sql("SELECT id_plano FROM plataforma.plano WHERE nome = ? AND ativo")
                .param(props.trial().plano()).query(Long.class).optional()
                .orElseGet(() -> jdbc.sql(
                        "SELECT id_plano FROM plataforma.plano WHERE ativo ORDER BY preco_mensal LIMIT 1")
                        .query(Long.class).single());

        OffsetDateTime trialExpiraEm = jdbc.sql("""
                        INSERT INTO plataforma.assinatura (id_tenant, id_plano, status, trial_expira_em)
                        VALUES (?, ?, 'TRIAL', now() + make_interval(days => ?))
                        RETURNING trial_expira_em
                        """)
                .params(idTenant, idPlano, props.trial().dias())
                .query(OffsetDateTime.class).single();

        // 4) uso_tenant inicial (1 usuário: o admin criado abaixo).
        jdbc.sql("INSERT INTO plataforma.uso_tenant (id_tenant, qtd_usuarios) VALUES (?, 1)")
                .param(idTenant).update();

        // 5) empresa (1:1 no v1) + configurações padrão da loja. codigo_empresa=1 (primeira
        // empresa do tenant, Q6); cfg_nome_etiqueta recebe um modelo padrão (o lojista
        // personaliza depois) — ambas NOT NULL sem DEFAULT em `empresa` (V014).
        long idEmpresa = jdbc.sql("""
                        INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, cfg_nome_etiqueta)
                        VALUES (?, 1, ?, ?)
                        RETURNING id_empresa
                        """)
                .params(idTenant, req.nomeLoja(), "{sku}\n{descricao}\n{preco_venda}")
                .query(Long.class).single();

        jdbc.sql("INSERT INTO cfg_geral (id_tenant) VALUES (?)").param(idTenant).update();

        // 5b) moedas padrão (formas de recebimento, §3.3.7/V025) — seed POR TENANT aqui,
        // não em migration global, porque id_tenant é obrigatório (P8) e não existe no
        // momento do Flyway. Mesmo conjunto do legado (db/042_MOEDAS.txt).
        jdbc.sql("""
                        INSERT INTO moeda (id_tenant, nome_moeda) VALUES
                            (?, 'DINHEIRO'),
                            (?, 'PIX'),
                            (?, 'CARTAO DEBITO'),
                            (?, 'CARTAO CREDITO'),
                            (?, 'CREDIARIO'),
                            (?, 'VALE PRESENTE'),
                            (?, 'VALE MERCADORIA')
                        """)
                .params(idTenant, idTenant, idTenant, idTenant, idTenant, idTenant, idTenant)
                .update();

        // 6) primeiro usuário = ADMIN (senha em hash — nunca texto).
        long idUsuario = jdbc.sql("""
                        INSERT INTO usuario (id_tenant, id_empresa, nome_usuario, email, senha_hash, administrador)
                        VALUES (?, ?, ?, ?, ?, true)
                        RETURNING id_usuario
                        """)
                .params(idTenant, idEmpresa, req.nomeAdmin(), req.email(), senhas.encode(req.senha()))
                .query(Long.class).single();

        // 7) token de primeiro acesso (auto-login) — leva o cliente direto ao sistema.
        String token = tokens.emitir(idUsuario, idTenant, req.email(), List.of("ADMIN"));
        return new AssinarResponse(token, idTenant, slug, req.nomeLoja(), props.trial().plano(), trialExpiraEm);
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        // Resolve o tenant pelo slug da loja (global) e estabelece o contexto para o RLS.
        Long idTenant = jdbc.sql("SELECT id_tenant FROM plataforma.tenant WHERE slug = ?")
                .param(req.slug()).query(Long.class).optional().orElse(null);
        if (idTenant == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Credenciais inválidas.");
        }
        jdbc.sql("SELECT set_config('app.id_tenant', ?, true)")
                .param(Long.toString(idTenant)).query(String.class).single();

        var usuario = jdbc.sql("""
                        SELECT id_usuario, senha_hash, administrador, ativo
                        FROM usuario WHERE email = ?
                        """)
                .param(req.email())
                .query((rs, n) -> new UsuarioAuth(
                        rs.getLong("id_usuario"), rs.getString("senha_hash"),
                        rs.getBoolean("administrador"), rs.getBoolean("ativo")))
                .optional().orElse(null);

        if (usuario == null || !usuario.ativo() || !senhas.matches(req.senha(), usuario.senhaHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Credenciais inválidas.");
        }
        List<String> roles = List.of(usuario.administrador() ? "ADMIN" : "OPERADOR");
        String token = tokens.emitir(usuario.idUsuario(), idTenant, req.email(), roles);
        return new TokenResponse(token, idTenant, req.slug());
    }

    private record UsuarioAuth(long idUsuario, String senhaHash, boolean administrador, boolean ativo) {
    }

    /** Deriva um slug URL-safe do nome da loja e garante unicidade (sufixo -2, -3, …). */
    private String slugUnico(String nome) {
        String base = Normalizer.normalize(nome, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (base.isBlank()) {
            base = "loja";
        }
        String candidato = base;
        int sufixo = 2;
        while (Boolean.TRUE.equals(jdbc.sql("SELECT exists(SELECT 1 FROM plataforma.tenant WHERE slug = ?)")
                .param(candidato).query(Boolean.class).single())) {
            candidato = base + "-" + sufixo++;
        }
        return candidato;
    }
}
