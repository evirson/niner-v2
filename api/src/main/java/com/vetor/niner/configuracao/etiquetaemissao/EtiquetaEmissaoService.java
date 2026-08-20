package com.vetor.niner.configuracao.etiquetaemissao;

import com.vetor.niner.catalogo.ProdutoBarraService;
import com.vetor.niner.catalogo.ProdutoBarraDtos.ProdutoBarraResponse;
import com.vetor.niner.configuracao.etiquetaemissao.EtiquetaEmissaoDtos.FornecedorOpcaoResponse;
import com.vetor.niner.configuracao.etiquetaemissao.EtiquetaEmissaoDtos.ProdutoEmissaoResponse;
import com.vetor.niner.configuracao.etiquetaemissao.EtiquetaEmissaoDtos.ProdutoOpcaoResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Emissão de Etiqueta de Produtos — ver {@code package-info.java} pro resumo de escopo/decisões.
 */
@Service
public class EtiquetaEmissaoService {

    private static final String CAMPOS_VARIACAO = """
            pb.id_variacao, pb.sku, p.descricao, p.marca, p.referencia, p.preco_venda,
            co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho
            """;

    private static final String JOINS_VARIANTES = """
            LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
            LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
            """;

    private final JdbcClient jdbc;
    private final ProdutoBarraService produtoBarraService;

    public EtiquetaEmissaoService(JdbcClient jdbc, ProdutoBarraService produtoBarraService) {
        this.jdbc = jdbc;
        this.produtoBarraService = produtoBarraService;
    }

    /** Modo Individual (2026-08-05, revisão) — QUALQUER produto ativo, com ou sem variação/SKU já
     * cadastrado (o modo Individual cria na hora via `ProdutoBarraService` se precisar). */
    @Transactional(readOnly = true)
    public List<ProdutoOpcaoResponse> buscarProdutos(String busca) {
        String filtro = (busca == null || busca.isBlank()) ? "" : " AND p.descricao ILIKE ?";
        var query = jdbc.sql("""
                SELECT p.id_produto, p.descricao, p.marca, p.referencia, p.id_grade
                FROM produto p
                WHERE p.id_tenant = plataforma.tenant_atual() AND p.ativo
                """ + filtro + " ORDER BY p.descricao LIMIT 10");
        if (!filtro.isEmpty()) {
            query = query.param("%" + busca.trim() + "%");
        }
        return query.query((rs, n) -> new ProdutoOpcaoResponse(
                        rs.getLong("id_produto"), rs.getString("descricao"), rs.getString("marca"), rs.getString("referencia"),
                        getLongOuNulo(rs, "id_grade")))
                .list();
    }

    /** {@code rs.getObject(coluna, Long.class)} não converte `integer` (o tipo real de
     * `produto.id_grade`) pra `Long` de forma confiável no driver — mesmo padrão já usado em
     * {@code CrmService.getLongOuNulo}. */
    private static Long getLongOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    /** Acha (ou cria, se ainda não existir) a variação escolhida no modo Individual — delega pra
     * {@code ProdutoBarraService}, que também valida se cor/tamanho são obrigatórios pra este
     * produto (produto com grade) e se o tamanho pertence à grade dele. */
    @Transactional
    public ProdutoEmissaoResponse obterOuCriarVariacao(long idProduto, Long idCor, Long idTamanho) {
        ProdutoBarraResponse r = produtoBarraService.obterOuCriar(idProduto, idCor, idTamanho);
        return new ProdutoEmissaoResponse(
                r.idVariacao(), r.sku(), r.descricao(), r.marca(), r.referencia(), r.precoVenda(),
                r.variacaoCor(), r.variacaoTamanho(), null);
    }

