package com.vetor.niner.comum.web;

import com.vetor.niner.identidade.usuario.HorarioAcessoService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Quem sou eu" do tenant logado (§3.4). Prova o primeiro uso pós-signup: com o token
 * do trial, o cliente já enxerga a própria conta. A leitura de {@code usuario} passa
 * pelo RLS (V024) — o contexto vem do claim {@code tid} via TenantAwareTransactionManager.
 */
@RestController
public class EuController {

    private final JdbcClient jdbc;
    private final HorarioAcessoService horarioAcesso;

    public EuController(JdbcClient jdbc, HorarioAcessoService horarioAcesso) {
        this.jdbc = jdbc;
        this.horarioAcesso = horarioAcesso;
    }

    @GetMapping("/api/v1/eu")
    @Transactional(readOnly = true)
    public Map<String, Object> eu(@AuthenticationPrincipal Jwt jwt) {
        long idTenant = ((Number) jwt.getClaim("tid")).longValue();
        long idUsuario = Long.parseLong(jwt.getSubject());
        long idEmpresa = ((Number) jwt.getClaim("eid")).longValue();

        var usuario = jdbc.sql(
                        "SELECT id_usuario, nome_usuario, email, administrador FROM usuario WHERE id_tenant = ? AND id_usuario = ?")
                .params(idTenant, idUsuario)
                .query((rs, n) -> Map.<String, Object>of(
                        "idUsuario", rs.getLong("id_usuario"),
                        "nome", rs.getString("nome_usuario"),
                        "email", rs.getString("email"),
                        "papel", rs.getBoolean("administrador") ? "ADMIN" : "OPERADOR"))
                .single();

        var conta = jdbc.sql("SELECT nome_conta, slug, status::text AS status FROM plataforma.tenant WHERE id_tenant = ?")
                .param(idTenant)
                .query((rs, n) -> Map.<String, Object>of(
                        "nomeConta", rs.getString("nome_conta"),
                        "slug", rs.getString("slug"),
                        "status", rs.getString("status")))
                .single();

        // Empresa ativa da sessão (claim eid, 2026-07-28) — escolhida no login quando o
        // usuário tem acesso a mais de uma (usuario_empresa). Exibida no header do front pra
        // deixar claro em qual empresa os cadastros feitos nesta sessão vão cair.
        var empresa = jdbc.sql(
                        "SELECT id_empresa, COALESCE(nome_fantasia, razao_social) AS nome FROM empresa WHERE id_tenant = ? AND id_empresa = ?")
                .params(idTenant, idEmpresa)
                .query((rs, n) -> Map.<String, Object>of(
                        "idEmpresa", rs.getLong("id_empresa"),
                        "nome", rs.getString("nome")))
                .single();

        OffsetDateTime trialExpiraEm = jdbc.sql(
                        "SELECT trial_expira_em FROM plataforma.assinatura WHERE id_tenant = ? AND status <> 'CANCELADA'")
                .param(idTenant).query(OffsetDateTime.class).optional().orElse(null);

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("id_tenant", idTenant);
        corpo.put("conta", conta);
        corpo.put("usuario", usuario);
        corpo.put("empresa", empresa);
        corpo.put("trial_expira_em", trialExpiraEm);
        // Contagem regressiva do aviso visual de horário de acesso (HorarioAcessoGuard,
        // 2026-08-14) — null na imensa maioria das chamadas (ADMIN, controle desligado, ou
        // dentro do horário normal); só vem preenchido nos minutos finais de tolerância antes
        // do bloqueio de verdade (HorarioAcessoFilter, mesma janela de graça).
        corpo.put("segundosRestantesTolerancia",
                horarioAcesso.segundosRestantesTolerancia(idUsuario, HorarioAcessoService.TOLERANCIA_MINUTOS_PADRAO));
        return corpo;
    }
}
