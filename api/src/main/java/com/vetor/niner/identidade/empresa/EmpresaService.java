package com.vetor.niner.identidade.empresa;

import com.vetor.niner.identidade.empresa.EmpresaDtos.EmpresaResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Leitura de empresas do tenant, sem paginação — hoje o v1 tem no máximo poucas dezenas.
 *
 * <p>Filtro por {@code id_tenant} explícito além do RLS — mesmo motivo documentado em
 * {@code ClienteHistoricoService}/{@code TipoCarteiraService} (Testcontainers conecta como
 * superusuário, que ignora RLS mesmo com {@code FORCE}).
 */
@Service
public class EmpresaService {

    private final JdbcClient jdbc;

    public EmpresaService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<EmpresaResponse> listar() {
        return jdbc.sql("""
                        SELECT id_empresa, codigo_empresa, razao_social, nome_fantasia, ativo
                        FROM empresa
                        WHERE id_tenant = plataforma.tenant_atual()
                        ORDER BY codigo_empresa ASC
                        """)
                .query(EmpresaService::mapear)
                .list();
    }

    /**
     * Empresas que o usuário logado pode operar — usado pela Entrada de Produtos por Compra
     * pra saber em qual empresa dar entrada (2026-08-11). ADMIN vê todas do tenant (mesmo
     * resultado de {@link #listar()}); OPERADOR só as ligadas a ele via {@code usuario_empresa}
     * (mesma query de {@code SignupService.login}, que lista as empresas de um usuário no
     * login em duas voltas).
     */
    @Transactional(readOnly = true)
    public List<EmpresaResponse> listarPermitidas(Jwt jwt) {
        if (ehAdmin(jwt)) {
            return listar();
        }
        long idUsuario = Long.parseLong(jwt.getSubject());
        return jdbc.sql("""
                        SELECT e.id_empresa, e.codigo_empresa, e.razao_social, e.nome_fantasia, e.ativo
                        FROM usuario_empresa ue
                        JOIN empresa e ON e.id_empresa = ue.id_empresa AND e.id_tenant = ue.id_tenant
                        WHERE ue.id_tenant = plataforma.tenant_atual() AND ue.id_usuario = ?
                        ORDER BY e.codigo_empresa ASC
                        """)
                .param(idUsuario)
                .query(EmpresaService::mapear)
                .list();
    }

    private static boolean ehAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }

    private static EmpresaResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new EmpresaResponse(
                rs.getLong("id_empresa"),
                rs.getInt("codigo_empresa"),
                rs.getString("razao_social"),
                rs.getString("nome_fantasia"),
                rs.getBoolean("ativo"));
    }
}
