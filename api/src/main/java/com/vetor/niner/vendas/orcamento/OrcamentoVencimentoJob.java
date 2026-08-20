package com.vetor.niner.vendas.orcamento;

import com.vetor.niner.comum.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Varre os orçamentos que passaram da validade e os marca {@code VENCIDO} (R6).
 *
 * <h2>Por que existe, se a consulta já vence sozinha</h2>
 *
 * <p>{@code OrcamentoService.buscar} marca o vencimento na hora em que alguém lê o orçamento — mas
 * um orçamento que ninguém mais consulta ficaria "ABERTO" para sempre, poluindo a lista e, pior,
 * mentindo no relatório de conversão (a métrica de sucesso da rotina é justamente <i>quantos
 * viraram venda × quantos morreram esperando o cliente</i>). Os dois juntos, como
 * {@code ArquivoCompartilhadoService} já faz com os PDFs.
 *
 * <h2>⚠️ Dois beans, de propósito</h2>
 *
 * <p>Este só agenda; quem faz o trabalho transacional é {@link OrcamentoVencimentoProcessador}.
 * Método {@code @Transactional} chamado de dentro do próprio bean <b>não passa pelo proxy do
 * Spring e roda sem transação</b> — silencioso e grave. Mesmo par de
 * {@code CobrancaWebhookJob} × {@code CobrancaWebhookProcessador}.
 *
 * <h2>⚠️ Sem tenant no contexto, este job não enxerga NADA — e não dá erro</h2>
 *
 * <p>{@code niner_app} nunca tem {@code BYPASSRLS}: uma consulta de domínio fora de
 * {@link TenantContext#comTenant} devolve <b>zero linhas em silêncio</b>. O job "não acharia nada
 * para vencer" todos os dias, para sempre, sem uma linha de log. Por isso a lista de tenants vem de
 * {@code plataforma.tenant} (global, sem RLS, P9) e cada tenant entra no contexto <b>antes</b> de
 * qualquer consulta — inclusive a de "este tenant tem algo a vencer?".
 */
@Component
public class OrcamentoVencimentoJob {

    private static final Logger log = LoggerFactory.getLogger(OrcamentoVencimentoJob.class);

    private final JdbcClient jdbc;
    private final OrcamentoVencimentoProcessador processador;

    public OrcamentoVencimentoJob(JdbcClient jdbc, OrcamentoVencimentoProcessador processador) {
        this.jdbc = jdbc;
        this.processador = processador;
    }

    /**
     * A cada 30 minutos. Não precisa ser mais fino: quem consulta ou tenta vender um orçamento
     * vencido já o vence na hora — este job é a rede para os que ninguém tocou.
     */
    @Scheduled(fixedRate = 1_800_000, initialDelay = 120_000)
    public void vencerOrcamentos() {
        for (Long idTenant : listarTenantIds()) {
            try {
                TenantContext.comTenant(idTenant, () -> {
                    int vencidos = processador.vencerDoTenant();
                    if (vencidos > 0) {
                        log.info("Orçamentos vencidos automaticamente no tenant {}: {}", idTenant, vencidos);
                    }
                    return null;
                });
            } catch (Exception e) {
                // Um tenant com problema não pode travar os outros.
                log.warn("Falha ao vencer orçamentos do tenant {}: {}", idTenant, e.getMessage());
            }
        }
    }

    /** `plataforma.tenant` é GLOBAL (sem RLS, P9) — é a única leitura que pode acontecer fora do
     *  contexto de tenant. */
    private List<Long> listarTenantIds() {
        return jdbc.sql("SELECT id_tenant FROM plataforma.tenant ORDER BY id_tenant")
                .query(Long.class).list();
    }
}
