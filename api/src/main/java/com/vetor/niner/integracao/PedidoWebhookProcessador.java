package com.vetor.niner.integracao;

import com.vetor.niner.canais.CanalDeVenda.CanalIndisponivelException;
import com.vetor.niner.integracao.PedidoImportacaoService.PedidoNaoImportavelException;
import com.vetor.niner.integracao.PedidoWebhookRepositorio.Pendente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * O trabalho de transformar notificação em pedido — o par transacional de {@code PedidoWebhookJob}.
 *
 * <p>⛔ Separado do agendador porque {@code @Transactional} não vale em auto-invocação: chamado de
 * dentro do próprio bean, o método rodaria <b>sem transação</b>, o {@code FOR UPDATE SKIP LOCKED}
 * soltaria o lock no fim do {@code SELECT} e dois workers pegariam a mesma notificação. Mesmo
 * desenho de {@code CobrancaWebhookJob} × {@code CobrancaWebhookProcessador}.
 */
@Component
public class PedidoWebhookProcessador {

    private static final Logger log = LoggerFactory.getLogger(PedidoWebhookProcessador.class);

    /** Lote pequeno: cada notificação custa duas chamadas HTTP ao marketplace. */
    private static final int LOTE = 10;

    /**
     * ⚠️ Só {@code orders_v2} vira pedido. {@code items} e {@code shipments} são registrados e
     * marcados como processados sem efeito — por enquanto. Tratá-los como erro encheria o painel
     * de falhas que não são falhas, e é exatamente por isso que os três tópicos ficaram
     * desmarcados no painel do ML até agora.
     */
    private static final String TOPICO_PEDIDO = "orders_v2";

    private final PedidoWebhookRepositorio webhooks;
    private final PedidoImportacaoService importacao;

    public PedidoWebhookProcessador(PedidoWebhookRepositorio webhooks,
                                    PedidoImportacaoService importacao) {
        this.webhooks = webhooks;
        this.importacao = importacao;
    }

    /**
     * Processa um lote de notificações do tenant corrente.
     *
     * @return quantas foram tratadas — só para log
     */
    @Transactional
    public int processarLoteDoTenantCorrente() {
        List<Pendente> lote = webhooks.pegarLote(LOTE);
        for (Pendente p : lote) {
            try {
                if (!TOPICO_PEDIDO.equals(p.topico())) {
                    webhooks.marcarProcessado(p.id());
                    continue;
                }
                String idExterno = idDoRecurso(p.recurso());
                if (idExterno == null) {
                    // Recurso que não é um pedido reconhecível: não adianta repetir.
                    webhooks.marcarFalha(p.id(), "Recurso não reconhecido: " + p.recurso());
                    continue;
                }
                importacao.importar(p.idCanal(), webhooks.tipoDoCanal(p.idCanal()), idExterno);
                webhooks.marcarProcessado(p.id());

            } catch (CanalIndisponivelException e) {
                // Transitório: o canal não respondeu agora. NÃO marca processado — a notificação
                // continua na fila e a próxima rodada tenta de novo.
                webhooks.marcarFalha(p.id(), e.getMessage());
                log.warn("Notificação {}: canal indisponível — fica na fila. {}", p.id(), e.getMessage());

            } catch (PedidoNaoImportavelException e) {
                // ⭐ Precisa de ação do lojista (quase sempre: anúncio ainda não vinculado). Também
                // NÃO marca processado: no minuto em que ele vincular, a mesma notificação passa
                // sozinha. A mensagem fica na linha, e é ela que a tela mostra.
                webhooks.marcarFalha(p.id(), e.getMessage());
                log.info("Notificação {} aguardando ação do lojista: {}", p.id(), e.getMessage());

            } catch (RuntimeException e) {
                webhooks.marcarFalha(p.id(), e.toString());
                log.error("Notificação {} falhou: {}", p.id(), e.toString());
            }
        }
        return lote.size();
    }

    /**
     * O id do pedido dentro do recurso notificado ({@code /orders/2000003508897546}).
     *
     * <p>⚠️ Não confia no formato: o ML já mudou o formato de recurso antes, e um {@code split}
     * ingênuo devolveria string vazia sem ninguém notar.
     */
    public static String idDoRecurso(String recurso) {
        if (recurso == null || !recurso.contains("/orders/")) {
            return null;
        }
        String cauda = recurso.substring(recurso.lastIndexOf('/') + 1).trim();
        return cauda.isEmpty() ? null : cauda;
    }
}
