package com.vetor.niner.comum.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O IP real do cliente, atrás do proxy — <b>fonte única</b> do projeto.
 *
 * <p><b>Por que existe</b> (2026-09-01): esta lógica vivia privada dentro do
 * {@link LimiteRequisicaoFilter}, e por isso o resto do sistema usava {@code getRemoteAddr()} cru —
 * que <b>atrás do nginx devolve o IP do proxy</b>, não o do cliente. Era o que ia (errado) para
 * {@code codigo_login.ip_solicitante} e {@code recuperacao_senha.ip_solicitante}, e teria ido para o
 * log de acesso. ⚠️ E o defeito nasceria <b>certo em dev</b> (sem proxy) e errado só em produção,
 * que é a pior combinação possível.
 *
 * <p>⛔ <b>Nunca o primeiro elemento de {@code X-Forwarded-For}</b> (achado da auditoria de
 * segurança de 2026-08-27): o nginx usa {@code proxy_add_x_forwarded_for}, que <b>acrescenta</b> o
 * IP real ao <b>fim</b> da lista e <b>preserva</b> o que o cliente mandou no começo. Lendo o
 * primeiro, qualquer um passa {@code X-Forwarded-For: 10.0.0.<aleatório>} e escolhe o próprio IP —
 * no rate limit isso fazia o limite deixar de existir; num log de auditoria faria a trilha inteira
 * apontar para endereços inventados.
 *
 * <p>⭐ O valor vem de {@code X-Real-IP}, que o nginx <b>sobrescreve</b> com {@code $remote_addr}
 * (`infra/nginx/nainer.conf`) — o cliente não tem como forjá-lo. O último salto do
 * {@code X-Forwarded-For} é a reserva para um proxy que não mande {@code X-Real-IP}: é o salto mais
 * próximo, o único que o cliente não escolhe.
 *
 * <p>⚠️ {@link #confiavel()} é <b>gravado junto do IP</b> no log de acesso. Sem isso, daqui a um ano
 * ninguém sabe se aquele endereço é do cliente ou do nginx — e um dado de auditoria que não se
 * explica sozinho vira discussão.
 */
@Component
public class IpDoCliente {

    private final boolean confiarProxy;

    public IpDoCliente(@Value("${niner.limite-requisicao.confiar-proxy:false}") boolean confiarProxy) {
        this.confiarProxy = confiarProxy;
    }

    /** {@code true} quando o cabeçalho de proxy é considerado — só em produção, atrás do nginx. */
    public boolean confiavel() {
        return confiarProxy;
    }

    public String de(HttpServletRequest req) {
        if (confiarProxy) {
            String real = req.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real.trim();
            }
            String encaminhado = req.getHeader("X-Forwarded-For");
            if (encaminhado != null && !encaminhado.isBlank()) {
                String[] saltos = encaminhado.split(",");
                return saltos[saltos.length - 1].trim();
            }
        }
        return req.getRemoteAddr();
    }
}
