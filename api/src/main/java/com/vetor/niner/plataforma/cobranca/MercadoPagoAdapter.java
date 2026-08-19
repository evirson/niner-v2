package com.vetor.niner.plataforma.cobranca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.plataforma.configuracao.ConfiguracaoPlataformaDtos.CredenciaisGateway;
import com.vetor.niner.plataforma.configuracao.ConfiguracaoPlataformaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Mercado Pago (ADR-016) sobre a <b>Payments API v1</b>, falada com o {@code HttpClient} do JDK —
 * mesma escolha do transporte da SEFAZ ({@code fiscal.sefaz.SefazTransporte}): sem SDK, sem
 * dependência nova, e o que muda entre sandbox e produção é o <b>token</b>, não o código.
 *
 * <p><b>De onde vem a credencial:</b> do banco quando o backoffice a tiver configurado
 * ({@code plataforma.configuracao_plataforma}, cifrada), com o {@code application.yml}/env como
 * alternativa — é o caminho de dev, CI e da primeira subida, antes de existir staff para
 * configurar. Token vazio nos dois ⇒ {@link #configurado()} falso e a cobrança responde 503: a
 * API sobe normalmente e o resto do ERP não sabe que existe gateway.
 *
 * <p>A credencial é lida <b>a cada chamada</b>, de propósito: trocar o token pelo backoffice tem
 * efeito imediato, sem reiniciar a API — que é justamente o motivo de ela ter saído do arquivo.
 *
 * <p><b>Idempotência (P2):</b> toda criação de cobrança manda {@code X-Idempotency-Key}; repetir
 * a chave devolve a mesma cobrança em vez de criar a segunda.
 *
 * <p>⚠️ As respostas são lidas por caminho de campo, com tolerância a ausência: um campo novo do
 * gateway não pode derrubar a cobrança, e um campo que sumiu vira erro explícito na hora, não
 * {@code null} escondido três camadas adiante.
 */
@Component
public class MercadoPagoAdapter implements GatewayCobranca {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoAdapter.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    public static final String NOME = "mercadopago";

    private final NinerProperties.MercadoPago cfg;
    private final ConfiguracaoPlataformaService configuracao;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public MercadoPagoAdapter(NinerProperties props, ConfiguracaoPlataformaService configuracao) {
        this.cfg = props.cobranca().mercadopago();
        this.configuracao = configuracao;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    private CredenciaisGateway credenciais() {
        return configuracao.credenciaisGateway();
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public boolean configurado() {
        String token = credenciais().accessToken();
        return token != null && !token.isBlank();
    }

    @Override
    public CobrancaPix criarPix(String referencia, BigDecimal valor, String descricao, String emailPagador,
            String chaveIdempotencia) {
        CredenciaisGateway credencial = exigirConfigurado();
        // Config parcial não pode explodir no meio de uma cobrança — 24h é o default do produto.
        OffsetDateTime expiraEm = OffsetDateTime.now()
                .plus(cfg.validadePix() != null ? cfg.validadePix() : Duration.ofHours(24));

        ObjectNode corpo = json.createObjectNode();
        corpo.put("transaction_amount", valor);
        corpo.put("description", descricao);
        corpo.put("payment_method_id", "pix");
        corpo.put("external_reference", referencia);
        corpo.put("date_of_expiration", expiraEm.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")));
        if (credencial.notificationUrl() != null && !credencial.notificationUrl().isBlank()) {
            corpo.put("notification_url", credencial.notificationUrl());
        }
        corpo.putObject("payer").put("email", emailPagador);

        JsonNode resposta = chamar(credencial, "POST", "/v1/payments", corpo.toString(), chaveIdempotencia);

        JsonNode dados = resposta.path("point_of_interaction").path("transaction_data");
        String copiaECola = texto(dados, "qr_code");
        if (copiaECola == null) {
            // Sem o payload PIX não há o que mostrar na tela — falhar aqui é melhor que gravar
            // uma fatura "cobrada" que o lojista não consegue pagar.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "O gateway não devolveu o código PIX. Tente novamente em instantes.");
        }
        return new CobrancaPix(
                resposta.path("id").asText(), copiaECola, texto(dados, "qr_code_base64"),
                texto(dados, "ticket_url"), expiraEm);
    }

    @Override
    public SituacaoPagamento consultarPagamento(String idTransacao) {
        CredenciaisGateway credencial = exigirConfigurado();
        JsonNode r = chamar(credencial, "GET", "/v1/payments/" + idTransacao, null, null);
        return new SituacaoPagamento(
                r.path("id").asText(), traduzir(r.path("status").asText()),
                r.path("transaction_amount").decimalValue(), texto(r, "external_reference"));
    }

    /**
     * Vocabulário do Mercado Pago → o do domínio. {@code in_process}/{@code in_mediation} contam
     * como pendente de propósito: não são falha, e tratar como falha cancelaria a fatura de quem
     * está com o pagamento em análise.
     */
    private static Situacao traduzir(String status) {
        return switch (status) {
            case "approved", "authorized" -> Situacao.CONFIRMADO;
            case "refunded", "charged_back" -> Situacao.ESTORNADO;
            case "rejected", "cancelled" -> Situacao.FALHOU;
            default -> Situacao.PENDENTE;
        };
    }

    private JsonNode chamar(CredenciaisGateway credencial, String metodo, String caminho, String corpo,
            String chaveIdempotencia) {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(cfg.baseUrl() + caminho))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + credencial.accessToken())
                .header("Content-Type", "application/json");
        if (chaveIdempotencia != null) {
            req.header("X-Idempotency-Key", chaveIdempotencia);
        }
        req.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(corpo));

        HttpResponse<String> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Cobrança interrompida.");
        } catch (Exception e) {
            log.warn("Falha de comunicação com o Mercado Pago em {} {}", metodo, caminho, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Não foi possível falar com o meio de pagamento agora. Tente novamente.");
        }
        if (resp.statusCode() >= 300) {
            // Corpo do erro entra no log (tem o motivo real do MP), nunca na resposta ao cliente.
            log.warn("Mercado Pago respondeu {} em {} {}: {}", resp.statusCode(), metodo, caminho, resp.body());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "O meio de pagamento recusou a operação (HTTP " + resp.statusCode() + ").");
        }
        try {
            return json.readTree(resp.body());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Resposta ilegível do meio de pagamento.");
        }
    }

    private CredenciaisGateway exigirConfigurado() {
        CredenciaisGateway credencial = credenciais();
        if (credencial.accessToken() == null || credencial.accessToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cobrança online ainda não está configurada nesta instalação.");
        }
        return credencial;
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.path(campo);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }
}
