package com.vetor.niner.integracao;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * As gravações que transformam um pedido de marketplace em venda do ERP (M6).
 *
 * <p>⚠️ Bean à parte pelo motivo de sempre ({@code @Transactional} não vale em auto-invocação), e
 * toda consulta filtra {@code id_tenant} no texto do SQL (P8).
 */
@Repository
public class PedidoVendaRepositorio {

    private final JdbcClient jdbc;

    public PedidoVendaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** O pedido, do jeito que a conversão precisa conhecê-lo. */
    public record PedidoParaConverter(long idPedido, long idCanal, long idEmpresa, String status,
                                      Long idVenda, boolean estoqueReservado, Long idCarteira,
                                      BigDecimal total, String idExterno) {
    }

    /** Uma linha do pedido, com o custo do produto para o CMV. */
    public record ItemDoPedidoNoErp(long idVariacao, BigDecimal quantidade, BigDecimal precoUnit,
                                    BigDecimal precoCusto) {
    }

    @Transactional(readOnly = true)
    public Optional<PedidoParaConverter> buscar(long idPedido) {
        return jdbc.sql("""
                        SELECT p.id_pedido, p.id_canal, c.id_empresa, p.status::text AS status,
                               p.id_venda, p.estoque_reservado, c.id_carteira, p.total, p.id_externo
                          FROM pedido p
                          JOIN canal c ON c.id_tenant = p.id_tenant AND c.id_canal = p.id_canal
                         WHERE p.id_tenant = plataforma.tenant_atual() AND p.id_pedido = ?
                        """)
                .params(idPedido)
                .query((rs, n) -> {
                    long idVenda = rs.getLong("id_venda");
                    boolean vendaNula = rs.wasNull();
                    long idCarteira = rs.getLong("id_carteira");
                    boolean carteiraNula = rs.wasNull();
                    return new PedidoParaConverter(rs.getLong("id_pedido"), rs.getLong("id_canal"),
                            rs.getLong("id_empresa"), rs.getString("status"),
                            vendaNula ? null : idVenda, rs.getBoolean("estoque_reservado"),
                            carteiraNula ? null : idCarteira,
                            rs.getBigDecimal("total"), rs.getString("id_externo"));
                })
                .optional();
    }

    /**
     * ⚠️ {@code preco_custo} vem de {@code produto.preco_custo} <b>no momento da venda</b>, igual
     * ao PDV. É ele que alimenta o CMV do DRE e da Lucratividade — sem custo, a venda de
     * marketplace apareceria com margem de 100%, que é o defeito que a V059 documentou.
     */
    @Transactional(readOnly = true)
    public List<ItemDoPedidoNoErp> itens(long idPedido) {
        return jdbc.sql("""
                        SELECT pi.id_variacao, pi.quantidade, pi.preco_unit, p.preco_custo
                          FROM pedido_item pi
                          JOIN produto_barra pb ON pb.id_tenant = pi.id_tenant AND pb.id_variacao = pi.id_variacao
                          JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                         WHERE pi.id_tenant = plataforma.tenant_atual() AND pi.id_pedido = ?
                         ORDER BY pi.id_pedido_item
                        """)
                .params(idPedido)
                .query((rs, n) -> new ItemDoPedidoNoErp(rs.getLong("id_variacao"),
                        rs.getBigDecimal("quantidade"), rs.getBigDecimal("preco_unit"),
                        rs.getBigDecimal("preco_custo")))
                .list();
    }

    // -------------------------------------------------------------------------------- reserva

