package com.vetor.niner.estoque.relatorioestoque;

import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.ColunaEmpresa;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.LinhaAnalitica;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.LinhaInventario;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.LinhaSintetica;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.ModeloRelatorioEstoque;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.RelatorioEstoqueResponse;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.SituacaoProduto;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.TipoQuantidade;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.TotalInventario;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.TotalSintetico;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relatório de Estoque — ver {@code package-info.java} pro resumo de escopo (3 modelos, filtros
 * comuns, colunas dinâmicas por empresa nos modelos Sintético/Analítico).
 */
@Service
public class RelatorioEstoqueService {

    private final JdbcClient jdbc;

    public RelatorioEstoqueService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RelatorioEstoqueResponse gerar(
            Jwt jwt, ModeloRelatorioEstoque modelo, List<Long> idsEmpresaSolicitadas, List<String> marcas,
            List<Long> idsCategoria, TipoQuantidade tipoQuantidade, SituacaoProduto situacao) {
        List<ColunaEmpresa> colunas = resolverColunasEmpresa(jwt, idsEmpresaSolicitadas);
        List<Long> idsEmpresaEfetivo = colunas.stream().map(ColunaEmpresa::idEmpresa).toList();
        TipoQuantidade tipoEfetivo = tipoQuantidade == null ? TipoQuantidade.TODOS : tipoQuantidade;

        if (modelo == ModeloRelatorioEstoque.INVENTARIO) {
            return gerarInventario(idsEmpresaEfetivo, marcas, idsCategoria, tipoEfetivo, situacao);
        }
        return gerarSinteticoOuAnalitico(modelo, colunas, idsEmpresaEfetivo, marcas, idsCategoria, tipoEfetivo, situacao);
    }

