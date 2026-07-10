package com.vetor.niner;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres real para os testes (mesma major de produção: {@code postgres:18}).
 *
 * <p>O {@code bootstrap-test.sql} roda na inicialização do container e cria as roles
 * {@code niner_owner}/{@code niner_app} — necessárias porque as migrations usam
 * {@code AUTHORIZATION niner_owner} (V001) e concedem grants a {@code niner_app} (V011).
 * O Flyway (habilitado só no perfil de teste, ver {@code src/test/resources/application.yml})
 * aplica {@code db/migration} sobre esse banco.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
				.withInitScript("bootstrap-test.sql");
	}

}
