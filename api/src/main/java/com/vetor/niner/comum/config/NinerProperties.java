package com.vetor.niner.comum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuração da aplicação (prefixo {@code niner}): assinatura de JWT e parâmetros
 * do trial. Valores em {@code application.yml}; segredo do JWT via secret manager em prod.
 */
@ConfigurationProperties("niner")
public record NinerProperties(Jwt jwt, Trial trial) {

    public record Jwt(String secret, Duration expiracao, String emissor) {
    }

    public record Trial(int dias, String plano) {
    }
}