    /**
     * Sobe a reserva e marca o pedido — <b>num comando só</b>, e é o comando do pedido que decide.
     *
     * <p>⛔ A marca é atualizada com {@code WHERE estoque_reservado = false}: se casar 0 linhas, a
     * reserva já foi feita e <b>nada</b> é somado. Ler-conferir-gravar deixaria janela para duas
     * chegadas do mesmo pedido reservarem duas vezes — e reserva dobrada trava o estoque inteiro de
     * um produto vendido uma vez só.
     *
     * @return {@code true} se esta chamada foi a que reservou
     */
    @Transactional
    public boolean reservar(long idPedido, long idEmpresa) {
        int marcou = jdbc.sql("""
                        UPDATE pedido SET estoque_reservado = true, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_pedido = ?
                           AND estoque_reservado = false
                        """)
                .params(idPedido).update();
        if (marcou == 0) {
            return false;
        }

        // ⭐ Este UPDATE dispara o gatilho da V067: reservar republica o saldo menor no anúncio,
        // sozinho. `disponivel` é coluna gerada (qtd_estoque − reservado) e é ela que o M3 publica.
        jdbc.sql("""
                        UPDATE produto_estoque pe
                           SET reservado = pe.reservado + pi.quantidade, atualizado_em = now()
                          FROM pedido_item pi
                         WHERE pi.id_tenant = plataforma.tenant_atual()
                           AND pi.id_pedido = ?
                           AND pe.id_tenant = pi.id_tenant
                           AND pe.id_variacao = pi.id_variacao
                           AND pe.id_empresa = ?
                        """)
                .params(idPedido, idEmpresa).update();
        return true;
    }

    /**
     * Devolve a reserva ao estoque disponível.
     *
     * <p>Usado quando o pedido é cancelado <b>e</b> quando ele vira venda — na venda o estoque sai
     * de verdade, e manter a reserva além disso subtrairia a mesma peça duas vezes de
     * {@code disponivel}.
     */
    @Transactional
    public void liberarReserva(long idPedido, long idEmpresa) {
        int desmarcou = jdbc.sql("""
                        UPDATE pedido SET estoque_reservado = false, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_pedido = ?
                           AND estoque_reservado = true
                        """)
                .params(idPedido).update();
        if (desmarcou == 0) {
            return;
        }
        // ⚠️ `GREATEST(..., 0)`: a coluna tem CHECK (reservado >= 0) e um estado inconsistente
        // (reserva zerada por fora, correção manual) faria o UPDATE estourar aqui — derrubando o
        // cancelamento de um pedido por causa de um saldo de reserva que já estava errado.
        jdbc.sql("""
                        UPDATE produto_estoque pe
                           SET reservado = GREATEST(pe.reservado - pi.quantidade, 0), atualizado_em = now()
                          FROM pedido_item pi
                         WHERE pi.id_tenant = plataforma.tenant_atual()
                           AND pi.id_pedido = ?
                           AND pe.id_tenant = pi.id_tenant
                           AND pe.id_variacao = pi.id_variacao
                           AND pe.id_empresa = ?
                        """)
                .params(idPedido, idEmpresa).update();
    }

    // -------------------------------------------------------------------------------- conversão

    /**
     * Marca o pedido como convertido. <b>É a trava de idempotência</b>.
     *
     * @return {@code true} se esta chamada foi a que converteu
     */
    @Transactional
    public boolean marcarConvertido(long idPedido, long idVenda) {
        return jdbc.sql("""
                        UPDATE pedido SET id_venda = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_pedido = ?
                           AND id_venda IS NULL
                        """)
                .params(idVenda, idPedido).update() == 1;
    }

    /**
     * Cria a venda de marketplace.
     *
     * <p>⛔ Três nulos que <b>são</b> a decisão de produto (§8), não campos esquecidos:
     * <ul>
     *   <li>{@code id_caixa} nulo — o dinheiro do canal não passa pela gaveta; vinculá-lo quebraria
     *       a conferência do Fechamento de Caixa;</li>
     *   <li>{@code id_cliente} nulo — o comprador do marketplace não vira cadastro;</li>
     *   <li>e o {@code id_funcionario} do movimento (abaixo) — sem vendedor, logo <b>sem
     *       comissão</b>, sem nenhuma linha de código: o relatório agrupa por funcionário.</li>
     * </ul>
     */
    @Transactional
    public long criarVenda(long idEmpresa) {
        return jdbc.sql("""
                        INSERT INTO venda (id_tenant, id_empresa, id_cliente, id_caixa, origem)
                        VALUES (plataforma.tenant_atual(), ?, NULL, NULL, 'MARKETPLACE')
                        RETURNING id_venda
                        """)
                .params(idEmpresa)
                .query(Long.class)
                .single();
    }

