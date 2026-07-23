package com.vetor.niner.comum.armazenamento;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.vetor.niner.comum.config.NinerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

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
}
