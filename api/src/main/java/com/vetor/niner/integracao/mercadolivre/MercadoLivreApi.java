package com.vetor.niner.integracao.mercadolivre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vetor.niner.canais.CanalDeVenda.CanalIndisponivelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Transporte HTTP com a API do Mercado Livre — a camada mais baixa do adapter, equivalente ao que
 * {@code SefazTransporte} é para o módulo fiscal.
 *
 * <p>Mesma escolha do fiscal: {@code HttpClient} do JDK, sem lib de terceiro. O que esta classe
 * acrescenta é a <b>tradução de falha</b>: o que é transitório (rede, 429, 5xx) vira
 * {@link CanalIndisponivelException} para o outbox reagendar; o que é definitivo (4xx de negócio)
 * vira {@link RespostaDeErroException}, que precisa de olho humano em vez de girar para sempre.
 *
 * <h2>⚠️ Distinguir os dois não é preciosismo</h2>
 *
 * Um erro definitivo tratado como transitório fica reprocessando até o dead-letter, escondendo a
 * causa atrás de dezenas de tentativas idênticas. Um transitório tratado como definitivo joga no
 * dead-letter um anúncio que só precisava esperar 30 segundos — e alguém tem que reprocessar à mão.
 */
public class MercadoLivreApi {

    private static final Logger log = LoggerFactory.getLogger(MercadoLivreApi.class);

    /** Base oficial. Injetável porque o teste aponta para o WireMock — não há sandbox no ML. */
    public static final String BASE_PRODUCAO = "https://api.mercadolibre.com";

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;

    public MercadoLivreApi(String baseUrl, ObjectMapper json) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Erro que o canal considera definitivo — não adianta repetir sem alguém olhar. */
    public static class RespostaDeErroException extends RuntimeException {
        private final int status;

        public RespostaDeErroException(int status, String mensagem) {
            super(mensagem);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public JsonNode get(String caminho, String accessToken) {
        return enviar(requisicao(caminho, accessToken).GET().build(), caminho);
    }

    public JsonNode put(String caminho, String accessToken, String corpoJson) {
        return enviar(requisicao(caminho, accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(corpoJson))
                .build(), caminho);
    }

    private HttpRequest.Builder requisicao(String caminho, String accessToken) {
        return HttpRequest.newBuilder(URI.create(baseUrl + caminho))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                // Formato novo de `orders` (2.5 do estudo). Inofensivo nos outros recursos, e
                // mandar sempre evita o caso em que alguém esquece justamente onde importa.
                .header("x-format-new", "true");
    }

    private JsonNode enviar(HttpRequest req, String caminho) {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new CanalIndisponivelException("Falha de rede ao falar com o Mercado Livre: " + caminho, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CanalIndisponivelException("Chamada ao Mercado Livre interrompida: " + caminho, e);
        }

        int status = resp.statusCode();

        // ⚠️ 429 vem com CORPO VAZIO (limite de 1.500/min por vendedor). Tentar interpretar o
        // corpo aqui daria erro de parse mascarando um simples "espere um pouco".
        if (status == 429) {
            throw new CanalIndisponivelException(
                    "Mercado Livre recusou por limite de requisições (429) em " + caminho);
        }
        if (status >= 500) {
            throw new CanalIndisponivelException(
                    "Mercado Livre indisponível (HTTP %d) em %s".formatted(status, caminho));
        }
        if (status == 401 || status == 403) {
            // Credencial: pode ser token vencido (transitório, o refresh resolve) ou autorização
            // revogada pelo lojista (definitivo). Quem sabe distinguir é o serviço de credenciais,
            // que tenta o refresh; por isso sobe como erro definitivo COM o status preservado.
            throw new RespostaDeErroException(status,
                    "Mercado Livre recusou a credencial (HTTP %d) em %s".formatted(status, caminho));
        }
        if (status >= 400) {
            log.warn("Mercado Livre recusou {} com HTTP {}: {}", caminho, status, resp.body());
            throw new RespostaDeErroException(status,
                    "Mercado Livre recusou a operação (HTTP %d): %s".formatted(status, resumo(resp.body())));
        }

        try {
            return resp.body() == null || resp.body().isBlank()
                    ? json.createObjectNode()
                    : json.readTree(resp.body());
        } catch (Exception e) {
            throw new RespostaDeErroException(status,
                    "Resposta do Mercado Livre não é JSON válido em " + caminho);
        }
    }

    /** Mensagem de erro do canal entra em log e em tela; cortar evita despejar payload inteiro. */
    private static String resumo(String corpo) {
        if (corpo == null || corpo.isBlank()) {
            return "(sem corpo)";
        }
        return corpo.length() <= 300 ? corpo : corpo.substring(0, 300) + "…";
    }
}
