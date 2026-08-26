package com.vetor.niner.integracao.mercadolivre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vetor.niner.canais.CanalDeVenda.CanalIndisponivelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transporte do endpoint de <b>token</b> do Mercado Livre ({@code POST /oauth/token}).
 *
 * <h2>⚠️ Por que não reusa {@link MercadoLivreApi}</h2>
 *
 * Aquela classe carimba {@code Authorization: Bearer} em toda requisição e fala JSON. Este
 * endpoint é o oposto dos dois: ele é <b>não autenticado</b> (é ele que <i>emite</i> o token) e
 * recebe o corpo em {@code application/x-www-form-urlencoded}. Forçar um dentro do outro
 * produziria um cliente com dois modos e um monte de {@code if} — a fronteira certa é esta.
 *
 * <h2>⚠️ Transitório × definitivo, de novo</h2>
 *
 * Mesma regra do resto da integração, com uma diferença que importa aqui: {@code 400
 * invalid_grant} é <b>definitivo</b> (código já usado, expirado, ou autorização revogada pelo
 * lojista) e insistir nele nunca funciona — quem resolve é o lojista, autorizando de novo. Rede,
 * {@code 429} e {@code 5xx} são transitórios e merecem outra tentativa.
 */
public class MercadoLivreOAuth {

    private static final Logger log = LoggerFactory.getLogger(MercadoLivreOAuth.class);

    /** ⚠️ O host da API — <b>não</b> é o do consentimento ({@code auth.mercadolivre.com.br}). */
    public static final String BASE_PRODUCAO = "https://api.mercadolibre.com";

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public MercadoLivreOAuth(String baseUrl, String clientId, String clientSecret,
                             String redirectUri, ObjectMapper json) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * O que o ML devolve ao emitir um token.
     *
     * @param contaExterna {@code user_id} do vendedor — é o que amarra uma notificação futura ao
     *                     canal certo
     * @param expiraEm     instante calculado a partir de {@code expires_in} (⚠️ <b>6 horas</b> no
     *                     ML; é por isso que a renovação automática é obrigatória, não conforto)
     */
    public record Tokens(String contaExterna, String accessToken, String refreshToken,
                         Instant expiraEm) {

        /** ⚠️ Nunca imprime o token — a mesma regra de {@code CredenciaisCanal}. */
        @Override
        public String toString() {
            return "Tokens[contaExterna=%s, accessToken=***, refreshToken=%s, expiraEm=%s]"
                    .formatted(contaExterna, refreshToken == null ? "ausente" : "***", expiraEm);
        }
    }

    /** Autorização recusada de forma <b>definitiva</b> — insistir não resolve, o lojista reautoriza. */
    public static class AutorizacaoInvalidaException extends RuntimeException {
        public AutorizacaoInvalidaException(String mensagem) {
            super(mensagem);
        }
    }

    /** Troca o {@code code} do consentimento pelo primeiro par de tokens. */
    public Tokens trocarCodigo(String code) {
        var campos = new LinkedHashMap<String, String>();
        campos.put("grant_type", "authorization_code");
        campos.put("client_id", clientId);
        campos.put("client_secret", clientSecret);
        campos.put("code", code);
        // ⚠️ O ML exige a MESMA redirect_uri usada no consentimento, mesmo não redirecionando
        // nada aqui: ela faz parte da prova de que o `code` é nosso.
        campos.put("redirect_uri", redirectUri);
        return pedirToken(campos);
    }

    /** Renova o {@code access_token} de 6 h sem incomodar o lojista (fluxo Refresh Token). */
    public Tokens renovar(String refreshToken) {
        var campos = new LinkedHashMap<String, String>();
        campos.put("grant_type", "refresh_token");
        campos.put("client_id", clientId);
        campos.put("client_secret", clientSecret);
        campos.put("refresh_token", refreshToken);
        return pedirToken(campos);
    }

    private Tokens pedirToken(Map<String, String> campos) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/oauth/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formulario(campos)))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new CanalIndisponivelException("Falha de rede ao pedir token ao Mercado Livre", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CanalIndisponivelException("Pedido de token ao Mercado Livre interrompido", e);
        }

        int status = resp.statusCode();
        if (status == 429 || status >= 500) {
            throw new CanalIndisponivelException(
                    "Mercado Livre indisponível ao emitir token (HTTP %d)".formatted(status));
        }
        if (status >= 400) {
            // ⚠️ O corpo traz `error` e `message`, e nenhum dos dois é segredo — mas a REQUISIÇÃO
            // trazia o client_secret, então o que se registra é a resposta, jamais o que saiu.
            log.warn("Mercado Livre recusou a emissão de token com HTTP {}: {}", status, resp.body());
            throw new AutorizacaoInvalidaException(
                    "Mercado Livre recusou a autorização (HTTP %d): %s"
                            .formatted(status, descricaoDoErro(resp.body())));
        }

        try {
            JsonNode no = json.readTree(resp.body());
            String accessToken = texto(no, "access_token");
            if (accessToken == null) {
                throw new AutorizacaoInvalidaException(
                        "Mercado Livre respondeu sem access_token na emissão de token.");
            }
            long expiraEmSegundos = no.path("expires_in").asLong(0);
            return new Tokens(
                    texto(no, "user_id"),
                    accessToken,
                    texto(no, "refresh_token"),
                    Instant.now().plusSeconds(expiraEmSegundos));
        } catch (AutorizacaoInvalidaException e) {
            throw e;
        } catch (Exception e) {
            throw new AutorizacaoInvalidaException(
                    "Resposta do Mercado Livre à emissão de token não é JSON válido.");
        }
    }

    private static String formulario(Map<String, String> campos) {
        var sb = new StringBuilder();
        campos.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** A mensagem do ML é o que o suporte pesquisa; cortar evita despejar payload inteiro. */
    private static String descricaoDoErro(String corpo) {
        if (corpo == null || corpo.isBlank()) {
            return "(sem corpo)";
        }
        return corpo.length() <= 300 ? corpo : corpo.substring(0, 300) + "…";
    }
}
