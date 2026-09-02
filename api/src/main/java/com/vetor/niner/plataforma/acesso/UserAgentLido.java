package com.vetor.niner.plataforma.acesso;

import java.util.Locale;

/**
 * O que dá para ler de um {@code User-Agent} — sistema operacional, navegador e tipo de aparelho.
 *
 * <p><b>Deliberadamente conservador</b>: classifica o que reconhece e devolve {@code DESCONHECIDO}
 * no resto, em vez de adivinhar. O {@code user_agent} <b>bruto</b> fica gravado ao lado na mesma
 * linha, então um caso novo se reprocessa depois — e um palpite errado gravado como fato, não.
 *
 * <h2>⛔ O que este parser NÃO tenta, e não é limitação de implementação</h2>
 *
 * <p><b>Marca e modelo do aparelho não existem mais no User-Agent.</b> A <i>User-Agent Reduction</i>
 * fez o Chrome no Android mandar {@code Android 10; K} — o modelo real virou um <b>"K" fixo</b> — e
 * o iPhone sempre mandou apenas {@code iPhone}, nunca o modelo. Safari e Firefox não implementam
 * Client Hints, então nem {@code Sec-CH-UA-Model} cobriria o parque. Foi medido em 2026-09-01 e é a
 * razão de o dono do produto ter tirado a marca do escopo.
 *
 * <p>⚠️ <b>A ordem dos testes importa e não é alfabética.</b> Todo navegador mente no User-Agent
 * por compatibilidade histórica: o Edge diz {@code Chrome} e {@code Safari}, o Chrome diz
 * {@code Safari}, e quase todos começam com {@code Mozilla/5.0}. Por isso o mais específico é
 * testado primeiro — inverter a ordem classifica Edge como Chrome, em silêncio.
 */
public record UserAgentLido(String so, String navegador, String dispositivo) {

    public static final String DESCONHECIDO = "DESCONHECIDO";
    public static final String COMPUTADOR = "COMPUTADOR";
    public static final String CELULAR = "CELULAR";
    public static final String TABLET = "TABLET";

    private static final UserAgentLido VAZIO =
            new UserAgentLido(null, null, DESCONHECIDO);

    public static UserAgentLido de(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return VAZIO;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        return new UserAgentLido(sistemaOperacional(ua), navegador(ua), dispositivo(ua));
    }

    private static String sistemaOperacional(String ua) {
        // ⚠️ "android" antes de "linux": todo Android É Linux e diz isso no User-Agent. Na ordem
        // inversa, todo celular Android viraria "Linux" — e o tipo de aparelho ficaria sozinho
        // dizendo a verdade.
        if (ua.contains("android")) return "Android";
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) return "iOS";
        if (ua.contains("windows")) return "Windows";
        // "mac os x" antes de qualquer coisa de iOS: o iPad em modo desktop se apresenta como Mac.
        if (ua.contains("mac os x") || ua.contains("macintosh")) return "macOS";
        if (ua.contains("cros")) return "ChromeOS";
        if (ua.contains("linux") || ua.contains("x11")) return "Linux";
        return null;
    }

    private static String navegador(String ua) {
        // Do mais específico para o mais genérico — ver o javadoc da classe.
        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/")) return "Edge";
        if (ua.contains("opr/") || ua.contains("opera")) return "Opera";
        if (ua.contains("samsungbrowser")) return "Samsung Internet";
        if (ua.contains("firefox") || ua.contains("fxios")) return "Firefox";
        // "crios" é o Chrome no iOS, que por regra da Apple roda sobre o WebKit e mesmo assim se
        // identifica como Chrome.
        if (ua.contains("chrome") || ua.contains("crios")) return "Chrome";
        // Safari por último: Chrome, Edge e Opera todos carregam "safari" na string.
        if (ua.contains("safari")) return "Safari";
        return null;
    }

    private static String dispositivo(String ua) {
        // ⚠️ Tablet antes de celular, e "ipad" antes de "mobile": o iPad manda "Mobile" na string,
        // e testar celular primeiro classificaria todo tablet como telefone.
        if (ua.contains("ipad") || ua.contains("tablet")
                // Android tablet não diz "tablet": ele diz "Android" SEM "Mobile". É a única
                // pista que existe, e é assim que os detectores sérios fazem.
                || (ua.contains("android") && !ua.contains("mobile"))) {
            return TABLET;
        }
        if (ua.contains("mobile") || ua.contains("iphone") || ua.contains("ipod")
                || ua.contains("android")) {
            return CELULAR;
        }
        if (ua.contains("windows") || ua.contains("macintosh") || ua.contains("mac os x")
                || ua.contains("x11") || ua.contains("linux") || ua.contains("cros")) {
            return COMPUTADOR;
        }
        return DESCONHECIDO;
    }
}
