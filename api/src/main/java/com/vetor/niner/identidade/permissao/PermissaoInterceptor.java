package com.vetor.niner.identidade.permissao;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Aplica o RBAC antes de o método do controller rodar (V073).
 *
 * <p><b>Esta é a trava que vale.</b> O menu escondido e os botões desabilitados evitam o erro
 * honesto do operador; não evitam quem digita a URL ou chama a API direto. Enquanto isto não
 * existiu, o RBAC era controle de interface, e um controle de acesso que parece proteger e não
 * protege é pior que nenhum — o administrador confia nele para separar responsabilidades.
 *
 * <p><b>O que NÃO é bloqueado, de propósito:</b>
 * <ul>
 *   <li>controller sem {@link Tela} — consultas auxiliares entre domínios (ver o javadoc de
 *       {@code Tela});</li>
 *   <li>requisição sem JWT de tenant — a superfície pública e o backoffice de staff têm as
 *       próprias regras (P9), e este interceptor não tem o que checar ali.</li>
 * </ul>
 */
@Component
public class PermissaoInterceptor implements HandlerInterceptor {

    private final PermissaoService permissoes;

    public PermissaoInterceptor(PermissaoService permissoes) {
        this.permissoes = permissoes;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        if (!(handler instanceof HandlerMethod metodo)) {
            return true;
        }
        // Método explicitamente livre vence a anotação do controller — ver Livre.java.
        if (metodo.getMethodAnnotation(Livre.class) != null) {
            return true;
        }

        Tela tela = metodo.getMethodAnnotation(Tela.class);
        if (tela == null) {
            tela = metodo.getBeanType().getAnnotation(Tela.class);
        }
        if (tela == null) {
            return true;
        }

        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Jwt jwt)) {
            return true;
        }

        Acao anotada = metodo.getMethodAnnotation(Acao.class);
        PermissaoService.Acao acao = anotada != null ? anotada.value() : porVerbo(req.getMethod());
        // Qualquer uma das chaves basta — ver o javadoc de `Tela.value()`. Com uma só (o caso
        // normal) o laço é idêntico ao `exigir` de antes.
        String[] chaves = tela.value();
        for (String chave : chaves) {
            if (permissoes.pode(jwt, chave, acao)) {
                return true;
            }
        }
        // Nenhuma passou: o 403 nomeia a PRIMEIRA, que é a tela dona do endpoint — citar todas
        // faria a mensagem descrever a implementação em vez do que o usuário precisa pedir.
        permissoes.exigir(jwt, chaves[0], acao);
        return true;
    }

    /**
     * Tradução padrão. ⚠️ Vale só para o caminho comum — rotina em que o verbo não traduz a
     * intenção declara a ação com {@link Acao}, e isso não é exceção rara neste ERP (cancelar,
     * estornar, reabrir, inutilizar são todos POST).
     */
    private static PermissaoService.Acao porVerbo(String verbo) {
        return switch (verbo) {
            case "POST" -> PermissaoService.Acao.INCLUIR;
            case "PUT", "PATCH" -> PermissaoService.Acao.ALTERAR;
            case "DELETE" -> PermissaoService.Acao.EXCLUIR;
            default -> PermissaoService.Acao.ACESSAR;
        };
    }
}
