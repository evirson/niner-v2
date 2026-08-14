package com.vetor.niner.comum.tenant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Liga a infra de contexto de tenant (P8): substitui o transaction manager padrão
 * pelo {@link TenantAwareTransactionManager}, que aplica {@code app.id_tenant} por
 * transação. O {@link TenantFilter} é um {@code @Component} registrado na cadeia de
 * segurança de {@code /api/v1/**} (ver SegurancaConfig).
 */
@Configuration(proxyBeanMethods = false)
public class TenantConfig {

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        TenantAwareTransactionManager tm = new TenantAwareTransactionManager(dataSource);
        // Habilita PROPAGATION_NESTED (savepoint) — usado por ImportacaoSavepointExecutor para
        // isolar cada linha de planilha sem abortar a transação do arquivo inteiro (2026-08-10).
        tm.setNestedTransactionAllowed(true);
        return tm;
    }
}
