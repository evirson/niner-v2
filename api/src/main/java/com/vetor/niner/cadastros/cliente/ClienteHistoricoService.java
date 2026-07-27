package com.vetor.niner.cadastros.cliente;

import com.vetor.niner.cadastros.cliente.ClienteHistoricoDtos.ClienteHistoricoResponse;
import com.vetor.niner.cadastros.cliente.ClienteHistoricoDtos.ItemVendaHistorico;
import com.vetor.niner.cadastros.cliente.ClienteHistoricoDtos.ParcelaHistorico;
import com.vetor.niner.cadastros.cliente.ClienteHistoricoDtos.ResumoCrediario;
import com.vetor.niner.cadastros.cliente.ClienteHistoricoDtos.ResumoParcelasComJuros;
import com.vetor.niner.cadastros.cliente.ClienteHistoricoDtos.ResumoParcelasSemJuros;
import com.vetor.niner.cadastros.cliente.ClienteHistoricoDtos.VendaHistorico;
import com.vetor.niner.financeiro.TipoCarteiraDtos.CategoriaCarteira;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Histórico do cliente (2026-07-23, pedido do dono do produto): compras (venda física, R9),
 * parcelas (contas a receber) e resumo das parcelas de crediário em aberto. Somente leitura —
 * não existe ainda fluxo de lançamento de venda nem baixa de parcela, então em qualquer tenant
 * real esta tela nasce vazia até essas tabelas terem dados (seed manual ou o módulo de vendas).
 *
 * <p>Filtro por {@code id_tenant} explícito em toda consulta, além do RLS — mesmo motivo
 * documentado em {@code PlanoContasService}/{@code TipoCarteiraService} (Testcontainers conecta
 * como superusuário, que ignora RLS mesmo com {@code FORCE}).
 */
@Service
public class ClienteHistoricoService {

    private final JdbcClient jdbc;

    public ClienteHistoricoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ClienteHistoricoResponse historico(long idCliente) {
        exigirClienteExistente(idCliente);
        return new ClienteHistoricoResponse(
                buscarCompras(idCliente), buscarProdutos(idCliente), buscarParcelas(idCliente),
                buscarResumoCrediario(idCliente));
    }

    private java.util.List<VendaHistorico> buscarCompras(long idCliente) {
        return jdbc.sql("""
                        SELECT v.id_venda, e.codigo_empresa, v.data_venda,
                               COALESCE(SUM(
                                 CASE WHEN pmd.credito_debito = 'D'
                                      THEN pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto + pmd.valor_acrescimo
                                      ELSE 0 END
                               ), 0) AS valor,
                               COALESCE(SUM(
                                 CASE WHEN pmd.credito_debito = 'D' THEN pmd.qtd_produto ELSE 0 END
                               ), 0) AS qtd_produtos
                        FROM venda v
                        JOIN empresa e ON e.id_empresa = v.id_empresa AND e.id_tenant = v.id_tenant
                        LEFT JOIN produto_movimento_mestre pmm
                               ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant
                              AND pmm.tipo_movimento = 'VENDA'
                        LEFT JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        WHERE v.id_tenant = plataforma.tenant_atual() AND v.id_cliente = ?
                        GROUP BY v.id_venda, e.codigo_empresa, v.data_venda
                        ORDER BY v.data_venda DESC, v.id_venda DESC
                        """)
                .param(idCliente)
                .query((rs, n) -> new VendaHistorico(
                        rs.getLong("id_venda"),
                        rs.getInt("codigo_empresa"),
                        rs.getObject("data_venda", OffsetDateTime.class),
                        rs.getBigDecimal("valor"),
                        rs.getBigDecimal("qtd_produtos")))
                .list();
    }

