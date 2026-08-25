package com.vetor.niner.integracao.outbox;

import com.vetor.niner.canais.CanalDeVenda.CanalIndisponivelException;
import com.vetor.niner.integracao.outbox.OutboxRepositorio.EventoPendente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * O trabalho transacional do outbox — <b>o par de {@link OutboxJob}</b>.
 *
 * <h2>⛔ Por que agendador e trabalho vivem em beans separados</h2>
 *
 * {@code @Transactional} <b>não vale em auto-invocação</b>: um método anotado chamado de dentro do
 * próprio bean não passa pelo proxy do Spring e roda <b>sem transação</b>. Num consumidor de fila
 * isso é silencioso e grave: o {@code FOR UPDATE SKIP LOCKED} solta o lock no fim do
 * {@code SELECT} e <b>dois workers pegam o mesmo evento</b> — publicando o mesmo estoque duas
 * vezes, ou pior, duas versões diferentes em ordem imprevisível.
 *
 * <p>Este projeto já pagou por isso: é o mesmo desenho de {@code CobrancaWebhookJob} ×
 * {@code CobrancaWebhookProcessador}. Não juntar os dois.
 *
 * <p>⚠️ E o {@code TenantContext} tem de estar posto <b>antes</b> de chamar aqui: a tabela tem RLS
 * e sem contexto o lote sai vazio, sem erro (P8).
 */
@Component
public class OutboxProcessador {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessador.class);

    /** Quantos eventos por tenant por rodada. */
    private static final int LOTE = 25;

    private final OutboxRepositorio repositorio;
    private final Map<String, ManipuladorDeEvento> manipuladores;

    /**
     * Quem sabe executar um tipo de evento. Cada canal registra os seus; o outbox não conhece
     * marketplace nenhum — só sabe entregar.
     */
    public interface ManipuladorDeEvento {
        /** O valor de {@code outbox_evento.tipo} que este manipulador atende. */
        String tipo();

        /**
         * @throws CanalIndisponivelException falha <b>transitória</b> — reagenda com backoff.
         *         Qualquer outra exceção é tratada como definitiva e vai ao dead-letter mais
         *         rápido, porque repetir não vai consertar.
         */
        void executar(EventoPendente evento);
    }

    public OutboxProcessador(OutboxRepositorio repositorio, List<ManipuladorDeEvento> manipuladores) {
        this.repositorio = repositorio;
        this.manipuladores = manipuladores.stream()
                .collect(java.util.stream.Collectors.toMap(ManipuladorDeEvento::tipo, m -> m));
    }

    /**
     * Processa um lote do tenant corrente.
     *
     * <p>⚠️ <b>Uma transação para o lote inteiro</b> é deliberado: é ela que segura o lock das
     * linhas do {@code SKIP LOCKED} até o fim. O custo é que o lote inteiro rola para trás se algo
     * escapar do {@code try} — e é por isso que o {@code catch} aqui é largo de propósito: nenhuma
     * falha de um evento pode derrubar os outros 24 nem soltar os locks.
     *
     * @return quantos eventos foram tratados (com sucesso ou não) — só para log
     */
    @Transactional
    public int processarLoteDoTenantCorrente() {
        List<EventoPendente> lote = repositorio.pegarLote(LOTE);
        for (EventoPendente evento : lote) {
            ManipuladorDeEvento manipulador = manipuladores.get(evento.tipo());
            if (manipulador == null) {
                // Tipo sem dono não é falha transitória: nenhuma espera vai criar o manipulador.
                // Vai direto ao dead-letter, onde alguém vê (R7) — em vez de girar 10 vezes.
                repositorio.marcarFalha(evento.id(),
                        "Nenhum manipulador registrado para o tipo '" + evento.tipo() + "'.", null);
                log.warn("Outbox: evento {} do tipo '{}' sem manipulador — dead-letter.",
                        evento.id(), evento.tipo());
                continue;
            }
            try {
                manipulador.executar(evento);
                repositorio.marcarProcessado(evento.id());
            } catch (CanalIndisponivelException e) {
                Duration espera = BackoffOutbox.proximaEspera(evento.tentativas());
                repositorio.marcarFalha(evento.id(), e.getMessage(), espera);
                if (espera == null) {
                    log.warn("Outbox: evento {} esgotou as tentativas — dead-letter. Causa: {}",
                            evento.id(), e.getMessage());
                }
            } catch (RuntimeException e) {
                // Definitivo: repetir não conserta payload inválido nem regra de negócio recusada.
                // ⚠️ Ainda assim damos UMA chance a mais antes do dead-letter quando o evento é
                // novo — erro definitivo que na verdade era transitório existe, e o custo de uma
                // tentativa extra é baixo perto de um dead-letter injusto.
                Duration espera = evento.tentativas() == 0 ? Duration.ofMinutes(1) : null;
                repositorio.marcarFalha(evento.id(), e.getMessage(), espera);
                log.warn("Outbox: evento {} falhou definitivamente ({}).", evento.id(), e.toString());
            }
        }
        return lote.size();
    }
}
