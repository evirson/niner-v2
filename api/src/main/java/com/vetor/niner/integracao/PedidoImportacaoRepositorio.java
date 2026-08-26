package com.vetor.niner.integracao;

import com.vetor.niner.canais.CanalDeVenda.ItemDoPedido;
import com.vetor.niner.canais.CanalDeVenda.PedidoDoCanal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Gravação do pedido importado do marketplace (M5, R5).
 *
 * <h2>⛔ Idempotência é a razão de ser desta classe</h2>
 *
 * O mesmo pedido chega várias vezes, de propósito: o webhook notifica cada mudança de estado, o
 * marketplace reenvia o que julga não entregue, e o polling de segurança traz os recentes de novo
 * a cada rodada. Reprocessar <b>não pode</b> duplicar pedido, item nem movimento de estoque (P2).
 *
 * <p>Quem garante isso é a chave natural {@code UNIQUE (id_canal, id_externo)}, que existe desde a
 * V021 — não um {@code SELECT} antes do {@code INSERT}, que teria janela entre a leitura e a
 * escrita e deixaria duas rodadas simultâneas passarem.
 */
@Repository
public class PedidoImportacaoRepositorio {

    private final JdbcClient jdbc;

    public PedidoImportacaoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Cria o pedido se ainda não existir; atualiza status/total se existir.
     *
     * <p>⚠️ O {@code UPDATE} do conflito é <b>estreito de propósito</b>: mexe em status, total,
     * frete e payload — nunca nos <b>itens</b>. Item de pedido já importado não muda no
     * marketplace, e regravá-los abriria a porta para duplicar linha de venda quando o M6 passar
     * a gerar movimento de estoque a partir daqui.
     *
     * @return o id do pedido no ERP, e se ele acabou de nascer
     */
    @Transactional
    public Resultado salvarPedido(long idCanal, PedidoDoCanal doCanal) {
        Long existente = jdbc.sql("""
                        SELECT id_pedido FROM pedido
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_canal = ? AND id_externo = ?
                        """)
                .params(idCanal, doCanal.idExterno())
                .query(Long.class)
                .optional()
                .orElse(null);

        if (existente != null) {
            // ⛔ O status do canal NÃO atropela o estado local de expedição (V070). Se o lojista
            // marcou EM_SEPARACAO e o polling roda 15 min depois com o ML ainda dizendo "paid", um
            // UPDATE direto devolveria o pedido à fila de separação — já separado — e alguém o
            // separaria de novo. `fn_status_pedido_do_canal` guarda essa regra no banco, para não
            // depender de quem escreve o próximo UPDATE.
            jdbc.sql("""
                            UPDATE pedido
                               SET status = fn_status_pedido_do_canal(status, CAST(? AS status_pedido)),
                                   total = ?, frete = ?,
                                   payload_bruto = CAST(? AS jsonb), atualizado_em = now()
                             WHERE id_tenant = plataforma.tenant_atual() AND id_pedido = ?
                            """)
                    .params(doCanal.status(), doCanal.total(), doCanal.frete(),
                            doCanal.payloadBruto(), existente)
                    .update();
            return new Resultado(existente, false);
        }

        long id = jdbc.sql("""
                        INSERT INTO pedido (id_tenant, id_canal, id_externo, status, comprador,
                                            total, frete, payload_bruto)
                        VALUES (plataforma.tenant_atual(), ?, ?, CAST(? AS status_pedido),
                                jsonb_build_object('nome', CAST(? AS text)), ?, ?, CAST(? AS jsonb))
                        RETURNING id_pedido
                        """)
                .params(idCanal, doCanal.idExterno(), doCanal.status(), doCanal.comprador(),
                        doCanal.total(), doCanal.frete(), doCanal.payloadBruto())
                .query(Long.class)
                .single();
        return new Resultado(id, true);
    }

    /** @param novo {@code true} = o pedido acabou de nascer; {@code false} = já existia */
    public record Resultado(long idPedido, boolean novo) {
    }

    /**
     * A variação do ERP ligada a este anúncio do canal (o de-para do R6).
     *
     * <p>⚠️ Vazio significa <b>"o lojista ainda não vinculou"</b>, e o chamador tem que tratar:
     * inventar uma variação aqui gravaria a venda de um produto no estoque de outro.
     */
    @Transactional(readOnly = true)
    public Optional<VinculoDoItem> vinculoDoItem(long idCanal, ItemDoPedido item) {
        return jdbc.sql("""
                        SELECT id_anuncio, id_variacao FROM anuncio
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_canal = ? AND id_externo = ?
                           AND id_externo_variacao IS NOT DISTINCT FROM ?
                        """)
                .params(idCanal, item.idExternoItem(), item.idExternoVariacao())
                .query((rs, n) -> new VinculoDoItem(rs.getLong("id_anuncio"), rs.getLong("id_variacao")))
                .optional();
    }

    public record VinculoDoItem(long idAnuncio, long idVariacao) {
    }

    @Transactional
    public void inserirItem(long idPedido, VinculoDoItem vinculo, ItemDoPedido item) {
        jdbc.sql("""
                        INSERT INTO pedido_item (id_tenant, id_pedido, id_variacao, id_anuncio,
                                                 quantidade, preco_unit)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?)
                        """)
                .params(idPedido, vinculo.idVariacao(), vinculo.idAnuncio(),
                        item.quantidade(), item.precoUnitario())
                .update();
    }
}
