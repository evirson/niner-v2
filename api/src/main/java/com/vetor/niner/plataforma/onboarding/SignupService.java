package com.vetor.niner.plataforma.onboarding;

import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.comum.seguranca.TokenService;
import com.vetor.niner.identidade.usuario.HorarioAcessoService;
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

import static org.springframework.http.HttpStatus.FORBIDDEN;
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
    private final HorarioAcessoService horarioAcesso;

    public SignupService(JdbcClient jdbc, PasswordEncoder senhas, TokenService tokens, NinerProperties props,
            HorarioAcessoService horarioAcesso) {
        this.jdbc = jdbc;
        this.senhas = senhas;
        this.tokens = tokens;
        this.props = props;
        this.horarioAcesso = horarioAcesso;
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

        // 5a) plano de contas mínimo pra Entrada de Produtos (mesma árvore de V032, replicada
        // aqui pra tenant novo nascer coerente sem depender da migration). id_plano_contas_pai/
        // nivel são colunas geradas a partir do código — nunca informadas. FK de hierarquia e
        // trigger de guarda já são DEFERRABLE INITIALLY DEFERRED (V016), então os 4 níveis
        // podem entrar juntos sem ordem especial.
        jdbc.sql("""
                        INSERT INTO cfg_plano_contas (
                            id_tenant, id_plano_contas, descricao, tipo_movimento, natureza,
                            inclui_dre, inclui_fluxo_caixa, grupo_dre, grupo_dfc, sinal,
                            aceita_lancamento, exige_contraparte, padrao_sistema
                        ) VALUES
                            (?, '3.00.000.000', 'CUSTOS VARIÁVEIS', 'DEBITO', 'SINTETICA',
                                true, true, 'CUSTO_VARIAVEL', 'OPERACIONAL', -1, false, false, true),
                            (?, '3.03.000.000', 'Compras e Custos de Aquisição de Estoque', 'DEBITO', 'SINTETICA',
                                false, true, 'NAO_APLICA', 'OPERACIONAL', -1, false, false, true),
                            (?, '3.03.001.000', 'Aquisição de Mercadoria', 'DEBITO', 'SINTETICA',
                                false, true, 'NAO_APLICA', 'OPERACIONAL', -1, false, false, true),
                            (?, '3.03.001.001', 'Compra de Mercadoria para Revenda', 'DEBITO', 'ANALITICA',
                                false, true, 'NAO_APLICA', 'OPERACIONAL', -1, true, false, true)
                        """)
                .params(idTenant, idTenant, idTenant, idTenant)
                .update();

        jdbc.sql("INSERT INTO cfg_geral (id_tenant, id_plano_contas_compra_mercadoria) VALUES (?, '3.03.001.001')")
                .param(idTenant).update();

        // 5b) formas de pagamento padrão (§3.3.7/V025) — seed POR TENANT aqui, não em migration
        // global, porque id_tenant é obrigatório (P8) e não existe no momento do Flyway. Mesmo
        // conjunto do legado (db/042_MOEDAS.txt), mas agora direto em tipo_carteira — moeda foi
        // absorvida em 2026-07-28 (motivo completo no topo de V025__financeiro_caixa_crediario.sql).
        // permite_receber_crediario (2026-07-29, RN007 do Recebimento de Crediário): nasce ligado
        // nas categorias explicitamente permitidas pela spec (À Vista/Débito/Crédito) — evita uma
        // tela de recebimento vazia em tenant novo; CREDIARIO/vale seguem desligados (crediário
        // não paga crediário, vale não estava nas categorias pedidas). "VALE PRESENTE" do legado
        // (2026-08-03) foi removido do seed — era só um rótulo AVISTA sem lógica nenhuma por trás,
        // e distinguir "presente" de "mercadoria" ficaria confuso ao lado do vale de verdade
        // (VALE MERCADORIA, categoria própria, ligado à Devolução de Produtos).
        jdbc.sql("""
                        INSERT INTO tipo_carteira
                            (id_tenant, nome_carteira, categoria_carteira, prazo_pagamento, pc_minima, pc_maxima,
                             permite_receber_crediario) VALUES
                            (?, 'DINHEIRO', 'AVISTA', 0, 1, 1, true),
                            (?, 'PIX', 'AVISTA', 0, 1, 1, true),
                            (?, 'CARTAO DEBITO', 'CARTAO_DEBITO', 1, 1, 1, true),
                            (?, 'CARTAO CREDITO', 'CARTAO_CREDITO', 30, 1, 6, true),
                            (?, 'CREDIARIO', 'CREDIARIO', 30, 1, 6, false),
                            (?, 'VALE MERCADORIA', 'VALE_MERCADORIA', 0, 1, 1, false)
                        """)
                .params(idTenant, idTenant, idTenant, idTenant, idTenant, idTenant)
                .update();

        // 6) primeiro usuário = ADMIN (senha em hash — nunca texto).
        long idUsuario = jdbc.sql("""
                        INSERT INTO usuario (id_tenant, id_empresa, nome_usuario, email, senha_hash, administrador)
                        VALUES (?, ?, ?, ?, ?, true)
                        RETURNING id_usuario
                        """)
                .params(idTenant, idEmpresa, req.nomeAdmin(), req.email(), senhas.encode(req.senha()))
                .query(Long.class).single();

        // 6b) acesso à empresa recém-criada (usuario_empresa, V015/2026-07-28) — sem essa
        // linha o primeiro login já cairia no caso "usuário sem empresa vinculada".
        jdbc.sql("INSERT INTO usuario_empresa (id_tenant, id_usuario, id_empresa) VALUES (?, ?, ?)")
                .params(idTenant, idUsuario, idEmpresa)
                .update();

        // 7) token de primeiro acesso (auto-login) — leva o cliente direto ao sistema, já com a
        // empresa recém-criada como empresa ativa da sessão (eid).
        String token = tokens.emitir(idUsuario, idTenant, idEmpresa, req.email(), List.of("ADMIN"));
        return new AssinarResponse(token, idTenant, slug, req.nomeLoja(), props.trial().plano(), trialExpiraEm);
    }

    /**
     * Login em duas voltas quando o usuário acessa mais de uma empresa
     * (docs/telas/usuario.md, `usuario_empresa`, 2026-07-28): a primeira chamada (sem
     * {@code idEmpresa}) valida a senha e, se houver mais de uma empresa, devolve a lista em
     * vez do token ({@code escolherEmpresa=true}); o front reenvia as mesmas credenciais com o
     * {@code idEmpresa} escolhido, que aí sim emite o token com a empresa ativa da sessão
     * ({@code eid}). Com uma única empresa, resolve direto — sem segunda volta.
     */
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

        // id_tenant explícito (defesa em profundidade) — ver comentário em ProdutoService.listar.
        // Aqui é o caminho de autenticação: um vazamento de RLS nesta query específica
        // significaria autenticação cross-tenant, não só leitura indevida.
        var usuario = jdbc.sql("""
                        SELECT id_usuario, senha_hash, administrador, ativo
                        FROM usuario WHERE id_tenant = ? AND email = ?
                        """)
                .params(idTenant, req.email())
                .query((rs, n) -> new UsuarioAuth(
                        rs.getLong("id_usuario"), rs.getString("senha_hash"),
                        rs.getBoolean("administrador"), rs.getBoolean("ativo")))
                .optional().orElse(null);

        if (usuario == null || !usuario.ativo() || !senhas.matches(req.senha(), usuario.senhaHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Credenciais inválidas.");
        }

        // Horário de acesso (2026-08-14) — sem tolerância nenhuma aqui: não existe "rotina em
        // andamento" pra proteger numa sessão que ainda nem começou (a tolerância padrão só
        // vale pro filtro por requisição, HorarioAcessoFilter, que pode pegar uma venda no meio).
        if (!horarioAcesso.podeAcessarAgora(usuario.idUsuario(), 0)) {
            throw new ResponseStatusException(FORBIDDEN, HorarioAcessoService.MENSAGEM_FORA_DA_JANELA);
        }

        List<EmpresaOpcaoLogin> empresas = jdbc.sql("""
                        SELECT e.id_empresa, COALESCE(e.nome_fantasia, e.razao_social) AS nome_empresa
                        FROM usuario_empresa ue
                        JOIN empresa e ON e.id_empresa = ue.id_empresa AND e.id_tenant = ue.id_tenant
                        WHERE ue.id_usuario = ? AND ue.id_tenant = plataforma.tenant_atual()
                        ORDER BY e.codigo_empresa ASC
                        """)
                .param(usuario.idUsuario())
                .query((rs, n) -> new EmpresaOpcaoLogin(rs.getLong("id_empresa"), rs.getString("nome_empresa")))
                .list();

        if (empresas.isEmpty()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Usuário sem empresa vinculada. Contate o administrador.");
        }

        // idEmpresa informado (segunda volta ou já sabido pelo front) é sempre validado contra
        // a lista de acesso, mesmo quando o usuário só tem uma empresa — nunca aceita "de
        // graça" só porque bateu por coincidência de ser a única.
        long idEmpresa;
        if (req.idEmpresa() != null) {
            boolean permitida = empresas.stream().anyMatch(e -> e.idEmpresa() == req.idEmpresa());
            if (!permitida) {
                throw new ResponseStatusException(UNAUTHORIZED, "Empresa inválida para este usuário.");
            }
            idEmpresa = req.idEmpresa();
        } else if (empresas.size() == 1) {
            idEmpresa = empresas.get(0).idEmpresa();
        } else {
            return new TokenResponse(null, idTenant, req.slug(), true, empresas);
        }

        List<String> roles = List.of(usuario.administrador() ? "ADMIN" : "OPERADOR");
        String token = tokens.emitir(usuario.idUsuario(), idTenant, idEmpresa, req.email(), roles);
        return new TokenResponse(token, idTenant, req.slug(), false, List.of());
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
