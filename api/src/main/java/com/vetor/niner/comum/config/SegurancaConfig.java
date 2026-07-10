package com.vetor.niner.comum.config;

import com.vetor.niner.comum.tenant.TenantFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Uma API, três superfícies, três {@link SecurityFilterChain} distintos (ADR-007,
 * spec §3.1). Cada cadeia casa por prefixo de path e é stateless (JWT, sem sessão):
 *
 * <ul>
 *   <li>{@code /api/publico/**} — site público (signup/checkout/trial). Anônimo.</li>
 *   <li>{@code /api/v1/**} — ERP do tenant. Estabelece o {@link TenantFilter}
 *       (contexto de tenant a partir do claim {@code tid}); RLS ativo no banco.</li>
 *   <li>{@code /api/admin/**} — backoffice da plataforma (staff). Opera em
 *       {@code plataforma.*} (global).</li>
 * </ul>
 *
 * <p>TODO(jwt): as cadeias de tenant/admin ainda não exigem token porque não há
 * emissor de JWT nesta fase. Quando existir, trocar o {@code permitAll} por
 * {@code authenticated()} + {@code oauth2ResourceServer(jwt)} validando o {@code aud}
 * ({@code tenant} × {@code plataforma}) — ver application.yml e ADR-009.
 */
@Configuration(proxyBeanMethods = false)
public class SegurancaConfig {

    @Bean
    @Order(1)
    SecurityFilterChain publicoFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/publico/**", "/actuator/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain tenantFilterChain(HttpSecurity http, TenantFilter tenantFilter) throws Exception {
        http
                .securityMatcher("/api/v1/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // JWT de tenant (aud=tenant validado no JwtDecoder). Emissão no login/signup.
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // estabelece o TenantContext após a autenticação, dentro da cadeia do tenant
                .addFilterAfter(tenantFilter, AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain adminFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/admin/**")
                // TODO(jwt): .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("SCOPE_admin"))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /** Nega tudo que não casa com as três superfícies acima. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /** CORS para os fronts (site/web/admin) chamarem a API. Origens em niner.cors.origins. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(NinerProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.cors().origins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
