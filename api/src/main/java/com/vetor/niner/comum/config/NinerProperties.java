package com.vetor.niner.comum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuração da aplicação (prefixo {@code niner}): assinatura de JWT, parâmetros do
 * trial e CORS. Valores em {@code application.yml}; segredo do JWT via secret manager em prod.
 */
@ConfigurationProperties("niner")
public record NinerProperties(Jwt jwt, Trial trial, Cors cors) {

    public record Jwt(String secret, Duration expiracao, String emissor) {
    }

    public record Trial(int dias, String plano) {
    }

    /** Origens permitidas dos fronts (site/web/admin) para CORS. */
    public record Cors(List<String> origins) {
    }
}
