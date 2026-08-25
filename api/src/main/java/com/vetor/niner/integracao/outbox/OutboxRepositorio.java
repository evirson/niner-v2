package com.vetor.niner.integracao.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Acesso a {@code outbox_evento} (P2, spec §3.3.6). Postgres como fila — sem broker (P6).
 *
 * <p>⚠️ <b>{@code outbox_evento} tem RLS</b> (V024). Um worker que consultasse a fila sem
 * {@code TenantContext} leria <b>zero linha em silêncio</b> — não daria erro, apenas nunca
 * despacharia nada, e ninguém perceberia até um lojista reclamar que o estoque do anúncio nunca
 * muda. Foi exatamente esse o bug dos jobs de fiscal em 2026-08-19. O padrão correto, e o único
 * usado aqui: {@link #listarTenantIds()} (sem RLS, em {@code plataforma.tenant}) e depois
 * {@code TenantContext.comTenant} por tenant.
 */
@Repository
public class OutboxRepositorio {

    /**
     * ⚠️ Mapper próprio, não injetado: <b>esta aplicação não expõe um bean de
     * {@code ObjectMapper}</b> (descoberto ao subir o contexto — "No qualifying bean of type
     * ObjectMapper"). Mesmo padrão já usado por {@code CobrancaWebhookProcessador}. Aqui só
     * serializa/desserializa o {@code payload} do outbox, que é JSON nosso de ponta a ponta — não
     * precisa da configuração do Spring.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public OutboxRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Um evento pronto para despacho. {@code payload} já desserializado. */
    public record EventoPendente(long id, long idTenant, String tipo, String agregadoId,
                                 JsonNode payload, int tentativas) {
    }

    /**
     * Tenants existentes. Roda em {@code plataforma.*}, que <b>não</b> tem RLS — é a única
     * consulta deste repositório que pode rodar sem contexto de tenant, e é justamente a que
     * estabelece o contexto das outras.
     */
    @Transactional(readOnly = true)
    public List<Long> listarTenantIds() {
        return jdbc.sql("SELECT id_tenant FROM plataforma.tenant").query(Long.class).list();
    }

    /**
     * Grava um evento na fila. <b>Chamado de dentro da transação de domínio</b> — é isso que
     * torna o par "mudou o estoque" + "avisar o canal" atômico (P2): ou os dois acontecem, ou
     * nenhum. Publicar direto no canal aqui amarraria o PDV à latência de um terceiro.
     */
    @Transactional
    public long enfileirar(String tipo, String agregadoId, Object payload) {
        String payloadJson;
        try {
            payloadJson = JSON.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload de outbox não serializável: " + tipo, e);
        }
        return jdbc.sql("""
                        INSERT INTO outbox_evento (id_tenant, tipo, agregado_id, payload)
                        VALUES (plataforma.tenant_atual(), ?, ?, CAST(? AS jsonb))
                        RETURNING id
                        """)
                .params(tipo, agregadoId, payloadJson)
                .query(Long.class)
                .single();
    }

    /**
     * Pega um lote de eventos do tenant corrente, <b>travando as linhas</b>.
     *
     * <p>⛔ {@code FOR UPDATE SKIP LOCKED} é o que permite mais de uma instância da API sem
     * duplicar trabalho: quem chegar depois pula as linhas já travadas em vez de esperar.
     *
     * <p>⚠️ <b>O lock só vale enquanto a transação viver</b>, e quem abre a transação é o método
     * anotado do <i>processador</i> — não este. Chamar isto de um método sem {@code @Transactional}
     * (ou por auto-invocação, que não passa pelo proxy do Spring) solta o lock no fim do
     * {@code SELECT} e faz dois workers pegarem o mesmo evento. É o motivo de agendador e trabalho
     * viverem em beans separados neste projeto.
     */
    @Transactional
    public List<EventoPendente> pegarLote(int tamanho) {
        return jdbc.sql("""
                        SELECT id, id_tenant, tipo, agregado_id, payload, tentativas
                          FROM outbox_evento
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND status IN ('PENDENTE', 'ERRO')
                           AND proximo_retry <= now()
                         ORDER BY proximo_retry, id
                         LIMIT ?
                           FOR UPDATE SKIP LOCKED
                        """)
                .param(tamanho)
                .query((rs, n) -> {
                    JsonNode payload;
                    try {
                        String bruto = rs.getString("payload");
                        payload = bruto == null ? JSON.createObjectNode() : JSON.readTree(bruto);
                    } catch (Exception e) {
                        // Payload corrompido não pode derrubar o lote inteiro; vira objeto vazio e
                        // o processamento falha só neste evento, que é onde o defeito está.
                        payload = JSON.createObjectNode();
                    }
                    return new EventoPendente(rs.getLong("id"), rs.getLong("id_tenant"),
                            rs.getString("tipo"), rs.getString("agregado_id"), payload,
                            rs.getInt("tentativas"));
                })
                .list();
    }

    @Transactional
    public void marcarProcessado(long id) {
        jdbc.sql("""
                        UPDATE outbox_evento
                           SET status = 'PROCESSADO', processado_em = now(), erro = NULL
                         WHERE id_tenant = plataforma.tenant_atual() AND id = ?
                        """)
                .params(id).update();
    }

    /**
     * Registra a falha e reagenda — ou manda para o dead-letter quando as tentativas acabam.
     *
     * @param proximaTentativa espera até a próxima; {@code null} = dead-letter (R7: fica visível
     *        no painel de saúde para reprocessamento manual, nunca some)
     */
    @Transactional
    public void marcarFalha(long id, String erro, Duration proximaTentativa) {
        if (proximaTentativa == null) {
            jdbc.sql("""
                            UPDATE outbox_evento
                               SET status = 'DEAD_LETTER', tentativas = tentativas + 1, erro = ?
                             WHERE id_tenant = plataforma.tenant_atual() AND id = ?
                            """)
                    .params(recortar(erro), id).update();
            return;
        }
        jdbc.sql("""
                        UPDATE outbox_evento
                           SET status = 'ERRO', tentativas = tentativas + 1, erro = ?,
                               proximo_retry = now() + CAST(? AS interval)
                         WHERE id_tenant = plataforma.tenant_atual() AND id = ?
                        """)
                .params(recortar(erro), proximaTentativa.toSeconds() + " seconds", id)
                .update();
    }

    /** Mensagem de erro vai para tela (R7); um stack trace inteiro na coluna não ajuda ninguém. */
    private static String recortar(String erro) {
        if (erro == null) {
            return null;
        }
        return erro.length() <= 500 ? erro : erro.substring(0, 500) + "…";
    }
}
