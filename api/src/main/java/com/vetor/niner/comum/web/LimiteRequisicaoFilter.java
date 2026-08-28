package com.vetor.niner.comum.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limite de requisições da superfície <b>pública</b> ({@code /api/publico/**}) — a única que
 * aceita escrita anônima: cadastro, formulário de lead e o beacon de medição, que ainda por cima
 * recebe <b>lote</b>. Sem limite, um robô enche o banco de visitas e leads em minutos, e o custo
 * disso cai no disco do VPS.
 *
 * <p><b>Em memória, de propósito</b> (P6: sem Redis, sem broker). Com uma instância de API isso
 * basta; quando houver duas atrás do mesmo proxy, o limite passa a ser por instância — aí o lugar
 * certo do controle é o proxy (`limit_req` do nginx), e este filtro vira a segunda camada.
 *
 * <p>Dois baldes, porque os riscos são diferentes: <b>escrita de cadastro</b> (signup/lead) é cara
 * e rara — limite baixo; <b>beacon</b> é barato e frequente — limite alto. Um limite único ou
 * estrangularia a medição ou deixaria o cadastro exposto.
 *
 * <p>Atrás de proxy, o IP real vem em {@code X-Forwarded-For}. Ele só é considerado quando
 * {@code niner.limite-requisicao.confiar-proxy=true} — do contrário qualquer cliente forjaria o
 * cabeçalho e furaria o limite trocando o valor a cada chamada.
 */
@Component
public class LimiteRequisicaoFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LimiteRequisicaoFilter.class);
    private static final Duration JANELA = Duration.ofMinutes(1);
    /** Acima disto, a limpeza roda antes de aceitar mais IPs (o mapa não pode crescer sem teto). */
    private static final int TETO_DE_CHAVES = 50_000;

    private final Map<String, Contador> contadores = new ConcurrentHashMap<>();
    private final boolean habilitado;
    private final boolean confiarProxy;
    private final int limiteEscrita;
    private final int limiteBeacon;

    public LimiteRequisicaoFilter(
            @Value("${niner.limite-requisicao.habilitado:true}") boolean habilitado,
            @Value("${niner.limite-requisicao.confiar-proxy:false}") boolean confiarProxy,
            @Value("${niner.limite-requisicao.escrita-por-minuto:10}") int limiteEscrita,
            @Value("${niner.limite-requisicao.beacon-por-minuto:120}") int limiteBeacon) {
        this.habilitado = habilitado;
        this.confiarProxy = confiarProxy;
        this.limiteEscrita = limiteEscrita;
        this.limiteBeacon = limiteBeacon;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !habilitado || !request.getRequestURI().startsWith("/api/publico/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        boolean beacon = req.getRequestURI().startsWith("/api/publico/eventos");
        // GET público (catálogo de planos) é leitura barata e cacheável: fora do limite.
        if (!beacon && !"POST".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, resp);
            return;
        }
        // Webhook de gateway não entra no limite: quem chama é o Mercado Pago, e recusar
        // notificação por excesso significaria perder confirmação de pagamento.
        if (req.getRequestURI().startsWith("/api/publico/webhooks/")) {
            chain.doFilter(req, resp);
            return;
        }

        int limite = beacon ? limiteBeacon : limiteEscrita;
        String chave = (beacon ? "b:" : "e:") + ipDe(req);

        if (excedeu(chave, limite)) {
            log.debug("Limite de requisições atingido para {} em {}", chave, req.getRequestURI());
            resp.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            resp.setHeader("Retry-After", Long.toString(JANELA.toSeconds()));
            // charset explícito: sem ele o Tomcat usa ISO-8859-1 e o "ç"/"õ" de "Muitas
            // requisições" chega quebrado — o response.json() do navegador decodifica sempre
            // como UTF-8 e o usuário lê "Muitas requisies" (visto em produção, 2026-08-19).
            resp.setContentType("application/problem+json");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write("""
                    {"type":"urn:niner:erro:limite-de-requisicoes","title":"Muitas requisições",\
                    "status":429,"detail":"Aguarde um instante e tente de novo."}""");
            return;
        }
        chain.doFilter(req, resp);
    }

    private boolean excedeu(String chave, int limite) {
        Instant agora = Instant.now();
        if (contadores.size() > TETO_DE_CHAVES) {
            contadores.entrySet().removeIf(e -> e.getValue().expirou(agora));
        }
        Contador c = contadores.compute(chave, (k, atual) ->
                atual == null || atual.expirou(agora) ? new Contador(agora.plus(JANELA)) : atual);
        return c.registrar() > limite;
    }

    /**
     * IP do cliente, atrás do proxy.
     *
     * <p>⛔ <b>Nunca o primeiro elemento de {@code X-Forwarded-For}</b> (achado da auditoria de
     * segurança, 2026-08-27). O nginx usa {@code proxy_add_x_forwarded_for}, que <b>acrescenta</b> o
     * IP real ao <b>fim</b> da lista e preserva o que o cliente mandou no começo. Lendo o primeiro,
     * qualquer um passava {@code X-Forwarded-For: 10.0.0.<aleatório>} a cada requisição, criava um
     * balde novo no mapa e <b>o limite deixava de existir</b> — justamente em produção, onde
     * {@code confiar-proxy} está ligado. O alvo pior era o código de 4 dígitos do login em duas
     * etapas.
     *
     * <p>⭐ Hoje o valor vem de {@code X-Real-IP}, que o nginx <b>sobrescreve</b> com
     * {@code $remote_addr} (`nainer.conf`) — o cliente não tem como forjá-lo. O último elemento do
     * {@code X-Forwarded-For} serve de reserva para um proxy que não mande {@code X-Real-IP}: é o
     * salto mais próximo, o único que o cliente não escolhe.
     */
    private String ipDe(HttpServletRequest req) {
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

    private record Contador(Instant fim, AtomicInteger chamadas) {
        Contador(Instant fim) {
            this(fim, new AtomicInteger());
        }

        int registrar() {
            return chamadas.incrementAndGet();
        }

        boolean expirou(Instant agora) {
            return agora.isAfter(fim);
        }
    }
}
