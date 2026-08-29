package com.vetor.niner.vendas.relatoriocomissao;

import com.vetor.niner.vendas.relatoriocomissao.RelatorioComissoesDtos.LinhaComissao;
import com.vetor.niner.vendas.relatoriocomissao.RelatorioComissoesDtos.RelatorioComissoesResponse;
import com.vetor.niner.vendas.relatoriocomissao.RelatorioComissoesDtos.SubtotalEmpresa;
import com.vetor.niner.vendas.relatoriocomissao.RelatorioComissoesDtos.TotalGeralComissao;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relatório de Comissões — ver {@code package-info.java} pro resumo de escopo. Duas fontes
 * agregadas por (empresa, funcionário) — vendas (débito do ledger, {@code tipo_movimento =
 * 'VENDA'}) e devoluções (crédito do ledger, {@code tipo_movimento = 'DEVOLUCAO'}) — combinadas
 * por {@code FULL OUTER JOIN} pra cobrir tanto o funcionário que só vendeu quanto o que só
 * apareceu em devoluções (vendedor resolvido pelo número da venda na Devolução de Produtos,
 * ver {@code vendas.devolucao}) naquele período. {@code valorLiquido = valorVenda −
 * valorDevolucao}; {@code valorComissao = valorLiquido × percComissao/100}.
 */
@Service
public class RelatorioComissoesService {

    private static final int PERIODO_MAXIMO_DIAS = 400;

    // Allowlist de colunas ordenáveis (nunca string-concatenar a coluna vinda do cliente na SQL).
    // "nomeEmpresa" é tratado à parte em `montarOrdenacao`: o agrupamento por empresa (subtotal)
    // só funciona se ela continuar sendo o critério primário de ordenação, então a coluna
    // escolhida pelo usuário sempre entra como critério SECUNDÁRIO dentro de cada empresa (mesmo
    // truque do Relatório de Contas a Receber). valorLiquido/valorComissao não existem como
    // coluna no SELECT (são calculados em Java) — repete a expressão aqui pra poder ordenar.
    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.ofEntries(
            Map.entry("nomeEmpresa", "e.razao_social"),
            Map.entry("nomeFuncionario", "fn.nome"),
            Map.entry("valorVenda", "valor_venda"),
            Map.entry("valorDevolucao", "valor_devolucao"),
            Map.entry("valorLiquido", "(COALESCE(v.valor_venda, 0) - COALESCE(d.valor_devolucao, 0))"),
            // ⚠️ As duas passaram a apontar para a comissão SOMADA POR LINHA (V088). Antes
            // ordenavam por "líquido × percentual do cadastro", que deixou de ser o número
            // mostrado — a grade sairia ordenada por uma coluna que ela não exibe.
            Map.entry("percComissao",
                    "CASE WHEN (COALESCE(v.valor_venda, 0) - COALESCE(d.valor_devolucao, 0)) = 0 THEN fn.perc_comissao"
                            + " ELSE (COALESCE(v.comissao_venda, 0) - COALESCE(d.comissao_devolucao, 0))"
                            + " / (COALESCE(v.valor_venda, 0) - COALESCE(d.valor_devolucao, 0)) * 100 END"),
            Map.entry("valorComissao", "(COALESCE(v.comissao_venda, 0) - COALESCE(d.comissao_devolucao, 0))"),
            Map.entry("quantidadeVendas", "qtd_vendas"),
            Map.entry("ticketMedio",
                    "CASE WHEN COALESCE(qtd_vendas, 0) = 0 THEN 0"
                            + " ELSE (COALESCE(v.valor_venda, 0) - COALESCE(d.valor_devolucao, 0)) / qtd_vendas END"));

    private final JdbcClient jdbc;

