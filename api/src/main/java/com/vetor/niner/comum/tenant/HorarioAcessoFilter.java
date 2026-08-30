package com.vetor.niner.comum.tenant;

import com.vetor.niner.identidade.usuario.HorarioAcessoService;
import com.vetor.niner.identidade.usuario.SessaoDoUsuarioService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Porteiro de cada requisição autenticada de tenant: <b>horário de acesso</b> (2026-08-11) e
 * <b>validade da sessão</b> (V080, 2026-08-27), decididos por uma consulta só
 * ({@link SessaoDoUsuarioService}).
 *
 * <p>Registrado logo depois de {@link TenantFilter} na cadeia de {@code /api/v1/**} — precisa do
 * {@link TenantContext} já ativo para consultar {@code plataforma.tenant_atual()}.
 *
 * <p><b>Horário de acesso:</b> {@link HorarioAcessoService#TOLERANCIA_MINUTOS_PADRAO} de tolerância
 * sobre o fim do dia — tempo de sobra para terminar/imprimir uma venda já iniciada antes do horário
 * fechar; o login (sem tolerância) é quem garante que uma sessão NOVA nunca começa fora da janela.
 * ADMIN e usuário com o controle desligado nunca são barrados.
 *
 * <p><b>Validade da sessão:</b> trocar a senha, desativar ou excluir o usuário faz
 * {@code usuario.sessao_valida_desde} avançar, e todo token emitido antes disso é recusado com
 * <b>401</b> na requisição seguinte. Antes da V080 o token vivia as 8 horas inteiras: demitir
 * alguém não o tirava do sistema.
 *
 * <p>⚠️ <b>Só age sobre token de tenant.</b> Token de staff ({@code /api/admin}) não tem
 * {@code tid}, não passa por {@link TenantContext}, e a consulta aqui responderia "usuário não
 * existe" — o que, depois da V080, derrubaria o backoffice inteiro. Sem {@code tid}, este filtro
 * não é da conta dele.
 */
@Component
public class HorarioAcessoFilter extends OncePerRequestFilter {

    private final SessaoDoUsuarioService sessoes;

    public HorarioAcessoFilter(SessaoDoUsuarioService sessoes) {
        this.sessoes = sessoes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Jwt token = extrairToken();
        Long idUsuario = idDoUsuario(token);
        if (idUsuario == null || token.getClaim("tid") == null) {
            chain.doFilter(request, response);
            return;
        }

        // ⚠️ O `eid` vai junto: a janela de horário é a da empresa em que a pessoa ESTÁ operando,
        // não a do cadastro dela (auditoria 2026-08-29, rodada 3).
        Object eid = token.getClaim("eid");
        var veredito = sessoes.avaliar(idUsuario, HorarioAcessoService.TOLERANCIA_MINUTOS_PADRAO,
                token.getIssuedAt(), eid == null ? null : ((Number) eid).longValue());
        switch (veredito) {
            case OK -> chain.doFilter(request, response);
            case FORA_DO_HORARIO -> recusar(response, HttpServletResponse.SC_FORBIDDEN,
                    HorarioAcessoService.MENSAGEM_FORA_DA_JANELA);
            // 401 e não 403: o problema não é permissão, é a sessão — o front trata 401 mandando
            // para o login, que é exatamente o que precisa acontecer aqui.
            case SESSAO_ENCERRADA -> recusar(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Sua sessão foi encerrada. Entre novamente.");
        }
    }

    private static void recusar(HttpServletResponse response, int status, String detalhe) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"detail\":\"" + detalhe + "\"}");
    }

    private static Jwt extrairToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken() : null;
    }

    private static Long idDoUsuario(Jwt token) {
        if (token == null) {
            return null;
        }
        String sub = token.getSubject();
        if (sub == null || sub.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(sub.trim());
        } catch (NumberFormatException e) {
            // sub não numérico: não é papel deste filtro validar identidade — deixa passar, como
            // se não houvesse idUsuario, e a camada de autenticação real reage.
            return null;
        }
    }
}
