package com.vetor.niner.integracao;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A fila de expedição (R5, M7) — o trabalho físico da venda de marketplace.
 *
 * <p>⚠️ Bean à parte pelo motivo de sempre, e toda consulta filtra {@code id_tenant} no texto do
 * SQL (P8).
 */
@Repository
public class ExpedicaoRepositorio {

    private final JdbcClient jdbc;

    public ExpedicaoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Uma linha da fila, com o que o separador precisa ver sem abrir nada. */
    public record PedidoNaFila(long idPedido, String idExterno, String nomeCanal, String status,
                               String comprador, BigDecimal total, OffsetDateTime criadoEm,
                               OffsetDateTime dataSeparacao, String codigoRastreio, int itens) {
    }

    /** Um item a separar. */
    public record ItemAExpedir(String sku, String descricao, BigDecimal quantidade) {
    }

    /**
     * A fila: o que já foi pago e ainda não saiu.
     *
     * <p>⚠️ <b>Não inclui RECEBIDO</b>, e é decisão: pedido não pago pode não ser pago nunca.
     * Separar mercadoria para ele é trabalho jogado fora — e, pior, a peça sai da prateleira e
     * some do fluxo da loja. A reserva já segura o estoque; a separação espera o dinheiro.
     */
    @Transactional(readOnly = true)
    public List<PedidoNaFila> fila(List<String> status) {
        return jdbc.sql("""
                        SELECT p.id_pedido, p.id_externo, c.nome AS nome_canal,
                               p.status::text AS status, p.comprador ->> 'nome' AS comprador,
                               p.total, p.criado_em, p.data_separacao, p.codigo_rastreio,
                               (SELECT count(*) FROM pedido_item pi
                                 WHERE pi.id_tenant = p.id_tenant AND pi.id_pedido = p.id_pedido) AS itens
                          FROM pedido p
                          JOIN canal c ON c.id_tenant = p.id_tenant AND c.id_canal = p.id_canal
                         WHERE p.id_tenant = plataforma.tenant_atual()
                           AND p.status::text IN (:status)
                         ORDER BY p.criado_em, p.id_pedido
                        """)
                .param("status", status)
                .query((rs, n) -> new PedidoNaFila(
                        rs.getLong("id_pedido"), rs.getString("id_externo"), rs.getString("nome_canal"),
                        rs.getString("status"), rs.getString("comprador"), rs.getBigDecimal("total"),
                        rs.getObject("criado_em", OffsetDateTime.class),
                        rs.getObject("data_separacao", OffsetDateTime.class),
                        rs.getString("codigo_rastreio"), rs.getInt("itens")))
                .list();
    }

    /** O que tem dentro do pacote — a lista que o separador leva para a prateleira. */
    @Transactional(readOnly = true)
    public List<ItemAExpedir> itens(long idPedido) {
        return jdbc.sql("""
                        SELECT pb.sku, pi.quantidade,
                               p.descricao || ' — ' || c.descricao || ' / ' || t.descricao AS descricao
                          FROM pedido_item pi
                          JOIN produto_barra pb ON pb.id_tenant = pi.id_tenant AND pb.id_variacao = pi.id_variacao
                          JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                          JOIN cfg_cor c ON c.id_tenant = pb.id_tenant AND c.id_cor = pb.id_cor
                          JOIN cfg_tamanho t ON t.id_tenant = pb.id_tenant AND t.id_tamanho = pb.id_tamanho
                         WHERE pi.id_tenant = plataforma.tenant_atual() AND pi.id_pedido = ?
                         ORDER BY pi.id_pedido_item
                        """)
                .params(idPedido)
                .query((rs, n) -> new ItemAExpedir(rs.getString("sku"), rs.getString("descricao"),
                        rs.getBigDecimal("quantidade")))
                .list();
    }

    /**
     * Avança o estado — <b>só a partir do estado esperado</b>.
     *
     * <p>⛔ O {@code WHERE status = ?} é a trava: dois operadores clicando "Separar" no mesmo
     * pedido, ou um clique duplo, fazem o segundo casar 0 linhas. Ler-conferir-gravar deixaria
     * janela — e no M7 a janela vale um pacote despachado duas vezes.
     *
     * @return {@code true} se esta chamada foi a que avançou
     */
    @Transactional
    public boolean avancar(long idPedido, String de, String para, Long idUsuario,
                           String codigoRastreio) {
        return jdbc.sql("""
                        UPDATE pedido
                           SET status = CAST(? AS status_pedido),
                               data_separacao = CASE WHEN ? = 'EM_SEPARACAO' THEN now() ELSE data_separacao END,
                               data_envio     = CASE WHEN ? = 'ENVIADO'      THEN now() ELSE data_envio END,
                               id_usuario_expedicao = COALESCE(?, id_usuario_expedicao),
                               codigo_rastreio = COALESCE(?, codigo_rastreio),
                               atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_pedido = ?
                           AND status = CAST(? AS status_pedido)
                        """)
                .params(para, para, para, idUsuario, codigoRastreio, idPedido, de)
                .update() == 1;
    }

    /** O status atual — para dizer ao lojista <b>por que</b> a ação foi recusada. */
    @Transactional(readOnly = true)
    public String statusAtual(long idPedido) {
        return jdbc.sql("""
                        SELECT status::text FROM pedido
                         WHERE id_tenant = plataforma.tenant_atual() AND id_pedido = ?
                        """)
                .params(idPedido)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** O que o evento de envio precisa para chegar ao canal. */
    public record DadosDoEnvio(long idCanal, String tipoCanal, String idExterno,
                               String idExternoEnvio, String codigoRastreio) {
    }

    @Transactional(readOnly = true)
    public DadosDoEnvio dadosDoEnvio(long idPedido) {
        return jdbc.sql("""
                        SELECT p.id_canal, c.tipo::text AS tipo_canal, p.id_externo,
                               p.payload_bruto -> 'shipping' ->> 'id' AS id_externo_envio,
                               p.codigo_rastreio
                          FROM pedido p
                          JOIN canal c ON c.id_tenant = p.id_tenant AND c.id_canal = p.id_canal
                         WHERE p.id_tenant = plataforma.tenant_atual() AND p.id_pedido = ?
                        """)
                .params(idPedido)
                .query((rs, n) -> new DadosDoEnvio(rs.getLong("id_canal"), rs.getString("tipo_canal"),
                        rs.getString("id_externo"), rs.getString("id_externo_envio"),
                        rs.getString("codigo_rastreio")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Pedido " + idPedido + " não encontrado."));
    }
}
