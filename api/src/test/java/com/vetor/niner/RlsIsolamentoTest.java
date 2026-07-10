package com.vetor.niner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gate P8 (spec §Roadmap Fase 0): um tenant nunca lê/escreve dado de outro.
 *
 * <p>Conecta como <b>niner_app</b> (sem BYPASSRLS) — não como o superusuário do
 * container, que ignoraria o RLS — e valida contra o banco real (Testcontainers, com
 * as migrations V001–V024 aplicadas) que o Row-Level Security de domínio (V024) isola
 * os tenants pelo {@code app.id_tenant} (materializado em produção pelo
 * {@link com.vetor.niner.comum.tenant.TenantAwareTransactionManager}).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RlsIsolamentoTest {

    @Autowired
    PostgreSQLContainer postgres;

    private Connection conexaoApp() throws SQLException {
        // Role da aplicação, SEM BYPASSRLS (criada em bootstrap-test.sql).
        return DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
    }

    @Test
    void tenantNaoEnxergaDadoDeOutro() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            long t1 = inserirTenant(st, "iso-p8-t1");
            long t2 = inserirTenant(st, "iso-p8-t2");
            try {
                st.execute("SET app.id_tenant = " + t1);
                st.executeUpdate("INSERT INTO produto(id_tenant, descricao) VALUES (" + t1 + ", 'P do T1')");

                st.execute("SET app.id_tenant = " + t2);
                st.executeUpdate("INSERT INTO produto(id_tenant, descricao) VALUES (" + t2 + ", 'P do T2')");

                // Volta para T1: enxerga só o próprio.
                st.execute("SET app.id_tenant = " + t1);
                assertThat(contar(st, "SELECT count(*) FROM produto")).isEqualTo(1);
                assertThat(contar(st, "SELECT count(*) FROM produto WHERE descricao = 'P do T2'")).isZero();

                // WITH CHECK bloqueia gravar para outro tenant.
                assertThatThrownBy(() ->
                        st.executeUpdate("INSERT INTO produto(id_tenant, descricao) VALUES (" + t2 + ", 'Invasor')"))
                        .isInstanceOf(SQLException.class);

                // Sem contexto de tenant: não enxerga nada.
                st.execute("SET app.id_tenant = ''");
                assertThat(contar(st, "SELECT count(*) FROM produto")).isZero();
            } finally {
                limpar(st, t1, t2);
            }
        }
    }

    private static long inserirTenant(Statement st, String slug) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "INSERT INTO plataforma.tenant(nome_conta, slug, email_contato) VALUES ('"
                        + slug + "','" + slug + "','" + slug + "@x') RETURNING id_tenant")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long contar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void limpar(Statement st, long t1, long t2) throws SQLException {
        st.execute("SET app.id_tenant = " + t1);
        st.executeUpdate("DELETE FROM produto");
        st.execute("SET app.id_tenant = " + t2);
        st.executeUpdate("DELETE FROM produto");
        st.executeUpdate("DELETE FROM plataforma.tenant WHERE id_tenant IN (" + t1 + ", " + t2 + ")");
    }
}
