package com.vetor.niner.identidade.permissao;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra o {@link PermissaoInterceptor} na superfície do ERP.
 *
 * <p>⚠️ Só em {@code /api/v1/**}: {@code /api/publico} não tem usuário para consultar e
 * {@code /api/admin} é a superfície de staff, que tem regra própria (P9) e nem carrega o claim de
 * tenant. Registrar nas três faria o interceptor rodar onde não há o que decidir.
 */
@Configuration
public class PermissaoWebConfig implements WebMvcConfigurer {

    private final PermissaoInterceptor interceptor;

    public PermissaoWebConfig(PermissaoInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");
    }
}
