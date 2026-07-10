package com.vetor.niner.comum.tenant;

import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Transaction manager que materializa o tenant do {@link TenantContext} na conexão
 * da transação via {@code app.id_tenant} — base das políticas RLS de domínio (P8,
 * spec §3.1.1).
 *
 * <p>Logo após o início da transação, executa
 * {@code select set_config('app.id_tenant', :tid, true)} (equivalente parametrizável
 * e injection-safe de {@code SET LOCAL}). O {@code true} torna o valor local à
 * transação — some no commit/rollback, sem vazar entre conexões do pool. Quando não
 * há tenant no contexto (superfícies publico/admin, tabelas globais de plataforma),
 * nada é definido.
 */
public class TenantAwareTransactionManager extends JdbcTransactionManager {

    private static final String SET_TENANT_SQL = "select set_config('app.id_tenant', ?, true)";

    public TenantAwareTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        Long idTenant = TenantContext.idTenantAtualOuNull();
        if (idTenant == null) {
            return;
        }
        ConnectionHolder holder =
                (ConnectionHolder) TransactionSynchronizationManager.getResource(obtainDataSource());
        if (holder == null) {
            return;
        }
        try (PreparedStatement ps = holder.getConnection().prepareStatement(SET_TENANT_SQL)) {
            ps.setString(1, Long.toString(idTenant));
            ps.execute();
        } catch (SQLException e) {
            throw new CannotCreateTransactionException("Falha ao definir app.id_tenant na transação", e);
        }
    }
}