    public RelatorioComissoesService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RelatorioComissoesResponse gerar(
            Jwt jwt, LocalDate dataInicial, LocalDate dataFinal, List<Long> idsEmpresaSolicitadas,
            String ordenarPor, String direcao) {
        validarPeriodo(dataInicial, dataFinal);
        List<Long> idsEmpresaEfetivo = resolverIdsEmpresa(jwt, idsEmpresaSolicitadas);

        List<LinhaComissao> linhas = buscarLinhas(dataInicial, dataFinal, idsEmpresaEfetivo, ordenarPor, direcao);

        List<SubtotalEmpresa> subtotais = calcularSubtotais(linhas);
        TotalGeralComissao totalGeral = calcularTotalGeral(linhas);

        return new RelatorioComissoesResponse(linhas, subtotais, totalGeral);
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

    /** Empresa: ADMIN filtra livremente (lista, vazia/nula = todas); OPERADOR sempre só a
     *  empresa ativa da sessão — mesmo padrão de {@code RelatorioVendasService}. */
    private List<Long> resolverIdsEmpresa(Jwt jwt, List<Long> idsEmpresaSolicitadas) {
        if (!ehAdmin(jwt)) {
            return List.of(idEmpresaSessao(jwt));
        }
        if (idsEmpresaSolicitadas == null || idsEmpresaSolicitadas.isEmpty()) {
            return null;
        }
        return idsEmpresaSolicitadas;
    }

    /**
     * Empresa é sempre o critério PRIMÁRIO de ordenação (asc, ou a direção escolhida se o
     * usuário clicar na própria coluna Empresa) — é o que mantém o agrupamento do subtotal por
     * empresa válido. Quando o resultado tem só uma empresa (filtro de 1 empresa, ou OPERADOR),
     * esse primário vira um no-op (todas as linhas compartilham o mesmo valor) e a coluna
     * escolhida pelo usuário (critério SECUNDÁRIO) manda de fato — sem coluna nenhuma escolhida
     * ainda (estado inicial da tela), cai no default histórico (nome do funcionário).
     */
    private List<LinhaComissao> buscarLinhas(
            LocalDate dataInicial, LocalDate dataFinal, List<Long> idsEmpresaEfetivo, String ordenarPor, String direcao) {
        List<Object> paramsEmpresa = new ArrayList<>();
        String placeholdersEmpresa = "";
        if (idsEmpresaEfetivo != null) {
            placeholdersEmpresa = String.join(",", idsEmpresaEfetivo.stream().map(id -> "?").toList());
            paramsEmpresa.addAll(idsEmpresaEfetivo);
        }
        String filtroEmpresaVendas = idsEmpresaEfetivo != null ? " AND v.id_empresa IN (" + placeholdersEmpresa + ")" : "";
        String filtroEmpresaDevolucoes = idsEmpresaEfetivo != null ? " AND pmm.id_empresa IN (" + placeholdersEmpresa + ")" : "";

        String sql = """
                -- ⭐ A comissão é somada POR LINHA desde a V088 (DS5), não mais aplicando um
                -- percentual único ao total do funcionário. Duas razões: (a) o serviço pode ter
                -- percentual próprio — o tosador ganha 20% do banho e 10% da tosa — e (b) o
                -- percentual usado é o CONGELADO no dia da venda, então editar a comissão do
                -- funcionário deixou de reescrever meses já pagos.
                -- ⚠️ O COALESCE com `fn.perc_comissao` é o histórico: linha anterior à V088 não
                -- tem percentual gravado, e para ela vale o comportamento antigo.
                WITH vendas AS (
                    SELECT v.id_empresa, pmd.id_funcionario,
                           SUM(pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto + pmd.valor_acrescimo) AS valor_venda,
                           SUM((pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto + pmd.valor_acrescimo)
                               * COALESCE(pmd.perc_comissao, fnc.perc_comissao) / 100) AS comissao_venda,
                           COUNT(DISTINCT v.id_venda) AS qtd_vendas
                    FROM venda v
                    JOIN produto_movimento_mestre pmm
                           ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant AND pmm.tipo_movimento = 'VENDA'
                    JOIN produto_movimento_detalhe pmd
                           ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant AND pmd.credito_debito = 'D'
                    JOIN funcionario fnc
                           ON fnc.id_funcionario = pmd.id_funcionario AND fnc.id_tenant = pmd.id_tenant
                    WHERE v.id_tenant = plataforma.tenant_atual() AND v.cancelada = false
                          AND (v.data_venda AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ? AND pmd.id_funcionario IS NOT NULL
                """
                + filtroEmpresaVendas + """

                    GROUP BY v.id_empresa, pmd.id_funcionario
                ),
                devolucoes AS (
                    SELECT pmm.id_empresa, pmd.id_funcionario,
                           -- ⭐ LÍQUIDO, igual à venda (auditoria 2026-08-29). O estorno somava o
                           -- bruto contra uma comissão que fora paga sobre o líquido: devolver uma
                           -- peça de R$ 100 vendida com R$ 30 de desconto tirava do vendedor mais
                           -- do que ele ganhou, e o mês podia fechar NEGATIVO.
                           SUM(pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto) AS valor_devolucao,
                           SUM((pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto)
                               * COALESCE(pmd.perc_comissao, fnc.perc_comissao) / 100) AS comissao_devolucao
                    FROM produto_movimento_mestre pmm
                    JOIN produto_movimento_detalhe pmd
                           ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant AND pmd.credito_debito = 'C'
                    JOIN funcionario fnc
                           ON fnc.id_funcionario = pmd.id_funcionario AND fnc.id_tenant = pmd.id_tenant
                    -- ⚠️ Devolução CANCELADA não pode ser descontada da comissão (achado de
                    -- auditoria, 2026-08-21). Cancelar uma devolução não apaga as linhas
                    -- `DEVOLUCAO` do ledger: ela só marca `venda_devolucao.cancelada` e lança um
                    -- movimento compensatório. Sem este filtro, o vendedor era pago a MENOS por uma
                    -- devolução que foi desfeita — e nada na tela apontava o motivo. É o mesmo
                    -- filtro que `DevolucaoProdutoService` já aplica no domínio.
                    JOIN venda_devolucao vd
                           ON vd.id_tenant = pmm.id_tenant AND vd.id_devolucao = pmm.id_devolucao
                          AND vd.cancelada = false
                    WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.tipo_movimento = 'DEVOLUCAO'
                          AND (pmm.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ? AND pmd.id_funcionario IS NOT NULL
                """
                + filtroEmpresaDevolucoes + """

                    GROUP BY pmm.id_empresa, pmd.id_funcionario
                )
                SELECT COALESCE(v.id_empresa, d.id_empresa) AS id_empresa, e.razao_social AS nome_empresa,
                       COALESCE(v.id_funcionario, d.id_funcionario) AS id_funcionario, fn.nome AS nome_funcionario,
                       COALESCE(v.valor_venda, 0) AS valor_venda, COALESCE(d.valor_devolucao, 0) AS valor_devolucao,
                       COALESCE(v.comissao_venda, 0) - COALESCE(d.comissao_devolucao, 0) AS valor_comissao,
                       fn.perc_comissao AS perc_comissao_funcionario, COALESCE(v.qtd_vendas, 0) AS qtd_vendas
                FROM vendas v
                FULL OUTER JOIN devolucoes d ON d.id_funcionario = v.id_funcionario AND d.id_empresa = v.id_empresa
                JOIN empresa e ON e.id_empresa = COALESCE(v.id_empresa, d.id_empresa) AND e.id_tenant = plataforma.tenant_atual()
                JOIN funcionario fn ON fn.id_funcionario = COALESCE(v.id_funcionario, d.id_funcionario) AND fn.id_tenant = plataforma.tenant_atual()
                """
                + montarOrdenacao(ordenarPor, direcao);

        List<Object> params = new ArrayList<>();
        params.add(dataInicial);
        params.add(dataFinal);
        params.addAll(paramsEmpresa);
        params.add(dataInicial);
        params.add(dataFinal);
        params.addAll(paramsEmpresa);

        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> {
                    BigDecimal valorVenda = rs.getBigDecimal("valor_venda");
                    BigDecimal valorDevolucao = rs.getBigDecimal("valor_devolucao");
                    BigDecimal valorLiquido = valorVenda.subtract(valorDevolucao);
                    // A comissão vem SOMADA POR LINHA do banco (V088/DS5) — o Java não a
                    // recalcula, só arredonda uma vez no fim (P7).
                    BigDecimal valorComissao = rs.getBigDecimal("valor_comissao")
                            .setScale(2, RoundingMode.HALF_UP);
                    // ⚠️ O percentual mostrado é o EFETIVO (comissão ÷ líquido), não mais o do
                    // cadastro do funcionário: com percentual por serviço, um número só do cadastro
                    // mentiria sobre uma linha que mistura 20% de banho com 10% de tosa. Quando
                    // todas as linhas têm o mesmo percentual, o efetivo é idêntico ao de antes.
                    BigDecimal percComissao = percentualEfetivo(valorComissao, valorLiquido,
                            rs.getBigDecimal("perc_comissao_funcionario"));
                    int quantidadeVendas = rs.getInt("qtd_vendas");
                    BigDecimal ticketMedio = calcularTicketMedio(valorLiquido, quantidadeVendas);
                    return new LinhaComissao(
                            rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                            rs.getLong("id_funcionario"), rs.getString("nome_funcionario"),
                            valorVenda, valorDevolucao, valorLiquido, percComissao, valorComissao,
                            quantidadeVendas, ticketMedio);
                })
                .list();
    }