    /**
     * Produtos vendidos por compra (painel "Produtos da Compra", 2026-07-27) — uma linha por
     * item do ledger de estoque (débito de venda), com o preço unitário líquido já considerando
     * desconto/acréscimo da linha.
     */
    private java.util.List<ItemVendaHistorico> buscarProdutos(long idCliente) {
        return jdbc.sql("""
                        SELECT v.id_venda, p.descricao AS descricao_produto,
                               vl.descricao AS variacao_linha, vc.descricao AS variacao_coluna,
                               pmd.qtd_produto AS qtd_vendida,
                               pmd.preco_venda, pmd.valor_desconto, pmd.valor_acrescimo
                        FROM venda v
                        JOIN produto_movimento_mestre pmm
                               ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant
                              AND pmm.tipo_movimento = 'VENDA'
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                              AND pmd.credito_debito = 'D'
                        JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                        JOIN produto p        ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_variante_linha vl
                               ON vl.id_variante_linha = pb.id_variante_linha AND vl.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_variante_coluna vc
                               ON vc.id_variante_coluna = pb.id_variante_coluna AND vc.id_tenant = pb.id_tenant
                        WHERE v.id_tenant = plataforma.tenant_atual() AND v.id_cliente = ?
                        ORDER BY v.data_venda DESC, v.id_venda DESC, p.descricao ASC
                        """)
                .param(idCliente)
                .query(ClienteHistoricoService::mapearItemVenda)
                .list();
    }

    private static ItemVendaHistorico mapearItemVenda(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal qtd = rs.getBigDecimal("qtd_vendida");
        BigDecimal precoVenda = rs.getBigDecimal("preco_venda");
        BigDecimal desconto = rs.getBigDecimal("valor_desconto");
        BigDecimal acrescimo = rs.getBigDecimal("valor_acrescimo");
        BigDecimal precoVendaLiquido = qtd.signum() == 0
                ? precoVenda
                : qtd.multiply(precoVenda).subtract(desconto).add(acrescimo).divide(qtd, 2, RoundingMode.HALF_UP);

        return new ItemVendaHistorico(
                rs.getLong("id_venda"),
                rs.getString("descricao_produto"),
                rs.getString("variacao_linha"),
                rs.getString("variacao_coluna"),
                qtd,
                precoVendaLiquido);
    }

    private java.util.List<ParcelaHistorico> buscarParcelas(long idCliente) {
        return jdbc.sql("""
                        SELECT cr.id_conta_receber, cr.id_venda, ev.codigo_empresa AS codigo_empresa_venda,
                               v.data_venda, cr.numero_parcela, cr.data_vencimento, cr.data_recebimento,
                               cr.valor_receber, cr.valor_juros, cr.valor_desconto, cr.valor_recebido,
                               ep.codigo_empresa AS codigo_empresa_pagamento,
                               tc.nome_carteira, tc.categoria_carteira::text AS categoria_carteira
                        FROM contas_receber cr
                        JOIN venda v            ON v.id_venda = cr.id_venda AND v.id_tenant = cr.id_tenant
                        JOIN empresa ev          ON ev.id_empresa = v.id_empresa AND ev.id_tenant = v.id_tenant
                        LEFT JOIN empresa ep     ON ep.id_empresa = cr.id_empresa_pagamento AND ep.id_tenant = cr.id_tenant
                        JOIN tipo_carteira tc    ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND v.id_cliente = ?
                        ORDER BY v.data_venda DESC, cr.numero_parcela ASC
                        """)
                .param(idCliente)
                .query(ClienteHistoricoService::mapearParcela)
                .list();
    }

    private static ParcelaHistorico mapearParcela(ResultSet rs, int rowNum) throws SQLException {
        long idContaReceber = rs.getLong("id_conta_receber");
        long idVenda = rs.getLong("id_venda");
        int codigoEmpresaVenda = rs.getInt("codigo_empresa_venda");
        OffsetDateTime dataVenda = rs.getObject("data_venda", OffsetDateTime.class);
        int numeroParcela = rs.getInt("numero_parcela");
        OffsetDateTime dataVencimento = rs.getObject("data_vencimento", OffsetDateTime.class);
        OffsetDateTime dataPagamento = rs.getObject("data_recebimento", OffsetDateTime.class);
        BigDecimal valorAPagar = rs.getBigDecimal("valor_receber")
                .add(rs.getBigDecimal("valor_juros"))
                .subtract(rs.getBigDecimal("valor_desconto"));
        BigDecimal valorPago = rs.getBigDecimal("valor_recebido");
        int codigoEmpresaPagamentoRaw = rs.getInt("codigo_empresa_pagamento");
        Integer codigoEmpresaPagamento = rs.wasNull() ? null : codigoEmpresaPagamentoRaw;
        String nomeCarteira = rs.getString("nome_carteira");
        CategoriaCarteira categoriaCarteira = CategoriaCarteira.valueOf(rs.getString("categoria_carteira"));

        return new ParcelaHistorico(
                idContaReceber,
                idVenda,
                codigoEmpresaVenda,
                dataVenda,
                numeroParcela,
                dataVencimento,
                dataPagamento,
                valorAPagar,
                valorPago,
                codigoEmpresaPagamento,
                diasAtraso(dataVencimento, dataPagamento),
                nomeCarteira,
                categoriaCarteira);
    }

