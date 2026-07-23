package com.vetor.niner;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Object storage fake (`fsouza/fake-gcs-server`) para testar upload/exclusão de imagem de
 * produto sem depender de credencial de GCS real (ADR-013) — nenhum teste toca o bucket de
 * verdade. Substitui o bean {@code Storage} de {@code ArmazenamentoConfig} (que é {@code
 * @Lazy} e aponta pro GCS de verdade) por um apontando pro container fake, via {@code
 * @Primary}. O bucket configurado em {@code niner.storage.bucket} (application.yml de teste,
 * {@code niner-erp-dev}) é criado aqui antes de qualquer teste rodar.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeGcsConfiguration {

    private static final String BUCKET_TESTE = "niner-erp-dev";

    private static final GenericContainer<?> FAKE_GCS =
            new GenericContainer<>(DockerImageName.parse("fsouza/fake-gcs-server:1.49"))
                    .withCommand("-scheme", "http", "-public-host", "localhost")
                    .withExposedPorts(4443)
                    .waitingFor(Wait.forHttp("/storage/v1/b").forStatusCode(200));

    static {
        FAKE_GCS.start();
    }

    // Nome de bean diferente de "storage" (o bean @Lazy de ArmazenamentoConfig) — mesmo nome
    // nos dois seria um *override* de bean definition (bloqueado por padrão no Spring Boot),
    // não só uma questão de @Primary. @Primary aqui resolve pela injeção por TIPO.
    @Bean
    @Primary
    Storage storageFake() {
        String host = "http://" + FAKE_GCS.getHost() + ":" + FAKE_GCS.getMappedPort(4443);
        Storage storage = StorageOptions.newBuilder()
                .setHost(host)
                .setProjectId("niner-test")
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
        if (storage.get(BUCKET_TESTE) == null) {
            storage.create(BucketInfo.of(BUCKET_TESTE));
        }
        return storage;
    }
}
