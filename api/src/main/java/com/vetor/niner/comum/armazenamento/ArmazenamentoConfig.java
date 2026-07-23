package com.vetor.niner.comum.armazenamento;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Cliente do GCS como bean {@code @Lazy}: só é construído (e só então tenta autenticar) no
 * primeiro uso real — a API sobe normalmente mesmo sem credencial de GCS configurada (dev
 * sem `gcloud`/ADC ainda), e testes podem trocar este bean por um apontando pro
 * fake-gcs-server (ver {@code FakeGcsConfiguration} nos testes).
 */
@Configuration(proxyBeanMethods = false)
public class ArmazenamentoConfig {

    @Bean
    @Lazy
    Storage storage() {
        return StorageOptions.getDefaultInstance().getService();
    }
}
