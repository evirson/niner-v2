package com.vetor.niner.integracao.mercadolivre;

import com.fasterxml.jackson.databind.JsonNode;
import com.vetor.niner.canais.CredenciaisCanalRepositorio;
import com.vetor.niner.canais.CredenciaisCanalRepositorio.DonoDaConta;
import com.vetor.niner.canais.TipoCanal;
import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.integracao.PedidoWebhookRepositorio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recepção das notificações do Mercado Livre (M5).
 *
 * <h2>⛔ Este endpoint não decide nada</h2>
 *
 * Ele grava a notificação em {@code webhook_recebido} e responde <b>200 na hora</b>. Quem aplica
 * efeito é {@code PedidoWebhookJob}, e <b>consultando a API do ML</b> — não o corpo que chegou.
 * Por isso um webhook forjado não consegue criar pedido, mexer em estoque nem consumir cota: o
 * máximo que faz é pedir que o worker consulte um pedido que não existe. Mesmo desenho do webhook
 * do Mercado Pago (ADR-016).
 *
 * <h2>⚠️ Responder 200 mesmo no caso ruim, e por quê</h2>
 *
 * O Mercado Livre <b>reenvia</b> o que falha e <b>desativa</b> o callback que falha repetidamente.
 * Devolver 4xx para uma notificação de vendedor desconhecido — que acontece de verdade: lojista
 * que desconectou, conta de teste antiga, notificação atrasada — treinaria a plataforma a desligar
 * o nosso endereço, e aí <b>nenhum</b> lojista receberia pedido. Responder 200 e registrar é o
 * comportamento certo.
 *
 * <h2>⚠️ E o endereço deste mapeamento é registrado no painel do ML</h2>
 *
 * Renomeá-lo quebra a notificação de todos os lojistas de uma vez, com uma mensagem do ML que não
 * diz que a culpa é nossa. Ver {@code MercadoLivreOAuthController}, que tem a mesma nota.
 */
@RestController
public class MercadoLivreWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MercadoLivreWebhookController.class);

    /** ⚠️ Mapper próprio: esta aplicação não expõe bean de ObjectMapper. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final CredenciaisCanalRepositorio credenciais;
    private final PedidoWebhookRepositorio webhooks;

    public MercadoLivreWebhookController(CredenciaisCanalRepositorio credenciais,
                                         PedidoWebhookRepositorio webhooks) {
        this.credenciais = credenciais;
        this.webhooks = webhooks;
    }

    /**
     * ⚠️ Recebe {@code String} e faz o parse aqui — <b>não</b> {@code JsonNode} no parâmetro. A
     * aplicação converte HTTP com <b>Jackson 3</b> ({@code tools.jackson}), e o {@code JsonNode}
     * que este projeto usa em código é o do <b>Jackson 2</b> ({@code com.fasterxml.jackson}):
     * declará-lo no parâmetro faz o Spring responder 500 com <i>"Cannot construct instance of
     * JsonNode"</i>. Mesmo padrão do webhook do Mercado Pago, que já tinha topado com isso.
     */
    @PostMapping("/api/publico/webhooks/mercadolivre")
    public ResponseEntity<Void> receber(@RequestBody(required = false) String corpoBruto) {
        JsonNode corpo;
        try {
            corpo = corpoBruto == null || corpoBruto.isBlank()
                    ? JSON.createObjectNode()
                    : JSON.readTree(corpoBruto);
        } catch (Exception e) {
            log.warn("Notificação do Mercado Livre com corpo ilegível — ignorada.");
            return ResponseEntity.ok().build();
        }
        if (corpo.isEmpty()) {
            return ResponseEntity.ok().build();
        }

        String vendedor = texto(corpo.path("user_id"));
        String topico = texto(corpo.path("topic"));
        String recurso = texto(corpo.path("resource"));

        if (vendedor == null || recurso == null) {
            log.warn("Notificação do Mercado Livre sem user_id/resource — ignorada: {}", corpo);
            return ResponseEntity.ok().build();
        }

        // ⭐ É aqui que a notificação anônima ganha dono. Consulta global, sem RLS (V068) — a
        // tabela existe exatamente porque `canal` não pode ser lida sem TenantContext.
        DonoDaConta dono = credenciais.donoDaConta(TipoCanal.MERCADO_LIVRE.name(), vendedor).orElse(null);
        if (dono == null) {
            // Ver a nota da classe: 200, sempre. Vendedor desconhecido acontece de verdade.
            log.info("Notificação do Mercado Livre para o vendedor {} sem canal conectado — ignorada.",
                    vendedor);
            return ResponseEntity.ok().build();
        }

        // O ML não manda um id de evento estável em todos os tópicos. A chave de idempotência é o
        // recurso — "o pedido X mudou" duas vezes é a mesma coisa a fazer uma vez só, e o worker
        // busca o estado ATUAL de qualquer forma.
        String chave = topico == null ? recurso : topico + " " + recurso;

        try {
            TenantContext.comTenant(dono.idTenant(),
                    () -> webhooks.registrar(dono.idCanal(), chave, topico, recurso, corpo.toString()));
        } catch (RuntimeException e) {
            // ⚠️ Nem mesmo uma falha nossa vira 4xx/5xx aqui: o ML reenviaria, e reenvio de algo
            // que quebra do nosso lado só multiplica o problema. Fica no log, e o polling de
            // segurança (`PedidoPollingJob`) pega o pedido de qualquer jeito.
            log.error("Falha ao registrar notificação do Mercado Livre ({}): {}", chave, e.toString());
        }
        return ResponseEntity.ok().build();
    }

    private static String texto(JsonNode no) {
        return no.isMissingNode() || no.isNull() || no.asText().isBlank() ? null : no.asText();
    }
}
