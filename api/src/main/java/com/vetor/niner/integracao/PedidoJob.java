package com.vetor.niner.integracao;

import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.integracao.outbox.OutboxRepositorio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Os dois agendadores de pedido (M5). <b>Só agendam</b> — o trabalho transacional vive em
 * {@link PedidoWebhookProcessador} e {@link PedidoPollingProcessador}.
 *
 * <h2>⚠️ O laço por tenant não é organização</h2>
 *
 * {@code webhook_recebido} e {@code canal} têm RLS. Uma consulta sem {@code TenantContext} devolve
 * <b>zero linha, sem erro</b> — o job rodaria para sempre com log limpo, sem importar pedido
 * nenhum, e ninguém perceberia até um lojista reclamar que a venda do marketplace não apareceu.
 * Foi assim que os jobs do fiscal ficaram inertes até 2026-08-19.
 */
@Component
public class PedidoJob {

    private static final Logger log = LoggerFactory.getLogger(PedidoJob.class);

    private final OutboxRepositorio tenants;
    private final PedidoWebhookProcessador webhooks;
    private final PedidoPollingProcessador polling;

    public PedidoJob(OutboxRepositorio tenants, PedidoWebhookProcessador webhooks,
                     PedidoPollingProcessador polling) {
        this.tenants = tenants;
        this.webhooks = webhooks;
        this.polling = polling;
    }

    /**
     * Notificações recebidas: a cada 20 segundos.
     *
     * <p>É o caminho <b>rápido</b> — o lojista espera ver a venda do marketplace aparecer quase na
     * hora. Sem notificação pendente, a rodada é um {@code SELECT} que não casa nada por tenant.
     */
    @Scheduled(fixedDelay = 20_000, initialDelay = 60_000)
    public void processarNotificacoes() {
        for (Long idTenant : tenants.listarTenantIds()) {
            try {
                TenantContext.comTenant(idTenant, () -> {
                    int tratadas = webhooks.processarLoteDoTenantCorrente();
                    if (tratadas > 0) {
                        log.debug("Pedidos: {} notificação(ões) tratada(s) no tenant {}.", tratadas, idTenant);
                    }
                });
            } catch (RuntimeException e) {
                log.error("Pedidos: rodada de notificações do tenant {} falhou: {}", idTenant, e.toString());
            }
        }
    }

    /**
     * ⭐ Polling de segurança: a cada 15 minutos.
     *
     * <p><b>Não é redundância — é a rede que segura o caso em que o webhook não existe.</b> Três
     * situações reais: (a) em desenvolvimento não há URL pública, então notificação nenhuma chega;
     * (b) os três tópicos ficaram <b>desmarcados</b> no painel do ML até este bloco existir; (c)
     * plataforma desativa callback que falha repetidamente — e o dia em que isso acontecer é
     * justamente o dia em que ninguém está olhando.
     *
     * <p>Quinze minutos é o intervalo que a spec previu (§Fase 2). Um pedido que demora quinze
     * minutos para aparecer é um aborrecimento; um pedido que <b>nunca</b> aparece é uma venda
     * perdida e uma reclamação no marketplace.
     */
    @Scheduled(fixedDelay = 900_000, initialDelay = 120_000)
    public void buscarPedidosRecentes() {
        for (Long idTenant : tenants.listarTenantIds()) {
            try {
                TenantContext.comTenant(idTenant, () -> {
                    int importados = polling.varrerTenantCorrente();
                    if (importados > 0) {
                        log.info("Pedidos: {} importado(s) pelo polling no tenant {}.", importados, idTenant);
                    }
                });
            } catch (RuntimeException e) {
                log.error("Pedidos: polling do tenant {} falhou: {}", idTenant, e.toString());
            }
        }
    }
}
