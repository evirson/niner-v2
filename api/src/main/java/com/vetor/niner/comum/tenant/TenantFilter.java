package com.vetor.niner.comum.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Estabelece o {@link TenantContext} da requisição a partir do claim {@code tid}
 * do JWT (spec §3.1.1). Registrado apenas na cadeia de segurança de {@code /api/v1/**}
 * (superfície do ERP do tenant); publico/admin não estabelecem tenant.
 *
 * <p>Roda depois da autenticação, então lê o {@code tid} do
 * {@link JwtAuthenticationToken} já presente no {@code SecurityContext}. Sem tenant
 * (token ausente), segue a cadeia sem ligar contexto.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Long idTenant = extrairTid();
        if (idTenant == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            TenantContext.comTenant(idTenant, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    private static Long extrairTid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            Object tid = jwt.getToken().getClaims().get("tid");
            if (tid instanceof Number n) {
                return n.longValue();
            }
            if (tid instanceof String s && !s.isBlank()) {
                return Long.valueOf(s.trim());
            }
        }
        return null;
    }
}
