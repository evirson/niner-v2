package com.vetor.niner.integracao;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * A fila de notificações recebidas do marketplace (M5).
 *
 * <p>⚠️ Bean à parte de quem a consome, pelo motivo de sempre: {@code @Transactional} não vale em
 * auto-invocação, e sem transação não há {@code SET LOCAL app.id_tenant} — a consulta voltaria
 * <b>vazia em silêncio</b>. Toda consulta filtra {@code id_tenant} no texto do SQL (P8).
 */
@Repository
public class PedidoWebhookRepositorio {

    /**
     * Quantas vezes vale a pena insistir num recurso.
     *
     * <p>Depois disso o worker para e a linha fica com o erro registrado. Um recurso que o canal
     * nunca devolve (pedido apagado, id inválido numa notificação estranha) giraria para sempre —
     * e girar para sempre esconde o problema atrás de ruído em vez de mostrá-lo.
     */
    public static final int MAX_TENTATIVAS = 8;

    private final JdbcClient jdbc;

    public PedidoWebhookRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Uma notificação à espera de processamento. */
    public record Pendente(long id, long idCanal, String topico, String recurso, int tentativas) {
    }

    /**
     * Registra a notificação. <b>Idempotente</b>: a mesma chave duas vezes não cria duas linhas.
     *
     * <p>⚠️ {@code ON CONFLICT DO NOTHING} e não "atualizar": se a notificação anterior ainda não
     * foi processada, ela já vai buscar o estado <b>atual</b> do pedido — reescrevê-la não
     * acrescenta nada. E se já foi processada, reprocessar por conta própria seria trabalho
     * repetido sem pedido de ninguém.
     */
    @Transactional
    public void registrar(long idCanal, String chave, String topico, String recurso, String payload) {
        jdbc.sql("""
                        INSERT INTO webhook_recebido
                               (id_tenant, id_canal, webhook_id, topico, recurso, payload)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, CAST(? AS jsonb))
                        ON CONFLICT (id_canal, webhook_id) DO NOTHING
                        """)
                .params(idCanal, chave, topico, recurso, payload)
                .update();
    }

    /**
     * Pega um lote de notificações não processadas, travando as linhas.
     *
     * <p>⚠️ {@code FOR UPDATE SKIP LOCKED} + <b>transação de quem chama</b>: é a transação que
     * segura o lock, e é por isso que o agendador e o trabalho vivem em beans separados. Chamado
     * de dentro do próprio bean, este método rodaria sem transação, o lock cairia no fim do
     * {@code SELECT} e dois workers pegariam a mesma notificação.
     */
    @Transactional
    public List<Pendente> pegarLote(int tamanho) {
        return jdbc.sql("""
                        SELECT id, id_canal, topico, recurso, tentativas
                          FROM webhook_recebido
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND processado_em IS NULL
                           AND tentativas < ?
                         ORDER BY recebido_em, id
                         LIMIT ?
                           FOR UPDATE SKIP LOCKED
                        """)
                .params(MAX_TENTATIVAS, tamanho)
                .query((rs, n) -> new Pendente(rs.getLong("id"), rs.getLong("id_canal"),
                        rs.getString("topico"), rs.getString("recurso"), rs.getInt("tentativas")))
                .list();
    }

    @Transactional
    public void marcarProcessado(long id) {
        jdbc.sql("""
                        UPDATE webhook_recebido SET processado_em = now(), erro = NULL
                         WHERE id_tenant = plataforma.tenant_atual() AND id = ?
                        """)
                .params(id).update();
    }

    /**
     * Marca a falha e conta a tentativa — <b>sem</b> marcar processado.
     *
     * <p>⚠️ Não marcar processado é o ponto: a notificação continua na fila e será tentada de
     * novo. Foi assim que o dreno de contingência fiscal perdeu notas para sempre em 2026-08-22 —
     * marcando "em andamento" antes de ter sucesso.
     */
    @Transactional
    public void marcarFalha(long id, String erro) {
        jdbc.sql("""
                        UPDATE webhook_recebido
                           SET erro = ?, tentativas = tentativas + 1
                         WHERE id_tenant = plataforma.tenant_atual() AND id = ?
                        """)
                .params(erro, id).update();
    }

    /**
     * O {@code tipo_canal} de um canal — é ele que escolhe o adapter.
     *
     * <p>⚠️ Mora aqui, e não nos processadores, porque lá seria <b>auto-invocação</b>: um método
     * {@code @Transactional} chamado de dentro do próprio bean não passa pelo proxy, roda sem
     * transação e a consulta não enxerga o tenant.
     */
    @Transactional(readOnly = true)
    public String tipoDoCanal(long idCanal) {
        return jdbc.sql("""
                        SELECT tipo::text FROM canal
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """)
                .params(idCanal)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Canal " + idCanal + " não encontrado."));
    }

    /** Canais conectados do tenant corrente — a fila do polling de segurança. */
    @Transactional(readOnly = true)
    public List<Long> canaisConectados() {
        return jdbc.sql("""
                        SELECT id_canal FROM canal
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND status = 'CONECTADO' AND credenciais IS NOT NULL
                         ORDER BY id_canal
                        """)
                .query(Long.class)
                .list();
    }
}
