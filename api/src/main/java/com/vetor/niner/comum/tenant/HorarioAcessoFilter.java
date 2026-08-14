package com.vetor.niner.comum.tenant;

import com.vetor.niner.identidade.usuario.HorarioAcessoService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bloqueia toda requisição autenticada de um OPERADOR fora do horário de acesso permitido
 * (docs/telas/usuario.md, 2026-08-11) — registrado logo depois de {@link TenantFilter} na
 * cadeia de {@code /api/v1/**} (precisa do {@link TenantContext} já ativo pra consultar
 * {@code plataforma.tenant_atual()}). {@link HorarioAcessoService#TOLERANCIA_MINUTOS_PADRAO} de
 * tolerância sobre o horário final do dia ({@link HorarioAcessoService#podeAcessarAgora}) —
 * tempo de sobra pra terminar/imprimir uma venda já iniciada antes do horário fechar; o login
 * (sem tolerância) é quem garante que uma sessão NOVA nunca começa fora da janela.
 *
 * <p>ADMIN e usuário com o controle desligado nunca são barrados aqui — a checagem inteira
 * (inclusive essas duas exceções) mora em {@link HorarioAcessoService#podeAcessarAgora}, único
 * lugar de verdade pra essa regra (mesma checagem usada no login).
 */
@Component
public class HorarioAcessoFilter extends OncePerRequestFilter {

    private final HorarioAcessoService horarioAcesso;

    public HorarioAcessoFilter(HorarioAcessoService horarioAcesso) {
        this.horarioAcesso = horarioAcesso;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Long idUsuario = extrairIdUsuario();
        if (idUsuario == null
                || horarioAcesso.podeAcessarAgora(idUsuario, HorarioAcessoService.TOLERANCIA_MINUTOS_PADRAO)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"detail\":\"" + HorarioAcessoService.MENSAGEM_FORA_DA_JANELA + "\"}");
    }

    private static Long extrairIdUsuario() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String sub = jwt.getToken().getSubject();
            if (sub != null && !sub.isBlank()) {
                try {
                    return Long.valueOf(sub.trim());
                } catch (NumberFormatException e) {
                    // sub não numérico: não é papel deste filtro validar identidade — deixa
                    // passar, como se não houvesse idUsuario (mesma checagem que já existe
                    // pra sub ausente), e a camada de autenticação/autorização real reage.
                    return null;
                }
            }
        }
        return null;
    }
}