    /**
     * Percentual efetivo = comissão ÷ líquido × 100.
     *
     * <p>⚠️ Líquido zero cai no percentual do cadastro em vez de dividir por zero — acontece de
     * verdade quando o funcionário só aparece por devolução no período, e mostrar "0%" ali daria a
     * entender que ele não tem comissão nenhuma cadastrada.
     */
    private static BigDecimal percentualEfetivo(BigDecimal comissao, BigDecimal liquido,
                                                BigDecimal percentualDoCadastro) {
        if (liquido.compareTo(BigDecimal.ZERO) == 0) {
            return percentualDoCadastro == null ? BigDecimal.ZERO : percentualDoCadastro;
        }
        return comissao.multiply(BigDecimal.valueOf(100))
                .divide(liquido, 2, RoundingMode.HALF_UP);
    }

    /** Ticket médio = valor líquido ÷ quantidade de vendas; zero quando não há venda no período
     *  (funcionário que só aparece por devolução) — nunca divide por zero. */
    private static BigDecimal calcularTicketMedio(BigDecimal valorLiquido, int quantidadeVendas) {
        return quantidadeVendas == 0
                ? BigDecimal.ZERO
                : valorLiquido.divide(BigDecimal.valueOf(quantidadeVendas), 2, RoundingMode.HALF_UP);
    }

