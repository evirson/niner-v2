package com.vetor.niner.comum.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Infra de autenticação: emissão e validação de JWT (HS256, segredo simétrico) e
 * hashing de senha (BCrypt). O mesmo segredo assina (login/signup) e valida
 * (resource server em {@code /api/v1}). Tokens de tenant carregam {@code aud=tenant}
 * e o claim {@code tid} (ADR-009).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NinerProperties.class)
public class JwtConfig {

    private final SecretKeySpec chave;

    JwtConfig(NinerProperties props) {
        this.chave = new SecretKeySpec(
                props.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(chave));
    }

    /**
     * Decoder da superfície do lojista ({@code /api/v1}): assinatura, expiração e {@code
     * aud=tenant}. É o {@code @Primary} porque é o caminho da maioria esmagadora das requisições.
     */
    @Bean
    @Primary
    JwtDecoder jwtDecoder() {
        return decoderPara("tenant");
    }

    /**
     * Decoder da superfície do backoffice ({@code /api/admin}): exige {@code aud=plataforma}.
     *
     * <p><b>Dois decoders, não um que aceite os dois `aud`.</b> Com um decoder permissivo, um
     * token de lojista entraria no backoffice (e vice-versa) e a separação de populações (R18/P9)
     * viraria só convenção. Assim, o token errado é rejeitado na porta, antes de qualquer
     * checagem de papel.
     */
    @Bean
    JwtDecoder jwtDecoderStaff() {
        return decoderPara("plataforma");
    }

    private JwtDecoder decoderPara(String audiencia) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(chave)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtClaimValidator<List<String>>("aud",
                        aud -> aud != null && aud.contains(audiencia))));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
