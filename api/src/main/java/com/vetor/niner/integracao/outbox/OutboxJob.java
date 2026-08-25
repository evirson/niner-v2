package com.vetor.niner.integracao.outbox;

import com.vetor.niner.comum.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * O agendador do outbox (P2). <b>Só agenda</b> — o trabalho transacional é do
 * {@link OutboxProcessador}, em outro bean.
 *
 * <p>⛔ <b>Não juntar os dois.</b> {@code @Transactional} não vale em auto-invocação: se o método
 * transacional fosse chamado daqui de dentro do mesmo bean, rodaria sem transação, o
 * {@code FOR UPDATE SKIP LOCKED} soltaria o lock no fim do {@code SELECT} e dois workers pegariam
 * o mesmo evento. Ver o javadoc do processador.
 *
 * <h2>⚠️ O laço por tenant não é detalhe de organização</h2>
 *
 * {@code outbox_evento} tem RLS (V024). Uma consulta à fila <b>sem</b> {@code TenantContext}
 * devolve <b>zero linha, sem erro</b> — o job rodaria de minuto em minuto para sempre, com log
 * limpo, sem nunca despachar nada. Foi assim que os jobs de fiscal ficaram inertes até
 * 2026-08-19. Por isso: listar tenants em {@code plataforma.tenant} (que não tem RLS) e entrar em
 * {@code comTenant} antes de qualquer consulta de domínio.
 */
@Component
public class OutboxJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxJob.class);

    private final OutboxRepositorio repositorio;
    private final OutboxProcessador processador;

    public OutboxJob(OutboxRepositorio repositorio, OutboxProcessador processador) {
        this.repositorio = repositorio;
        this.processador = processador;
    }

    /**
     * A cada 30 segundos.
     *
     * <p>O critério de aceitação do R3 é <i>"estoque alterado no ERP reflete no anúncio em ≤ 2
     * min"</i>. Meio minuto dá folga de sobra para uma rodada que enfrente retentativa, e é
     * barato: sem evento pendente, a rodada é um {@code SELECT} que não casa nada por tenant.
     *
     * <p>{@code initialDelay} de 45 s deixa a aplicação subir e o pool de conexões estabilizar
     * antes de a primeira rodada competir por conexão.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    public void despachar() {
        for (Long idTenant : repositorio.listarTenantIds()) {
            try {
                TenantContext.comTenant(idTenant, () -> {
                    int tratados = processador.processarLoteDoTenantCorrente();
                    if (tratados > 0) {
                        log.debug("Outbox: {} evento(s) tratado(s) no tenant {}.", tratados, idTenant);
                    }
                });
            } catch (RuntimeException e) {
                // Um tenant com problema não pode parar a fila dos outros. O erro do evento em si
                // já foi gravado na linha pelo processador; o que chega aqui é falha da rodada
                // (conexão, por exemplo), e a próxima rodada tenta de novo em 30 s.
                log.error("Outbox: rodada do tenant {} falhou: {}", idTenant, e.toString());
            }
        }
    }
}