    private static String montarOrdenacao(String ordenarPor, String direcao) {
        String direcaoSql = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";
        String colunaSql = ordenarPor == null ? null : COLUNAS_ORDENAVEIS.get(ordenarPor);
        String ordenacaoEmpresa = "nomeEmpresa".equals(ordenarPor) ? direcaoSql : "ASC";
        String ordenacaoSecundaria = (colunaSql == null || "nomeEmpresa".equals(ordenarPor))
                ? "fn.nome ASC"
                : colunaSql + " " + direcaoSql;
        return " ORDER BY e.razao_social " + ordenacaoEmpresa + ", " + ordenacaoSecundaria + ", fn.nome";
    }

    /** Preserva a ordem de primeira aparição (já vem ordenado por empresa) — não precisa
     *  reordenar de novo. */
    private static List<SubtotalEmpresa> calcularSubtotais(List<LinhaComissao> linhas) {
        record Acumulador(String nomeEmpresa, BigDecimal[] valores, int[] quantidadeVendas) {
        }
        LinkedHashMap<Long, Acumulador> porEmpresa = new LinkedHashMap<>();
        for (LinhaComissao l : linhas) {
            Acumulador acc = porEmpresa.computeIfAbsent(l.idEmpresa(),
                    k -> new Acumulador(l.nomeEmpresa(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}, new int[]{0}));
            acc.valores()[0] = acc.valores()[0].add(l.valorVenda());
            acc.valores()[1] = acc.valores()[1].add(l.valorDevolucao());
            acc.valores()[2] = acc.valores()[2].add(l.valorLiquido());
            acc.valores()[3] = acc.valores()[3].add(l.valorComissao());
            acc.quantidadeVendas()[0] += l.quantidadeVendas();
        }
        List<SubtotalEmpresa> subtotais = new ArrayList<>();
        porEmpresa.forEach((idEmpresa, acc) -> subtotais.add(new SubtotalEmpresa(
                idEmpresa, acc.nomeEmpresa(), acc.valores()[0], acc.valores()[1], acc.valores()[2], acc.valores()[3],
                acc.quantidadeVendas()[0], calcularTicketMedio(acc.valores()[2], acc.quantidadeVendas()[0]))));
        return subtotais;
    }

    private static TotalGeralComissao calcularTotalGeral(List<LinhaComissao> linhas) {
        BigDecimal valorVenda = BigDecimal.ZERO;
        BigDecimal valorDevolucao = BigDecimal.ZERO;
        BigDecimal valorLiquido = BigDecimal.ZERO;
        BigDecimal valorComissao = BigDecimal.ZERO;
        int quantidadeVendas = 0;
        for (LinhaComissao l : linhas) {
            valorVenda = valorVenda.add(l.valorVenda());
            valorDevolucao = valorDevolucao.add(l.valorDevolucao());
            valorLiquido = valorLiquido.add(l.valorLiquido());
            valorComissao = valorComissao.add(l.valorComissao());
            quantidadeVendas += l.quantidadeVendas();
        }
        return new TotalGeralComissao(
                valorVenda, valorDevolucao, valorLiquido, valorComissao,
                quantidadeVendas, calcularTicketMedio(valorLiquido, quantidadeVendas));
    }

    private static boolean ehAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }

    private static long idEmpresaSessao(Jwt jwt) {
        return ((Number) jwt.getClaim("eid")).longValue();
    }
}