    @Transactional(readOnly = true)
    public List<FornecedorOpcaoResponse> buscarFornecedores(String busca) {
        String filtro = (busca == null || busca.isBlank()) ? "" : " AND razao_social ILIKE ?";
        var query = jdbc.sql("""
                SELECT id_fornecedor, razao_social
                FROM fornecedor
                WHERE id_tenant = plataforma.tenant_atual() AND ativo
                """ + filtro + " ORDER BY razao_social LIMIT 10");
        if (!filtro.isEmpty()) {
            query = query.param("%" + busca.trim() + "%");
        }
        return query.query((rs, n) -> new FornecedorOpcaoResponse(rs.getLong("id_fornecedor"), rs.getString("razao_social")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<ProdutoEmissaoResponse> buscarPorEntradas(LocalDate dataDe, LocalDate dataAte, Long idFornecedor, Integer notaFiscal) {
        exigirParOuNenhum(dataDe, dataAte);
        if (dataDe == null && idFornecedor == null && notaFiscal == null) {
            throw new IllegalArgumentException("Informe ao menos um filtro: período, fornecedor ou nota fiscal.");
        }

        StringBuilder sql = new StringBuilder("""
                SELECT %s, SUM(pmd.qtd_produto) AS quantidade_sugerida
                FROM produto_movimento_mestre pmm
                JOIN produto_movimento_detalhe pmd
                       ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                      AND pmd.credito_debito = 'C'
                JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                %s
                WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.tipo_movimento = 'COMPRA'
                """.formatted(CAMPOS_VARIACAO, JOINS_VARIANTES));
        List<Object> params = new ArrayList<>();
        if (dataDe != null) {
            sql.append(" AND (pmm.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?");
            params.add(dataDe);
            params.add(dataAte);
        }
        if (idFornecedor != null) {
            sql.append(" AND pmm.id_fornecedor = ?");
            params.add(idFornecedor);
        }
        if (notaFiscal != null) {
            sql.append(" AND pmm.nota_fiscal = ?");
            params.add(notaFiscal);
        }
        sql.append("""
                 GROUP BY pb.id_variacao, pb.sku, p.descricao, p.marca, p.referencia, p.preco_venda,
                          co.descricao, ta.descricao
                 ORDER BY p.descricao
                """);

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> mapearVariacao(rs, rs.getBigDecimal("quantidade_sugerida")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<ProdutoEmissaoResponse> buscarPorEstoques(Jwt jwt, Long idEmpresaSolicitada, Long idCategoria) {
        long idEmpresa = resolverEmpresaObrigatoria(jwt, idEmpresaSolicitada);

        StringBuilder sql = new StringBuilder("""
                SELECT %s, pe.qtd_estoque AS quantidade_sugerida
                FROM produto_estoque pe
                JOIN produto_barra pb ON pb.id_variacao = pe.id_variacao AND pb.id_tenant = pe.id_tenant
                JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                %s
                """.formatted(CAMPOS_VARIACAO, JOINS_VARIANTES));
        List<Object> params = new ArrayList<>();
        if (idCategoria != null) {
            sql.append(" JOIN produto_categoria pc ON pc.id_produto = p.id_produto AND pc.id_tenant = p.id_tenant AND pc.id_categoria = ?");
            params.add(idCategoria);
        }
        sql.append(" WHERE pe.id_tenant = plataforma.tenant_atual() AND pe.id_empresa = ? AND pe.qtd_estoque > 0");
        params.add(idEmpresa);
        sql.append(" ORDER BY p.descricao");

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> mapearVariacao(rs, rs.getBigDecimal("quantidade_sugerida")))
                .list();
    }

    private static ProdutoEmissaoResponse mapearVariacao(java.sql.ResultSet rs, java.math.BigDecimal quantidadeSugerida)
            throws java.sql.SQLException {
        return new ProdutoEmissaoResponse(
                rs.getLong("id_variacao"), rs.getString("sku"), rs.getString("descricao"), rs.getString("marca"),
                rs.getString("referencia"), rs.getBigDecimal("preco_venda"), rs.getString("variacao_cor"),
                rs.getString("variacao_tamanho"), quantidadeSugerida);
    }

    private long resolverEmpresaObrigatoria(Jwt jwt, Long idEmpresaSolicitada) {
        if (!ehAdmin(jwt)) {
            return idEmpresaSessao(jwt);
        }
        if (idEmpresaSolicitada == null) {
            throw new IllegalArgumentException("Selecione a empresa.");
        }
        return idEmpresaSolicitada;
    }

    private void exigirParOuNenhum(Object de, Object ate) {
        if ((de == null) != (ate == null)) {
            throw new IllegalArgumentException("Informe início e fim do período, ou deixe os dois em branco.");
        }
    }

    private static boolean ehAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }

    private static long idEmpresaSessao(Jwt jwt) {
        return ((Number) jwt.getClaim("eid")).longValue();
    }
}
