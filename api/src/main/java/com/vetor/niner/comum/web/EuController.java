package com.vetor.niner.comum.web;

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

    public EuController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/eu")
    @Transactional(readOnly = true)
    public Map<String, Object> eu(@AuthenticationPrincipal Jwt jwt) {
        long idTenant = ((Number) jwt.getClaim("tid")).longValue();
        long idUsuario = Long.parseLong(jwt.getSubject());

        var usuario = jdbc.sql("SELECT nome_usuario, email, administrador FROM usuario WHERE id_usuario = ?")
                .param(idUsuario)
                .query((rs, n) -> Map.<String, Object>of(
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

        OffsetDateTime trialExpiraEm = jdbc.sql(
                        "SELECT trial_expira_em FROM plataforma.assinatura WHERE id_tenant = ? AND status <> 'CANCELADA'")
                .param(idTenant).query(OffsetDateTime.class).optional().orElse(null);

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("id_tenant", idTenant);
        corpo.put("conta", conta);
        corpo.put("usuario", usuario);
        corpo.put("trial_expira_em", trialExpiraEm);
        return corpo;
    }
}