    /**
     * Paga: dias entre vencimento e pagamento (paga em dia/adiantada não é atraso). Não paga:
     * dias entre vencimento e hoje, só se já venceu (ainda não vencida não é atraso, é "a
     * vencer"). {@code null} = sem atraso / não aplicável.
     */
    private static Long diasAtraso(OffsetDateTime dataVencimento, OffsetDateTime dataPagamento) {
        OffsetDateTime referencia = dataPagamento != null ? dataPagamento : OffsetDateTime.now();
        long dias = ChronoUnit.DAYS.between(dataVencimento, referencia);
        return dias > 0 ? dias : null;
    }

    /**
     * Só parcelas de categoria CREDIÁRIO, em aberto (não pagas). {@code total} = vencidas + a
     * vencer — parcelas já pagas (mesmo com atraso) não entram, o resumo é sobre saldo em
     * aberto.
     */
    private ResumoCrediario buscarResumoCrediario(long idCliente) {
        var linha = jdbc.sql("""
                        SELECT
                          COALESCE(SUM(CASE WHEN cr.data_recebimento IS NULL AND cr.data_vencimento < now()
                                            THEN cr.valor_receber END), 0) AS vencidas_valor,
                          COALESCE(SUM(CASE WHEN cr.data_recebimento IS NULL AND cr.data_vencimento < now()
                                            THEN cr.valor_juros END), 0) AS vencidas_juros,
                          COUNT(*) FILTER (WHERE cr.data_recebimento IS NULL AND cr.data_vencimento < now()) AS vencidas_qtd,
                          COALESCE(SUM(CASE WHEN cr.data_recebimento IS NULL AND cr.data_vencimento >= now()
                                            THEN cr.valor_receber END), 0) AS avencer_valor,
                          COUNT(*) FILTER (WHERE cr.data_recebimento IS NULL AND cr.data_vencimento >= now()) AS avencer_qtd
                        FROM contas_receber cr
                        JOIN venda v          ON v.id_venda = cr.id_venda AND v.id_tenant = cr.id_tenant
                        JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND v.id_cliente = ?
                          AND tc.categoria_carteira = 'CREDIARIO'::categoria_carteira
                        """)
                .param(idCliente)
                .query((rs, n) -> new Object[] {
                        rs.getBigDecimal("vencidas_valor"), rs.getBigDecimal("vencidas_juros"), rs.getLong("vencidas_qtd"),
                        rs.getBigDecimal("avencer_valor"), rs.getLong("avencer_qtd"),
                })
                .single();

        BigDecimal vencidasValor = (BigDecimal) linha[0];
        BigDecimal vencidasJuros = (BigDecimal) linha[1];
        long vencidasQtd = (long) linha[2];
        BigDecimal aVencerValor = (BigDecimal) linha[3];
        long aVencerQtd = (long) linha[4];

        ResumoParcelasComJuros vencidas = new ResumoParcelasComJuros(vencidasValor, vencidasJuros, vencidasQtd);
        ResumoParcelasSemJuros aVencer = new ResumoParcelasSemJuros(aVencerValor, aVencerQtd);
        ResumoParcelasComJuros total = new ResumoParcelasComJuros(
                vencidasValor.add(aVencerValor), vencidasJuros, vencidasQtd + aVencerQtd);

        return new ResumoCrediario(vencidas, aVencer, total);
    }

    private void exigirClienteExistente(long idCliente) {
        boolean existe = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM cliente
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_cliente = ?)
                        """)
                .param(idCliente).query(Boolean.class).single());
        if (!existe) {
            throw new ResponseStatusException(NOT_FOUND, "Cliente não encontrado.");
        }
    }
}
