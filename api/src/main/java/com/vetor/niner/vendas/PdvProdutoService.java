package com.vetor.niner.vendas;

import com.vetor.niner.comum.armazenamento.ArmazenamentoDeArquivos;
import com.vetor.niner.vendas.PdvDtos.EstoqueEmpresa;
import com.vetor.niner.vendas.PdvDtos.PdvProdutoResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Busca por descrição (F2) e leitura por código de barras (sku/ean) pro PDV
 * (docs/telas/pdv.md) — cada variação (`produto_barra`) é uma linha, com estoque por empresa
 * (todas as empresas do tenant, `LEFT JOIN produto_estoque` — variação sem movimento ainda
 * não tem linha lá, conta como 0) mais o total somado. Só produto ativo. Puramente leitura —
 * a validação de estoque "de verdade" só acontece na efetivação da venda ({@link
 * PdvVendaService}), porque o saldo pode mudar entre a leitura e o F5.
 *
 * <p>Filtro por {@code id_tenant} explícito em toda consulta, além do RLS — mesmo motivo
 * documentado em {@code ClienteHistoricoService}/{@code TipoCarteiraService} (Testcontainers
 * conecta como superusuário, que ignora RLS mesmo com {@code FORCE}).
 */
@Service
public class PdvProdutoService {

    private static final int LIMITE_BUSCA = 20;

    private final JdbcClient jdbc;
    private final ArmazenamentoDeArquivos armazenamento;

    public PdvProdutoService(JdbcClient jdbc, ArmazenamentoDeArquivos armazenamento) {
        this.jdbc = jdbc;
        this.armazenamento = armazenamento;
    }

    @Transactional(readOnly = true)
    public List<PdvProdutoResponse> buscar(String busca, String marca, String referencia) {
        List<LinhaEstoque> linhas = jdbc.sql(CTE_VARIACOES_BUSCA)
                .params(paraFiltro(busca), paraFiltro(marca), paraFiltro(referencia), LIMITE_BUSCA)
                .query(this::mapearLinha)
                .list();
        return agrupar(linhas);
    }

    /** {@code null}/branco vira {@code "%"} (não filtra); senão {@code ILIKE} parcial, maiúsculo
     *  (mesma convenção de "digitação livre sempre maiúscula" do resto do projeto). */
    private static String paraFiltro(String valor) {
        return valor == null || valor.isBlank() ? "%" : "%" + valor.trim().toUpperCase(Locale.ROOT) + "%";
    }

    @Transactional(readOnly = true)
    public PdvProdutoResponse buscarPorCodigo(String codigo) {
        List<LinhaEstoque> linhas = jdbc.sql(CTE_VARIACOES_CODIGO)
                .params(codigo, codigo)
                .query(this::mapearLinha)
                .list();
        List<PdvProdutoResponse> agrupado = agrupar(linhas);
        if (agrupado.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Produto não encontrado para este código de barras.");
        }
        return agrupado.get(0);
    }

    /**
     * Uma linha crua da consulta (uma variação × uma empresa) — agrupadas por
     * {@code idVariacao} em {@link #agrupar} pra virar o {@code estoquePorEmpresa} aninhado.
     * {@code urlImagem} vem da primeira foto da galeria do produto (indice 0), já resolvida
     * pra URL pública — só depende do produto, então é igual em toda linha de uma mesma variação.
     */
    private record LinhaEstoque(
            long idVariacao, String descricaoProduto, String variacaoCor, String variacaoTamanho,
            String sku, BigDecimal precoVenda, String urlImagem, String marca, String referencia,
            int codigoEmpresa, String nomeEmpresa, BigDecimal qtdEstoque) {
    }

    private LinhaEstoque mapearLinha(ResultSet rs, int rowNum) throws SQLException {
        String chaveImagem = rs.getString("imagem_produto");
        return new LinhaEstoque(
                rs.getLong("id_variacao"),
                rs.getString("descricao_produto"),
                rs.getString("variacao_cor"),
                rs.getString("variacao_tamanho"),
                rs.getString("sku"),
                rs.getBigDecimal("preco_venda"),
                chaveImagem == null ? null : armazenamento.urlPublica(chaveImagem),
                rs.getString("marca"),
                rs.getString("referencia"),
                rs.getInt("codigo_empresa"),
                rs.getString("nome_empresa"),
                rs.getBigDecimal("qtd_estoque"));
    }

