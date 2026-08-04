package com.vetor.niner.estoque.relatoriomovimentacao;

import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.CabecalhoKardex;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.Graficos;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.Kpis;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.LinhaAnalitica;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.LinhaKardex;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.LinhaSintetica;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.ModeloRelatorioMovimentacao;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.PontoGrafico;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.RelatorioMovimentacaoProdutosResponse;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.TipoMovimentoProduto;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.VariacaoEncontrada;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Relatório de Movimentação de Produtos (Kardex) — ver {@code package-info.java} pro resumo de
 * escopo (3 modelos, tipos não-físicos, saldo corrido do Kardex).
 */
@Service
public class RelatorioMovimentacaoProdutosService {

    private static final int PERIODO_MAXIMO_DIAS = 400;

    /** RESERVA/LIBERACAO_RESERVA não tiram/põem produto físico do estoque (são reserva de
     *  saldo, não movimentação real) — ver package-info. Entram no Kardex igual a qualquer
     *  outro tipo (a trigger de estoque não distingue), só ficam fora dos KPIs/gráficos "físicos". */
    private static final Set<String> TIPOS_NAO_FISICOS = Set.of("RESERVA", "LIBERACAO_RESERVA");

    private final JdbcClient jdbc;

    public RelatorioMovimentacaoProdutosService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RelatorioMovimentacaoProdutosResponse gerar(
            Jwt jwt, ModeloRelatorioMovimentacao modelo, LocalDate dataInicial, LocalDate dataFinal,
            List<Long> idsEmpresaSolicitadas, List<TipoMovimentoProduto> tipos, List<String> marcas,
            List<Long> idsCategoria, Long idVariacaoKardex, Long idEmpresaKardex) {
        validarPeriodo(dataInicial, dataFinal);

        if (modelo == ModeloRelatorioMovimentacao.KARDEX) {
            if (idVariacaoKardex == null) {
                throw new IllegalArgumentException("Escolha um produto para gerar o Kardex.");
            }
            long idEmpresa = ehAdmin(jwt) ? exigirEmpresaKardex(idEmpresaKardex) : idEmpresaSessao(jwt);
            return gerarKardex(idVariacaoKardex, idEmpresa, dataInicial, dataFinal);
        }

        List<Long> idsEmpresaEfetivo = resolverIdsEmpresa(jwt, idsEmpresaSolicitadas);
        List<LinhaAnalitica> linhas = buscarLinhasAnalitico(dataInicial, dataFinal, idsEmpresaEfetivo, tipos, marcas, idsCategoria);

        Kpis kpis = calcularKpis(linhas);
        Graficos graficos = calcularGraficos(linhas);

        if (modelo == ModeloRelatorioMovimentacao.ANALITICO) {
            return new RelatorioMovimentacaoProdutosResponse(
                    ModeloRelatorioMovimentacao.ANALITICO, kpis, graficos, linhas, null, null, null);
        }

        List<LinhaSintetica> linhasSintetico = calcularSintetico(linhas);
        return new RelatorioMovimentacaoProdutosResponse(
                ModeloRelatorioMovimentacao.SINTETICO, kpis, graficos, null, null, null, linhasSintetico);
    }

    /** Sem filtro de tenant explícito — RLS de {@code produto_barra} já isola por
     *  {@code plataforma.tenant_atual()}; usado só pro popup de busca do Kardex, então um
     *  resultado curto (10) é suficiente pra um autocomplete. */
    @Transactional(readOnly = true)
    public List<VariacaoEncontrada> buscarVariacoes(String busca) {
        String filtroBusca = (busca == null || busca.isBlank()) ? "" : " AND (p.descricao ILIKE ? OR pb.sku ILIKE ?)";
        String sql = """
                SELECT pb.id_variacao, pb.sku, p.descricao AS descricao_produto, p.marca,
                       vl.descricao AS variacao_linha, vc.descricao AS variacao_coluna
                FROM produto_barra pb
                JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                LEFT JOIN cfg_variante_linha vl ON vl.id_variante_linha = pb.id_variante_linha AND vl.id_tenant = pb.id_tenant
                LEFT JOIN cfg_variante_coluna vc ON vc.id_variante_coluna = pb.id_variante_coluna AND vc.id_tenant = pb.id_tenant
                WHERE pb.id_tenant = plataforma.tenant_atual()
                """
                + filtroBusca
                + " ORDER BY p.descricao LIMIT 10";

        var query = jdbc.sql(sql);
        if (!filtroBusca.isEmpty()) {
            String curinga = "%" + busca + "%";
            query = query.params(List.of(curinga, curinga));
        }
        return query.query((rs, n) -> new VariacaoEncontrada(
                        rs.getLong("id_variacao"), rs.getString("sku"), rs.getString("descricao_produto"),
                        rs.getString("marca"), rs.getString("variacao_linha"), rs.getString("variacao_coluna")))
                .list();
    }

