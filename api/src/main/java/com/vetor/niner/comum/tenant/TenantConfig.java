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
        return new TenantAwareTransactionManager(dataSource);
    }
}
