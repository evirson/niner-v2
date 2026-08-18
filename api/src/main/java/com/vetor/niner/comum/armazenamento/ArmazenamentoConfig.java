package com.vetor.niner.comum.armazenamento;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.vetor.niner.comum.config.NinerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * Cliente do GCS como bean {@code @Lazy}: só é construído (e só então tenta autenticar) no
 * primeiro uso real — a API sobe normalmente mesmo sem credencial de GCS configurada (dev
 * sem `gcloud`/ADC ainda), e testes podem trocar este bean por um apontando pro
 * fake-gcs-server (ver {@code FakeGcsConfiguration} nos testes).
 *
 * <p>Com {@code niner.storage.host} preenchido (env {@code NINER_STORAGE_HOST}, ex.:
 * {@code http://localhost:4443}), o cliente aponta pro emulador fake-gcs-server do
 * docker-compose <b>sem credencial nenhuma</b> e cria o bucket na hora se não existir —
 * modo dev sem gcloud/ADC/chave (docs/infra/armazenamento-imagens.md §3, Opção C). Host
 * vazio (default) = GCS real via Application Default Credentials.
 */
@Configuration(proxyBeanMethods = false)
public class ArmazenamentoConfig {

    @Bean
    @Lazy
    Storage storage(NinerProperties props) {
        String host = props.storage().host();
        if (host == null || host.isBlank()) {
            return StorageOptions.getDefaultInstance().getService();
        }
        Storage storage = StorageOptions.newBuilder()
                .setHost(host)
                .setProjectId("niner-dev")
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
        if (storage.get(props.storage().bucket()) == null) {
            storage.create(BucketInfo.of(props.storage().bucket()));
        }
        return storage;
    }

    /**
     * Cliente S3 do object storage <b>privado</b> (ADR-014) — MinIO. Também {@code @Lazy}, e pelo
     * mesmo motivo do bean acima: com o MinIO fora do ar a API sobe normalmente e a falha aparece
     * na primeira gravação, traduzida por {@link S3ArmazenamentoPrivado}, em vez de derrubar o
     * contexto do Spring na subida.
     *
     * <p>{@code forcePathStyle} é obrigatório: MinIO endereça bucket por caminho
     * ({@code http://host/bucket/chave}), não por subdomínio como a AWS. Sem isso o SDK tenta
     * resolver {@code niner-fiscal-dev.minio} e falha em DNS, com erro que não menciona nada disso.
     * A região é irrelevante para o MinIO, mas o SDK exige uma — daí o default {@code us-east-1}.
     */
    @Bean
    @Lazy
    S3Client s3ClientPrivado(NinerProperties props) {
        NinerProperties.Privado privado = props.storage().privado();
        return S3Client.builder()
                .endpointOverride(URI.create(privado.endpoint()))
                .region(Region.of(privado.regiao()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(privado.accessKey(), privado.secretKey())))
                .forcePathStyle(true)
                .build();
    }
}