    private void validarPeriodo(LocalDate dataInicial, LocalDate dataFinal) {
        if (dataInicial == null || dataFinal == null) {
            throw new IllegalArgumentException("Informe a data inicial e a data final.");
        }
        if (dataInicial.isAfter(dataFinal)) {
            throw new IllegalArgumentException("Data inicial não pode ser maior que a data final.");
        }
        if (dataInicial.plusDays(PERIODO_MAXIMO_DIAS).isBefore(dataFinal)) {
            throw new IllegalArgumentException("Período de consulta não pode exceder " + PERIODO_MAXIMO_DIAS + " dias.");
        }
    }

    private List<Long> resolverIdsEmpresa(Jwt jwt, List<Long> idsEmpresaSolicitadas) {
        if (!ehAdmin(jwt)) {
            return List.of(idEmpresaSessao(jwt));
        }
        if (idsEmpresaSolicitadas == null || idsEmpresaSolicitadas.isEmpty()) {
            return null;
        }
        return idsEmpresaSolicitadas;
    }

    private static long exigirEmpresaKardex(Long idEmpresaKardex) {
        if (idEmpresaKardex == null) {
            throw new IllegalArgumentException("Escolha a empresa para gerar o Kardex.");
        }
        return idEmpresaKardex;
    }

    private List<LinhaAnalitica> buscarLinhasAnalitico(
            LocalDate dataInicial, LocalDate dataFinal, List<Long> idsEmpresaEfetivo,
            List<TipoMovimentoProduto> tipos, List<String> marcas, List<Long> idsCategoria) {
        StringBuilder filtro = new StringBuilder(
                " WHERE pmd.id_tenant = plataforma.tenant_atual() AND pmm.data_movimento::date BETWEEN ? AND ?");
        List<Object> params = new ArrayList<>();
        params.add(dataInicial);
        params.add(dataFinal);

        if (idsEmpresaEfetivo != null) {
            filtro.append(" AND pmd.id_empresa IN (").append(placeholders(idsEmpresaEfetivo.size())).append(")");
            params.addAll(idsEmpresaEfetivo);
        }
        if (tipos != null && !tipos.isEmpty()) {
            filtro.append(" AND pmm.tipo_movimento::text IN (")
                    .append(placeholders(tipos.size())).append(")");
            tipos.forEach(t -> params.add(t.name()));
        }
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

        // COALESCE(NULLIF(pmd.preco_custo, 0), p.preco_custo): descoberto pelos testes deste
        // relatório (2026-08-04) que nenhum service que grava o ledger (Pdv/Devolução/
        // Cancelamento/Transferência/Balanço) preenchia `produto_movimento_detalhe.preco_custo`
        // — corrigido na raiz no mesmo dia (cada service passou a gravar o custo no INSERT).
        // O fallback pro custo ATUAL do cadastro (`produto.preco_custo`) fica só pras linhas
        // gravadas ANTES da correção, que ficaram com 0 pra sempre (não dá pra reconstruir o
        // custo histórico de um movimento que nunca o gravou).
        String sql = """
                SELECT pmd.id_empresa, COALESCE(e.nome_fantasia, e.razao_social) AS nome_empresa,
                       pmm.data_movimento, pmm.tipo_movimento::text AS tipo_movimento,
                       pmd.id_variacao, pb.sku, p.descricao AS descricao_produto, p.marca,
                       vl.descricao AS variacao_linha, vc.descricao AS variacao_coluna,
                       pmd.credito_debito::text AS credito_debito, pmd.qtd_produto,
                       COALESCE(NULLIF(pmd.preco_custo, 0), p.preco_custo) AS preco_custo,
                       pmm.id_venda, pmm.id_transferencia, pmm.id_devolucao, pmm.nota_fiscal,
                       COALESCE(forn.nome_fantasia, forn.razao_social) AS nome_fornecedor, pmd.origem,
                       fn.nome AS nome_funcionario
                FROM produto_movimento_detalhe pmd
                JOIN produto_movimento_mestre pmm ON pmm.id_movimento = pmd.id_movimento AND pmm.id_tenant = pmd.id_tenant
                JOIN empresa e ON e.id_empresa = pmd.id_empresa AND e.id_tenant = pmd.id_tenant
                JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                LEFT JOIN cfg_variante_linha vl ON vl.id_variante_linha = pb.id_variante_linha AND vl.id_tenant = pb.id_tenant
                LEFT JOIN cfg_variante_coluna vc ON vc.id_variante_coluna = pb.id_variante_coluna AND vc.id_tenant = pb.id_tenant
                LEFT JOIN fornecedor forn ON forn.id_fornecedor = pmm.id_fornecedor AND forn.id_tenant = pmm.id_tenant
                LEFT JOIN funcionario fn ON fn.id_funcionario = pmd.id_funcionario AND fn.id_tenant = pmd.id_tenant
                """
                + filtro
                + " ORDER BY pmm.data_movimento, pmm.id_movimento, pmd.id_movimento_detalhe";

        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> {
                    String tipo = rs.getString("tipo_movimento");
                    String creditoDebito = rs.getString("credito_debito");
                    BigDecimal qtd = rs.getBigDecimal("qtd_produto");
                    BigDecimal custo = rs.getBigDecimal("preco_custo");
                    BigDecimal entrada = "C".equals(creditoDebito) ? qtd : BigDecimal.ZERO;
                    BigDecimal saida = "D".equals(creditoDebito) ? qtd : BigDecimal.ZERO;
                    String documento = montarDocumento(
                            tipo, getLongOuNulo(rs, "id_venda"), getLongOuNulo(rs, "id_transferencia"),
                            getLongOuNulo(rs, "id_devolucao"), getLongOuNulo(rs, "nota_fiscal"),
                            rs.getString("nome_fornecedor"), rs.getString("origem"));
                    return new LinhaAnalitica(
                            rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                            rs.getObject("data_movimento", OffsetDateTime.class), tipo, !TIPOS_NAO_FISICOS.contains(tipo),
                            rs.getLong("id_variacao"), rs.getString("sku"), rs.getString("descricao_produto"), rs.getString("marca"),
                            rs.getString("variacao_linha"), rs.getString("variacao_coluna"), entrada, saida, custo,
                            qtd.multiply(custo), documento, rs.getString("nome_funcionario"));
                })
                .list();
    }

    /** Documento contextualizado por tipo (P4 — não é o front que decide o texto). AJUSTE/DEVOLUCAO
     *  manual/RESERVA/LIBERACAO_RESERVA caem no fallback de {@code origem} (texto livre já gravado
     *  pela tela de origem, ex. "contagem de estoque") — sem isso, "—". */
    private static String montarDocumento(
            String tipo, Long idVenda, Long idTransferencia, Long idDevolucao, Long notaFiscal,
            String nomeFornecedor, String origem) {
        return switch (tipo) {
            case "VENDA", "CANCELAMENTO" -> idVenda != null ? "Venda #" + idVenda : origemOuTraco(origem);
            case "TRANSFERENCIA" -> idTransferencia != null ? "Transferência #" + idTransferencia : origemOuTraco(origem);
            case "DEVOLUCAO" -> idDevolucao != null ? "Devolução #" + idDevolucao : origemOuTraco(origem);
            case "COMPRA" -> (nomeFornecedor != null ? nomeFornecedor : "Compra") + (notaFiscal != null ? " — NF " + notaFiscal : "");
            default -> origemOuTraco(origem);
        };
    }

    private static String origemOuTraco(String origem) {
        if (origem == null || origem.isBlank()) return "—";
        return Character.toUpperCase(origem.charAt(0)) + origem.substring(1);
    }

    private static Kpis calcularKpis(List<LinhaAnalitica> linhas) {
        BigDecimal qtdEntrada = BigDecimal.ZERO;
        BigDecimal qtdSaida = BigDecimal.ZERO;
        BigDecimal valorEntrada = BigDecimal.ZERO;
        BigDecimal valorSaida = BigDecimal.ZERO;
        for (LinhaAnalitica l : linhas) {
            if (!l.movimentoFisico()) continue;
            qtdEntrada = qtdEntrada.add(l.entrada());
            qtdSaida = qtdSaida.add(l.saida());
            valorEntrada = valorEntrada.add(l.entrada().multiply(l.custoUnitario()));
            valorSaida = valorSaida.add(l.saida().multiply(l.custoUnitario()));
        }
        return new Kpis(qtdEntrada, qtdSaida, valorEntrada, valorSaida, valorEntrada.subtract(valorSaida));
    }

    private static Graficos calcularGraficos(List<LinhaAnalitica> linhas) {
        LinkedHashMap<String, BigDecimal> porTipo = new LinkedHashMap<>();
        LinkedHashMap<String, BigDecimal> porDia = new LinkedHashMap<>();
        LinkedHashMap<String, BigDecimal> porProdutoAjusteNegativo = new LinkedHashMap<>();
        DateTimeFormatter formatoDia = DateTimeFormatter.ISO_LOCAL_DATE;

        for (LinhaAnalitica l : linhas) {
            porTipo.merge(l.tipoMovimento(), l.valorMovimentado(), BigDecimal::add);

            String dia = l.dataMovimento().toLocalDate().format(formatoDia);
            porDia.merge(dia, l.valorMovimentado(), BigDecimal::add);

            if ("AJUSTE".equals(l.tipoMovimento()) && l.saida().signum() > 0) {
                BigDecimal valorPerda = l.saida().multiply(l.custoUnitario());
                porProdutoAjusteNegativo.merge(l.descricaoProduto(), valorPerda, BigDecimal::add);
            }
        }

        List<PontoGrafico> topAjustes = porProdutoAjusteNegativo.entrySet().stream()
                .map(e -> new PontoGrafico(e.getKey(), e.getValue()))
                .sorted((a, b) -> b.valor().compareTo(a.valor()))
                .limit(10)
                .toList();

        return new Graficos(
                porTipo.entrySet().stream().map(e -> new PontoGrafico(e.getKey(), e.getValue())).toList(),
                porDia.entrySet().stream().map(e -> new PontoGrafico(e.getKey(), e.getValue())).toList(),
                topAjustes);
    }

    private static List<LinhaSintetica> calcularSintetico(List<LinhaAnalitica> linhas) {
        record Acumulador(BigDecimal[] valores) {
        }
        LinkedHashMap<String, Acumulador> porTipo = new LinkedHashMap<>();
        for (LinhaAnalitica l : linhas) {
            Acumulador acc = porTipo.computeIfAbsent(l.tipoMovimento(),
                    k -> new Acumulador(new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}));
            acc.valores()[0] = acc.valores()[0].add(l.entrada());
            acc.valores()[1] = acc.valores()[1].add(l.saida());
            acc.valores()[2] = acc.valores()[2].add(l.entrada().multiply(l.custoUnitario()));
            acc.valores()[3] = acc.valores()[3].add(l.saida().multiply(l.custoUnitario()));
        }
        List<LinhaSintetica> resultado = new ArrayList<>();
        porTipo.forEach((tipo, acc) -> resultado.add(new LinhaSintetica(
                tipo, !TIPOS_NAO_FISICOS.contains(tipo), acc.valores()[0], acc.valores()[1], acc.valores()[2], acc.valores()[3])));
        return resultado;
    }

    private RelatorioMovimentacaoProdutosResponse gerarKardex(
            long idVariacao, long idEmpresa, LocalDate dataInicial, LocalDate dataFinal) {
        CabecalhoKardex cabecalho = buscarCabecalhoKardex(idVariacao, idEmpresa);
        if (cabecalho == null) {
            throw new IllegalArgumentException("Produto/variação não encontrado para a empresa selecionada.");
        }

        BigDecimal saldoInicial = jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN pmd.credito_debito = 'C' THEN pmd.qtd_produto ELSE -pmd.qtd_produto END), 0)
                        FROM produto_movimento_detalhe pmd
                        JOIN produto_movimento_mestre pmm ON pmm.id_movimento = pmd.id_movimento AND pmm.id_tenant = pmd.id_tenant
                        WHERE pmd.id_tenant = plataforma.tenant_atual() AND pmd.id_variacao = ? AND pmd.id_empresa = ?
                              AND pmm.data_movimento::date < ?
                        """)
                .params(idVariacao, idEmpresa, dataInicial)
                .query(BigDecimal.class)
                .single();

        String sql = """
                SELECT pmm.data_movimento, pmm.tipo_movimento::text AS tipo_movimento,
                       pmd.credito_debito::text AS credito_debito, pmd.qtd_produto,
                       pmm.id_venda, pmm.id_transferencia, pmm.id_devolucao, pmm.nota_fiscal,
                       COALESCE(forn.nome_fantasia, forn.razao_social) AS nome_fornecedor, pmd.origem,
                       fn.nome AS nome_funcionario
                FROM produto_movimento_detalhe pmd
                JOIN produto_movimento_mestre pmm ON pmm.id_movimento = pmd.id_movimento AND pmm.id_tenant = pmd.id_tenant
                LEFT JOIN fornecedor forn ON forn.id_fornecedor = pmm.id_fornecedor AND forn.id_tenant = pmm.id_tenant
                LEFT JOIN funcionario fn ON fn.id_funcionario = pmd.id_funcionario AND fn.id_tenant = pmd.id_tenant
                WHERE pmd.id_tenant = plataforma.tenant_atual() AND pmd.id_variacao = ? AND pmd.id_empresa = ?
                      AND pmm.data_movimento::date BETWEEN ? AND ?
                ORDER BY pmm.data_movimento, pmm.id_movimento, pmd.id_movimento_detalhe
                """;

        List<LinhaKardex> linhas = new ArrayList<>();
        BigDecimal[] saldoCorrido = {saldoInicial};
        jdbc.sql(sql)
                .params(idVariacao, idEmpresa, dataInicial, dataFinal)
                .query((rs, n) -> {
                    String tipo = rs.getString("tipo_movimento");
                    String creditoDebito = rs.getString("credito_debito");
                    BigDecimal qtd = rs.getBigDecimal("qtd_produto");
                    BigDecimal entrada = "C".equals(creditoDebito) ? qtd : BigDecimal.ZERO;
                    BigDecimal saida = "D".equals(creditoDebito) ? qtd : BigDecimal.ZERO;
                    saldoCorrido[0] = saldoCorrido[0].add(entrada).subtract(saida);
                    String documento = montarDocumento(
                            tipo, getLongOuNulo(rs, "id_venda"), getLongOuNulo(rs, "id_transferencia"),
                            getLongOuNulo(rs, "id_devolucao"), getLongOuNulo(rs, "nota_fiscal"),
                            rs.getString("nome_fornecedor"), rs.getString("origem"));
                    linhas.add(new LinhaKardex(
                            rs.getObject("data_movimento", OffsetDateTime.class), tipo, !TIPOS_NAO_FISICOS.contains(tipo),
                            documento, rs.getString("nome_funcionario"), entrada, saida, saldoCorrido[0]));
                    return null;
                })
                .list();

        CabecalhoKardex cabecalhoComSaldos = new CabecalhoKardex(
                cabecalho.idVariacao(), cabecalho.sku(), cabecalho.descricaoProduto(), cabecalho.marca(),
                cabecalho.variacaoLinha(), cabecalho.variacaoColuna(), cabecalho.idEmpresa(), cabecalho.nomeEmpresa(),
                saldoInicial, saldoCorrido[0]);

        return new RelatorioMovimentacaoProdutosResponse(
                ModeloRelatorioMovimentacao.KARDEX, null, null, null, cabecalhoComSaldos, linhas, null);
    }

    private CabecalhoKardex buscarCabecalhoKardex(long idVariacao, long idEmpresa) {
        String sql = """
                SELECT pb.id_variacao, pb.sku, p.descricao AS descricao_produto, p.marca,
                       vl.descricao AS variacao_linha, vc.descricao AS variacao_coluna,
                       emp.id_empresa, COALESCE(emp.nome_fantasia, emp.razao_social) AS nome_empresa
                FROM produto_barra pb
                JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                LEFT JOIN cfg_variante_linha vl ON vl.id_variante_linha = pb.id_variante_linha AND vl.id_tenant = pb.id_tenant
                LEFT JOIN cfg_variante_coluna vc ON vc.id_variante_coluna = pb.id_variante_coluna AND vc.id_tenant = pb.id_tenant
                JOIN empresa emp ON emp.id_tenant = pb.id_tenant AND emp.id_empresa = ?
                WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ?
                """;
        return jdbc.sql(sql)
                .params(idEmpresa, idVariacao)
                .query((rs, n) -> new CabecalhoKardex(
                        rs.getLong("id_variacao"), rs.getString("sku"), rs.getString("descricao_produto"), rs.getString("marca"),
                        rs.getString("variacao_linha"), rs.getString("variacao_coluna"),
                        rs.getLong("id_empresa"), rs.getString("nome_empresa"), null, null))
                .optional()
                .orElse(null);
    }

    private static Long getLongOuNulo(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
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
