package com.vetor.niner.integracao.mercadolivre;

import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.integracao.outbox.OutboxRepositorio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Renovação automática do {@code access_token} do Mercado Livre.
 *
 * <h2>⚠️ Por que isto não é opcional</h2>
 *
 * O token do ML dura <b>6 horas</b> ({@code expires_in: 21600}). Sem renovação automática, o
 * lojista teria de autorizar a aplicação <b>quatro vezes por dia</b> — e é para isso que a
 * aplicação foi registrada com o fluxo <i>Refresh Token</i> no DevCenter (que é como o
 * {@code offline_access} aparece naquele formulário).
 *
 * <h2>⛔ Agendador aqui, trabalho transacional em outro bean</h2>
 *
 * {@code @Transactional} não vale em auto-invocação: um método anotado chamado de dentro do
 * próprio bean não passa pelo proxy do Spring e roda <b>sem transação</b> — e sem transação não há
 * {@code SET LOCAL app.id_tenant}, logo o RLS esconde tudo <b>em silêncio</b>. Mesmo desenho de
 * {@code CobrancaWebhookJob} × {@code CobrancaWebhookProcessador} e de {@code OutboxJob}.
 *
 * <h2>⚠️ E o laço por tenant também não é organização</h2>
 *
 * {@code canal} tem RLS. Uma consulta sem {@code TenantContext} devolve <b>zero linha, sem
 * erro</b>: o job rodaria para sempre com log limpo, sem renovar nada, e o defeito só apareceria
 * quando os tokens de todo mundo vencessem. Foi assim que os jobs do fiscal ficaram inertes até
 * 2026-08-19.
 */
@Component
public class MercadoLivreTokenJob {

    private static final Logger log = LoggerFactory.getLogger(MercadoLivreTokenJob.class);

    private final OutboxRepositorio tenants;
    private final MercadoLivreTokenRenovador renovador;

    public MercadoLivreTokenJob(OutboxRepositorio tenants, MercadoLivreTokenRenovador renovador) {
        this.tenants = tenants;
        this.renovador = renovador;
    }

    /**
     * A cada 10 minutos.
     *
     * <p>A folga de renovação é de 1 hora ({@link MercadoLivreTokenRenovador#FOLGA}) sobre um
     * token de 6 h, então dez minutos dão <b>seis</b> oportunidades de renovar antes de o token
     * vencer de fato. Sobra para atravessar uma indisponibilidade do ML sem o lojista notar — que
     * é o ponto: renovação que só tenta uma vez vira reautorização manual no primeiro soluço.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 90_000)
    public void renovarTokens() {
        for (Long idTenant : tenants.listarTenantIds()) {
            try {
                TenantContext.comTenant(idTenant, () -> {
                    int renovados = renovador.renovarDoTenantCorrente();
                    if (renovados > 0) {
                        log.info("Mercado Livre: {} token(s) renovado(s) no tenant {}.", renovados, idTenant);
                    }
                });
            } catch (RuntimeException e) {
                // Um tenant com problema não pode parar a renovação dos outros — a próxima rodada
                // tenta de novo em 10 min, e a folga de 1 h dá margem de sobra para isso.
                log.error("Mercado Livre: renovação do tenant {} falhou: {}", idTenant, e.toString());
            }
        }
    }
}