    /**
     * Lança a saída de estoque da venda.
     *
     * <p>⚠️ {@code id_funcionario} nulo — ver a nota de {@link #criarVenda}. E é este {@code INSERT}
     * que faz o saldo cair, pela trigger {@code fn_atualiza_estoque_movimento}: o M6 não mexe em
     * {@code produto_estoque.qtd_estoque} com a própria mão.
     */
    @Transactional
    public void lancarSaidaDeEstoque(long idVenda, long idEmpresa, List<ItemDoPedidoNoErp> itens) {
        long idMovimento = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_venda)
                        VALUES (plataforma.tenant_atual(), ?, 'VENDA', ?)
                        RETURNING id_movimento
                        """)
                .params(idEmpresa, idVenda)
                .query(Long.class)
                .single();

        for (ItemDoPedidoNoErp item : itens) {
            jdbc.sql("""
                            INSERT INTO produto_movimento_detalhe
                                   (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito,
                                    qtd_produto, preco_venda, preco_custo, id_funcionario, origem)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'D', ?, ?, ?, NULL, 'canal')
                            """)
                    .params(idMovimento, idEmpresa, item.idVariacao(), item.quantidade(),
                            item.precoUnit(), item.precoCusto())
                    .update();
        }
    }

    /**
     * A parcela a receber do marketplace.
     *
     * <p>⭐ É por aqui que a venda de marketplace entra no DRE, na Lucratividade e no Fluxo de Caixa
     * <b>sem código novo nesses relatórios</b>: uma parcela em aberto, na carteira do canal, que
     * carrega a comissão ({@code taxa_administradora}) e o prazo de liquidação.
     *
     * <p>⚠️ Nasce <b>em aberto</b> ({@code data_recebimento} nula), como uma venda no cartão. O
     * dinheiro do ML não está na conta do lojista no instante da venda — dá-la por recebida
     * inflaria o caixa de hoje com dinheiro que chega em duas semanas.
     */
    @Transactional
    public void lancarContaAReceber(long idVenda, long idEmpresa, long idCarteira, BigDecimal valor) {
        jdbc.sql("""
                        INSERT INTO contas_receber
                               (id_tenant, id_venda, id_carteira, numero_parcela, data_vencimento,
                                data_recebimento, valor_receber, valor_recebido, id_empresa_pagamento)
                        SELECT plataforma.tenant_atual(), ?, ?, 1,
                               now() + (COALESCE(tc.prazo_pagamento, 0) || ' days')::interval,
                               NULL, ?, 0, NULL
                          FROM tipo_carteira tc
                         WHERE tc.id_tenant = plataforma.tenant_atual() AND tc.id_carteira = ?
                        """)
                .params(idVenda, idCarteira, valor, idCarteira)
                .update();
    }

    /** Cria a carteira que representa o dinheiro deste canal, e a amarra ao canal. */
    @Transactional
    public long criarCarteiraDoCanal(long idCanal, String nome) {
        long idCarteira = jdbc.sql("""
                        INSERT INTO tipo_carteira
                               (id_tenant, nome_carteira, categoria_carteira, prazo_pagamento,
                                pc_minima, pc_maxima, taxa_administradora)
                        VALUES (plataforma.tenant_atual(), ?, 'CARTAO_CREDITO', 14, 1, 1, 0)
                        RETURNING id_carteira
                        """)
                .params(nome)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        UPDATE canal SET id_carteira = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """)
                .params(idCarteira, idCanal)
                .update();
        return idCarteira;
    }

    /** A taxa configurada na carteira do canal — 0 significa "o lojista ainda não configurou". */
    @Transactional(readOnly = true)
    public BigDecimal taxaDaCarteira(long idCarteira) {
        return jdbc.sql("""
                        SELECT COALESCE(taxa_administradora, 0) FROM tipo_carteira
                         WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?
                        """)
                .params(idCarteira)
                .query(BigDecimal.class)
                .optional()
                .orElse(BigDecimal.ZERO);
    }
}