    /**
     * As linhas chegam pré-ordenadas (descrição, variação, empresa) — {@link LinkedHashMap}
     * preserva a ordem de primeira aparição de cada variação.
     */
    private static List<PdvProdutoResponse> agrupar(List<LinhaEstoque> linhas) {
        Map<Long, List<LinhaEstoque>> porVariacao = new LinkedHashMap<>();
        for (LinhaEstoque linha : linhas) {
            porVariacao.computeIfAbsent(linha.idVariacao(), k -> new ArrayList<>()).add(linha);
        }
        List<PdvProdutoResponse> resultado = new ArrayList<>();
        for (List<LinhaEstoque> grupo : porVariacao.values()) {
            LinhaEstoque primeira = grupo.get(0);
            List<EstoqueEmpresa> estoquePorEmpresa = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (LinhaEstoque linha : grupo) {
                estoquePorEmpresa.add(new EstoqueEmpresa(linha.codigoEmpresa(), linha.nomeEmpresa(), linha.qtdEstoque()));
                total = total.add(linha.qtdEstoque());
            }
            resultado.add(new PdvProdutoResponse(
                    primeira.idVariacao(), primeira.descricaoProduto(), primeira.variacaoCor(), primeira.variacaoTamanho(),
                    primeira.sku(), primeira.precoVenda(), estoquePorEmpresa, total, primeira.urlImagem(),
                    primeira.marca(), primeira.referencia()));
        }
        return resultado;
    }

    private static final String VARIACOES_BASE = """
            SELECT pb.id_variacao, p.descricao AS descricao_produto,
                   co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho,
                   pb.sku, p.preco_venda, pi.imagem AS imagem_produto, p.marca, p.referencia
            FROM produto_barra pb
            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
            LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
            LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
            LEFT JOIN produto_imagem pi
                   ON pi.id_produto = p.id_produto AND pi.id_tenant = p.id_tenant AND pi.indice = 0
            """;

    private static final String ESTOQUE_POR_EMPRESA = """
            SELECT v.id_variacao, v.descricao_produto, v.variacao_cor, v.variacao_tamanho, v.sku, v.preco_venda,
                   v.imagem_produto, v.marca, v.referencia,
                   e.codigo_empresa, COALESCE(e.nome_fantasia, e.razao_social) AS nome_empresa,
                   COALESCE(pe.qtd_estoque, 0) AS qtd_estoque
            FROM variacoes v
            CROSS JOIN empresa e
            LEFT JOIN produto_estoque pe
                   ON pe.id_variacao = v.id_variacao AND pe.id_empresa = e.id_empresa
                  AND pe.id_tenant = plataforma.tenant_atual()
            WHERE e.id_tenant = plataforma.tenant_atual() AND e.ativo = true
            """;

    /** Filtra e limita ANTES de expandir por empresa — senão o LIMIT cortaria linhas no meio de uma
     *  variação. {@code marca}/{@code referencia} entram como filtro adicional (2026-08-12, popup de
     *  pesquisa da Entrada de Produtos por Compra) — {@code COALESCE} pq as duas colunas são nullable
     *  e {@code NULL ILIKE '%'} nunca é {@code true}. */
    private static final String CTE_VARIACOES_BUSCA = "WITH variacoes AS (" + VARIACOES_BASE + """
            WHERE pb.id_tenant = plataforma.tenant_atual() AND p.ativo = true
              AND p.descricao ILIKE ? AND COALESCE(p.marca, '') ILIKE ? AND COALESCE(p.referencia, '') ILIKE ?
            ORDER BY p.descricao ASC, pb.id_variacao ASC
            LIMIT ?
            )
            """ + ESTOQUE_POR_EMPRESA + "ORDER BY v.descricao_produto ASC, v.id_variacao ASC, e.codigo_empresa ASC";

    private static final String CTE_VARIACOES_CODIGO = "WITH variacoes AS (" + VARIACOES_BASE + """
            WHERE pb.id_tenant = plataforma.tenant_atual() AND p.ativo = true
              AND (pb.sku = ? OR pb.ean = ?)
            )
            """ + ESTOQUE_POR_EMPRESA + "ORDER BY e.codigo_empresa ASC";
}
