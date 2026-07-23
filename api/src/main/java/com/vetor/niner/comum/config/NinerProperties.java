package com.vetor.niner.comum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuração da aplicação (prefixo {@code niner}): assinatura de JWT, parâmetros do
 * trial, CORS e object storage. Valores em {@code application.yml}; segredo do JWT via
 * secret manager em prod.
 */
@ConfigurationProperties("niner")
public record NinerProperties(Jwt jwt, Trial trial, Cors cors, Storage storage) {

    public record Jwt(String secret, Duration expiracao, String emissor) {
    }

    public record Trial(int dias, String plano) {
    }

    /** Origens permitidas dos fronts (site/web/admin) para CORS. */
    public record Cors(List<String> origins) {
    }

    /** Bucket/base-url do object storage das fotos de produto (ADR-013). {@code host} vazio
     * = GCS real (ADC/chave); preenchido (ex.: {@code http://localhost:4443}) = emulador
     * fake-gcs-server, sem credencial — modo dev, ver docs/infra/armazenamento-imagens.md §3. */
    public record Storage(String bucket, String baseUrl, String host) {
    }
}
