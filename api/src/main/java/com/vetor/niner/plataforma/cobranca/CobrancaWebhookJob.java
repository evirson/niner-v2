package com.vetor.niner.plataforma.cobranca;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agendador do consumo de notificações de cobrança (ADR-016). Só orquestra: o trabalho
 * transacional está em {@link CobrancaWebhookProcessador}, em outro bean de propósito — o proxy
 * do Spring não intercepta auto-invocação, então {@code @Transactional} chamado de dentro do
 * mesmo bean não abriria transação nenhuma (e o {@code FOR UPDATE SKIP LOCKED} perderia o lock).
 */
@Component
public class CobrancaWebhookJob {

    private final CobrancaWebhookProcessador processador;
    private final GatewayCobranca gateway;

    public CobrancaWebhookJob(CobrancaWebhookProcessador processador, GatewayCobranca gateway) {
        this.processador = processador;
        this.gateway = gateway;
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 15_000)
    public void processarPendentes() {
        if (!gateway.configurado()) {
            return;                                    // sem credencial não há o que consultar
        }
        for (Long id : processador.pegarLote()) {
            try {
                processador.processar(id);
            } catch (RuntimeException e) {
                processador.adiar(id, e.getMessage());
            }
        }
    }
}
