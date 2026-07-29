package com.vetor.niner.identidade.empresa;

import com.vetor.niner.identidade.empresa.EmpresaDtos.EmpresaResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    private static EmpresaResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new EmpresaResponse(
                rs.getLong("id_empresa"),
                rs.getInt("codigo_empresa"),
                rs.getString("razao_social"),
                rs.getString("nome_fantasia"),
                rs.getBoolean("ativo"));
    }
}