    /** ADMIN pode escolher qualquer subconjunto (nenhuma marcada = todas as empresas ATIVAS do
     *  tenant); OPERADOR sempre só a própria empresa (claim {@code eid}), sem seletor. Diferente
     *  dos outros relatórios (que só restringem um WHERE), aqui a lista É a definição das colunas
     *  dinâmicas dos modelos Sintético/Analítico — por isso empresa inativa nunca entra "de
     *  graça" quando nada foi selecionado (só se o ADMIN escolher explicitamente). */
    private List<ColunaEmpresa> resolverColunasEmpresa(Jwt jwt, List<Long> idsEmpresaSolicitadas) {
        if (!ehAdmin(jwt)) {
            long idEmpresa = idEmpresaSessao(jwt);
            String nome = jdbc.sql("""
                            SELECT COALESCE(nome_fantasia, razao_social) FROM empresa
                            WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                            """)
                    .param(idEmpresa)
                    .query(String.class)
                    .single();
            return List.of(new ColunaEmpresa(idEmpresa, nome));
        }

        StringBuilder sql = new StringBuilder("""
                SELECT id_empresa, COALESCE(nome_fantasia, razao_social) AS nome
                FROM empresa
                WHERE id_tenant = plataforma.tenant_atual()
                """);
        List<Object> params = new ArrayList<>();
        if (idsEmpresaSolicitadas != null && !idsEmpresaSolicitadas.isEmpty()) {
            sql.append(" AND id_empresa IN (").append(placeholders(idsEmpresaSolicitadas.size())).append(")");
            params.addAll(idsEmpresaSolicitadas);
        } else {
            sql.append(" AND ativo = true");
        }
        sql.append(" ORDER BY nome");
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new ColunaEmpresa(rs.getLong("id_empresa"), rs.getString("nome")))
                .list();
    }

    private RelatorioEstoqueResponse gerarInventario(
            List<Long> idsEmpresaEfetivo, List<String> marcas, List<Long> idsCategoria,
            TipoQuantidade tipoQuantidade, SituacaoProduto situacao) {
        StringBuilder filtro = new StringBuilder(" WHERE p.id_tenant = plataforma.tenant_atual() AND p.tipo_item = 'MERCADORIA'");
        List<Object> paramsFiltro = new ArrayList<>();
        aplicarFiltrosProduto(filtro, paramsFiltro, marcas, idsCategoria, situacao);

        List<Object> params = new ArrayList<>(idsEmpresaEfetivo);
        params.addAll(paramsFiltro);

        String sql = """
                SELECT p.descricao AS descricao_produto, p.marca, p.referencia, p.preco_custo,
                       COALESCE(SUM(pe.qtd_estoque), 0) AS qtd_total
                FROM produto p
                JOIN produto_barra pb ON pb.id_tenant = p.id_tenant AND pb.id_produto = p.id_produto
                LEFT JOIN produto_estoque pe ON pe.id_tenant = p.id_tenant AND pe.id_variacao = pb.id_variacao
                                             AND pe.id_empresa IN (%s)
                """.formatted(placeholders(idsEmpresaEfetivo.size()))
                + filtro
                + " GROUP BY p.id_produto, p.descricao, p.marca, p.referencia, p.preco_custo"
                + " ORDER BY p.descricao";

        List<LinhaInventario> linhas = jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> {
                    BigDecimal qtdTotal = rs.getBigDecimal("qtd_total");
                    BigDecimal custoUnitario = rs.getBigDecimal("preco_custo");
                    return new LinhaInventario(
                            rs.getString("descricao_produto"), rs.getString("marca"), rs.getString("referencia"),
                            qtdTotal, custoUnitario, qtdTotal.multiply(custoUnitario));
                })
                .list();

        linhas = filtrarPorTipoQuantidade(linhas, tipoQuantidade, LinhaInventario::qtdTotal);

        BigDecimal totalQtd = BigDecimal.ZERO;
        BigDecimal totalCusto = BigDecimal.ZERO;
        for (LinhaInventario l : linhas) {
            totalQtd = totalQtd.add(l.qtdTotal());
            totalCusto = totalCusto.add(l.custoTotal());
        }

        return new RelatorioEstoqueResponse(
                ModeloRelatorioEstoque.INVENTARIO, List.of(), linhas, List.of(), List.of(),
                new TotalInventario(totalQtd, totalCusto), null);
    }

    private RelatorioEstoqueResponse gerarSinteticoOuAnalitico(
            ModeloRelatorioEstoque modelo, List<ColunaEmpresa> colunas, List<Long> idsEmpresaEfetivo,
            List<String> marcas, List<Long> idsCategoria, TipoQuantidade tipoQuantidade, SituacaoProduto situacao) {
        StringBuilder filtro = new StringBuilder(" WHERE p.id_tenant = plataforma.tenant_atual() AND p.tipo_item = 'MERCADORIA'");
        List<Object> paramsFiltro = new ArrayList<>();
        aplicarFiltrosProduto(filtro, paramsFiltro, marcas, idsCategoria, situacao);

        List<Object> params = new ArrayList<>(idsEmpresaEfetivo);
        params.addAll(paramsFiltro);

        String sql = """
                SELECT p.id_produto, p.descricao AS descricao_produto, p.marca, p.referencia,
                       pb.id_variacao, co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho,
                       emp.id_empresa, COALESCE(pe.qtd_estoque, 0) AS qtd_estoque
                FROM produto p
                JOIN produto_barra pb ON pb.id_tenant = p.id_tenant AND pb.id_produto = p.id_produto
                LEFT JOIN cfg_cor co ON co.id_tenant = pb.id_tenant AND co.id_cor = pb.id_cor AND co.id_cor <> 1
                LEFT JOIN cfg_tamanho ta ON ta.id_tenant = pb.id_tenant AND ta.id_tamanho = pb.id_tamanho AND ta.id_tamanho <> 1
                JOIN empresa emp ON emp.id_tenant = p.id_tenant AND emp.id_empresa IN (%s)
                LEFT JOIN produto_estoque pe ON pe.id_tenant = p.id_tenant AND pe.id_variacao = pb.id_variacao
                                             AND pe.id_empresa = emp.id_empresa
                """.formatted(placeholders(idsEmpresaEfetivo.size()))
                + filtro
                + " ORDER BY p.descricao, pb.id_variacao, emp.id_empresa";

        record Bruta(long idProduto, String descricao, String marca, String referencia, long idVariacao,
                      String variacaoCor, String variacaoTamanho, long idEmpresa, BigDecimal qtd) {
        }

        List<Bruta> brutas = jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> new Bruta(
                        rs.getLong("id_produto"), rs.getString("descricao_produto"), rs.getString("marca"), rs.getString("referencia"),
                        rs.getLong("id_variacao"), rs.getString("variacao_cor"), rs.getString("variacao_tamanho"),
                        rs.getLong("id_empresa"), rs.getBigDecimal("qtd_estoque")))
                .list();

        Map<Long, Integer> indiceEmpresa = new java.util.HashMap<>();
        for (int i = 0; i < colunas.size(); i++) {
            indiceEmpresa.put(colunas.get(i).idEmpresa(), i);
        }
        int n = colunas.size();

        record Acumulador(String descricao, String marca, String referencia, String variacaoCor,
                           String variacaoTamanho, BigDecimal[] qtds) {
        }

        // Analítico agrupa por variação (id_variacao); Sintético agrupa por produto (id_produto) —
        // somando as variações do mesmo produto na mesma coluna de empresa. A ordem de inserção no
        // LinkedHashMap já reflete a ordenação pedida (por descrição do produto) porque a consulta
        // SQL já veio ordenada assim — não precisa reordenar depois.
        LinkedHashMap<Long, Acumulador> porChave = new LinkedHashMap<>();
        for (Bruta b : brutas) {
            long chave = modelo == ModeloRelatorioEstoque.ANALITICO ? b.idVariacao() : b.idProduto();
            Acumulador acc = porChave.computeIfAbsent(chave, k -> new Acumulador(
                    b.descricao(), b.marca(), b.referencia(), b.variacaoCor(), b.variacaoTamanho(), new BigDecimal[n]));
            Integer indice = indiceEmpresa.get(b.idEmpresa());
            if (indice == null) continue;
            BigDecimal atual = acc.qtds()[indice];
            acc.qtds()[indice] = (atual == null ? BigDecimal.ZERO : atual).add(b.qtd());
        }

        BigDecimal[] totalPorEmpresa = new BigDecimal[n];
        java.util.Arrays.fill(totalPorEmpresa, BigDecimal.ZERO);
        BigDecimal totalGeral = BigDecimal.ZERO;

        if (modelo == ModeloRelatorioEstoque.ANALITICO) {
            List<LinhaAnalitica> linhas = new ArrayList<>();
            for (Acumulador acc : porChave.values()) {
                List<BigDecimal> qtdPorEmpresa = paraLista(acc.qtds());
                BigDecimal qtdTotal = somar(qtdPorEmpresa);
                linhas.add(new LinhaAnalitica(
                        acc.descricao(), acc.marca(), acc.referencia(), acc.variacaoCor(), acc.variacaoTamanho(),
                        qtdPorEmpresa, qtdTotal));
            }
            linhas = filtrarPorTipoQuantidade(linhas, tipoQuantidade, LinhaAnalitica::qtdTotal);
            return new RelatorioEstoqueResponse(
                    ModeloRelatorioEstoque.ANALITICO, colunas, List.of(), List.of(), linhas, null, null);
        }

        List<LinhaSintetica> linhas = new ArrayList<>();
        for (Acumulador acc : porChave.values()) {
            List<BigDecimal> qtdPorEmpresa = paraLista(acc.qtds());
            BigDecimal qtdTotal = somar(qtdPorEmpresa);
            linhas.add(new LinhaSintetica(acc.descricao(), acc.marca(), acc.referencia(), qtdPorEmpresa, qtdTotal));
        }
        linhas = filtrarPorTipoQuantidade(linhas, tipoQuantidade, LinhaSintetica::qtdTotal);

        for (LinhaSintetica l : linhas) {
            for (int i = 0; i < n; i++) {
                totalPorEmpresa[i] = totalPorEmpresa[i].add(l.qtdPorEmpresa().get(i));
            }
            totalGeral = totalGeral.add(l.qtdTotal());
        }

        return new RelatorioEstoqueResponse(
                ModeloRelatorioEstoque.SINTETICO, colunas, List.of(), linhas, List.of(), null,
                new TotalSintetico(paraLista(totalPorEmpresa), totalGeral));
    }

    private void aplicarFiltrosProduto(
            StringBuilder filtro, List<Object> params, List<String> marcas, List<Long> idsCategoria, SituacaoProduto situacao) {
        if (marcas != null && !marcas.isEmpty()) {
            filtro.append(" AND p.marca IN (").append(placeholders(marcas.size())).append(")");
            params.addAll(marcas);
        }
        if (idsCategoria != null && !idsCategoria.isEmpty()) {
            filtro.append(" AND EXISTS (SELECT 1 FROM produto_categoria pc WHERE pc.id_tenant = p.id_tenant")
                    .append(" AND pc.id_produto = p.id_produto AND pc.id_categoria IN (")
                    .append(placeholders(idsCategoria.size())).append("))");
            params.addAll(idsCategoria);
        }
        switch (situacao == null ? SituacaoProduto.ATIVOS : situacao) {
            case INATIVOS -> filtro.append(" AND p.ativo = false");
            case TODOS -> {
                /* sem filtro de situação */
            }
            default -> filtro.append(" AND p.ativo = true");
        }
    }

    private static <T> List<T> filtrarPorTipoQuantidade(
            List<T> linhas, TipoQuantidade tipoQuantidade, java.util.function.Function<T, BigDecimal> qtdTotal) {
        return switch (tipoQuantidade) {
            case DIFERENTE_DE_ZERO -> linhas.stream().filter(l -> qtdTotal.apply(l).signum() != 0).toList();
            case ZERADA -> linhas.stream().filter(l -> qtdTotal.apply(l).signum() == 0).toList();
            case TODOS -> linhas;
        };
    }

    private static List<BigDecimal> paraLista(BigDecimal[] valores) {
        List<BigDecimal> lista = new ArrayList<>(valores.length);
        for (BigDecimal v : valores) {
            lista.add(v == null ? BigDecimal.ZERO : v);
        }
        return lista;
    }

    private static BigDecimal somar(List<BigDecimal> valores) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : valores) {
            total = total.add(v);
        }
        return total;
    }

    private static String placeholders(int quantidade) {
        return String.join(",", java.util.Collections.nCopies(quantidade, "?"));
    }

    private static boolean ehAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }

    private static long idEmpresaSessao(Jwt jwt) {
        return ((Number) jwt.getClaim("eid")).longValue();
    }
}
