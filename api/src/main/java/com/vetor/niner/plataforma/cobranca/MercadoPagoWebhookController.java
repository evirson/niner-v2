package com.vetor.niner.plataforma.cobranca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vetor.niner.plataforma.configuracao.ConfiguracaoPlataformaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Recepção das notificações do Mercado Pago (ADR-016).
 *
 * <p><b>Este endpoint não decide nada.</b> Ele só grava a notificação em
 * {@code plataforma.webhook_gateway} (única por gateway + evento) e responde 200. Quem aplica
 * efeito é {@link CobrancaWebhookJob}, e <b>consultando o gateway</b> — por isso um webhook
 * forjado não consegue marcar fatura como paga, nem promover ninguém de faixa.
 *
 * <p>A validação de assinatura ({@code x-signature}) é defesa em profundidade e só roda quando o
 * segredo está configurado; sem ele (dev) a notificação entra e o worker confere na origem.
 *
 * <p>Responder rápido é requisito do provedor (ele espera 200/201 em segundos) — mais um motivo
 * para o handler não fazer nada além de gravar.
 */
@RestController
@RequestMapping("/api/publico/webhooks")
public class MercadoPagoWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoWebhookController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final ConfiguracaoPlataformaService configuracao;

    public MercadoPagoWebhookController(JdbcClient jdbc, ConfiguracaoPlataformaService configuracao) {
        this.jdbc = jdbc;
        this.configuracao = configuracao;
    }

    @PostMapping("/mercadopago")
    public ResponseEntity<Void> receber(
            @RequestBody(required = false) String corpo,
            @RequestParam(name = "data.id", required = false) String dataIdQuery,
            @RequestParam(required = false) String type,
            @RequestHeader(name = "x-signature", required = false) String assinatura,
            @RequestHeader(name = "x-request-id", required = false) String requestId) {

        JsonNode payload;
        try {
            payload = corpo == null || corpo.isBlank() ? JSON.createObjectNode() : JSON.readTree(corpo);
        } catch (Exception e) {
            log.warn("Webhook do Mercado Pago com corpo ilegível");
            return ResponseEntity.badRequest().build();
        }

        String dataId = dataIdQuery != null ? dataIdQuery : texto(payload.path("data").path("id"));
        String tipo = type != null ? type : texto(payload.path("type"));

        if (!assinaturaValida(assinatura, requestId, dataId)) {
            log.warn("Webhook do Mercado Pago com assinatura inválida (data.id={})", dataId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Idempotência (P2): o id da notificação identifica a ENTREGA; o mesmo pagamento pode
        // notificar várias vezes (created/updated), e cada uma vira uma linha — o worker
        // reconsulta o gateway e converge no mesmo estado.
        String eventoId = texto(payload.path("id"));
        if (eventoId == null) {
            eventoId = (dataId == null ? "sem-id" : dataId) + "-" + texto(payload.path("action"));
        }

        jdbc.sql("""
                        INSERT INTO plataforma.webhook_gateway (gateway, evento_id, tipo, payload)
                        VALUES (?, ?, ?, ?::jsonb)
                        ON CONFLICT (gateway, evento_id) DO NOTHING
                        """)
                .params(MercadoPagoAdapter.NOME, eventoId, tipo, corpo == null ? "{}" : corpo)
                .update();

        return ResponseEntity.ok().build();
    }

    /**
     * {@code x-signature: ts=...,v1=...} — HMAC-SHA256 sobre
     * {@code id:<data.id>;request-id:<x-request-id>;ts:<ts>;} (partes ausentes são omitidas;
     * {@code data.id} alfanumérico entra em minúsculas), com o segredo do webhook.
     *
     * <p>Sem segredo configurado, aceita: em dev não há segredo, e o efeito de negócio depende da
     * consulta ao gateway de qualquer forma.
     */
    private boolean assinaturaValida(String header, String requestId, String dataId) {
        String segredo = configuracao.credenciaisGateway().webhookSecret();
        if (segredo == null || segredo.isBlank()) {
            return true;
        }
        if (header == null || header.isBlank()) {
            return false;
        }
        String ts = null;
        String v1 = null;
        for (String parte : header.split(",")) {
            String[] kv = parte.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("ts".equals(kv[0].trim())) {
                ts = kv[1].trim();
            } else if ("v1".equals(kv[0].trim())) {
                v1 = kv[1].trim();
            }
        }
        if (ts == null || v1 == null) {
            return false;
        }

        StringBuilder manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            manifest.append("id:").append(dataId.toLowerCase(Locale.ROOT)).append(';');
        }
        if (requestId != null && !requestId.isBlank()) {
            manifest.append("request-id:").append(requestId).append(';');
        }
        manifest.append("ts:").append(ts).append(';');

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String calculado = HexFormat.of()
                    .formatHex(mac.doFinal(manifest.toString().getBytes(StandardCharsets.UTF_8)));
            // Comparação em tempo constante — comparar hash com equals() vaza informação por tempo.
            return MessageDigest.isEqual(
                    calculado.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Falha ao validar assinatura do webhook", e);
            return false;
        }
    }

    private static String texto(JsonNode no) {
        return no.isMissingNode() || no.isNull() || no.asText().isBlank() ? null : no.asText();
    }
}
