package com.vetor.niner.vendas.relatorio;

import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.ComposicaoFaturamento;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.DetalheTotalizadorResponse;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.Graficos;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.Kpis;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.LinhaAgrupada;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.LinhaAnalitica;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.LinhaCarteiraGrafico;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.PontoGrafico;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.RelatorioVendasResponse;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.Totalizador;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.TotalizarPor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Relatório de Vendas — ver {@code package-info.java} pro resumo de escopo. Dataset-base é 1
 * linha por venda (não cancelada, dentro do filtro), montado numa única query agrupada; todo o
 * resto (KPIs, composição, gráficos "por venda", totalizador, drill-down) é calculado em Java a
 * partir dessa lista, sem round-trip extra ao banco. Top 10 Marcas e "por carteira" têm
 * granularidade diferente (item do ledger e parcela de recebimento) — duas queries agregadas à
 * parte, reaplicando os mesmos filtros de período/empresa/vendedor.
 */
@Service
public class RelatorioVendasService {

    private static final int PERIODO_MAXIMO_DIAS = 400;
    private static final String[] DIAS_SEMANA_PT =
            {"Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado", "Domingo"};

    private final JdbcClient jdbc;

    public RelatorioVendasService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RelatorioVendasResponse gerar(Jwt jwt, LocalDate dataInicial, LocalDate dataFinal,
                                          List<Long> idsEmpresa, Long idFuncionario, TotalizarPor totalizarPor) {
        validarPeriodo(dataInicial, dataFinal);
        Filtro filtro = construirFiltro(jwt, dataInicial, dataFinal, idsEmpresa, idFuncionario);
        List<VendaAgregada> linhas = buscarLinhasBase(filtro);

        Kpis kpis = calcularKpis(linhas, filtro);
        ComposicaoFaturamento composicao = calcularComposicao(linhas, kpis);
        Graficos graficos = calcularGraficos(linhas, filtro, dataInicial, dataFinal);
        Totalizador totalizador = montarTotalizador(linhas, totalizarPor);

        return new RelatorioVendasResponse(kpis, composicao, graficos, totalizador);
    }

    @Transactional(readOnly = true)
    public DetalheTotalizadorResponse buscarDetalheGrupo(Jwt jwt, LocalDate dataInicial, LocalDate dataFinal,
                                                           List<Long> idsEmpresa, Long idFuncionario,
                                                           TotalizarPor totalizarPor, String chave) {
        validarPeriodo(dataInicial, dataFinal);
        if (totalizarPor == null || totalizarPor == TotalizarPor.NAO_TOTALIZAR) {
            throw new IllegalArgumentException("Detalhamento só se aplica quando há um totalizador aplicado.");
        }
        Filtro filtro = construirFiltro(jwt, dataInicial, dataFinal, idsEmpresa, idFuncionario);
        List<VendaAgregada> linhas = buscarLinhasBase(filtro);
        List<LinhaAnalitica> itens = linhas.stream()
                .filter(l -> chaveGrupo(l, totalizarPor).equals(chave))
                .map(RelatorioVendasService::paraAnalitica)
                .toList();
        return new DetalheTotalizadorResponse(itens);
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

    // --- Dataset-base (1 linha por venda) ------------------------------------------------

    /** Vendedor = {@code venda.id_funcionario} (V089), mesmo critério de
     *  {@code PesquisaVendaService}. ⚠️ Era {@code MAX(pmd.id_funcionario)} — o ledger deixou de
     *  servir na V088, quando cada linha passou a carregar quem EXECUTOU aquele item. Operador de caixa =
     *  usuário dono da sessão de caixa que recebeu o pagamento da venda, via {@code LATERAL}
     *  (evita multiplicar as linhas de item quando há mais de um lançamento de caixa por venda,
     *  caso de split-tender). */
    private record VendaAgregada(
            long idVenda, long idEmpresa, String nomeEmpresa, OffsetDateTime dataVenda,
            Long idCliente, String nomeCliente, Long idFuncionario, String nomeFuncionario,
            Long idOperador, String nomeOperador, BigDecimal qtdItens, BigDecimal valorBruto,
            BigDecimal valorDesconto, BigDecimal valorAcrescimo) {

        BigDecimal valorLiquido() {
            return valorBruto.subtract(valorDesconto).add(valorAcrescimo);
        }
    }

    /** Recorte de data do relatório, sempre no fuso da loja (nunca o do banco — ver
     *  {@code feedback_data_fuso_loja_nao_do_banco}). Fica em constante porque
     *  {@link Filtro#clausulaPorDataDeMovimento()} a troca por texto. */
    private static final String PREDICADO_DATA_DA_VENDA =
            "(v.data_venda AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?";
    private static final String PREDICADO_DATA_DO_MOVIMENTO =
            "(pmm.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?";

    private record Filtro(String clausula, List<Object> params) {

        /**
         * A mesma cláusula, mas recortando pela data do <b>movimento</b> em vez da data da venda.
         *
         * <p>Existe para a devolução (2026-08-22, auditoria item 6): ela acontece semanas ou meses
         * depois da venda que a originou, e recortá-la pela data da venda fazia duas coisas
         * erradas. A devolução caía no relatório do mês da <b>venda</b>, enquanto o DRE e as
         * Comissões a colocam no mês do <b>movimento</b> — dois relatórios do mesmo sistema
         * discordando do mesmo fato. E, como o KPI é subtraído da venda líquida, o faturamento de
         * um mês já fechado <b>mudava sozinho</b> meses depois, sem nada indicar por quê.
         *
         * <p>Decisão do dono do produto: <i>"valores fechados do mês não podem ser mudados; a
         * devolução tem que entrar no mês da devolução"</i>. Os demais filtros (empresa, vendedor)
         * continuam valendo sobre a venda de origem, que é o que se quer.
         *
         * <p>A troca é textual mas segura: as duas datas são os <b>dois primeiros</b> parâmetros da
         * lista, e o predicado substituído tem exatamente os mesmos dois {@code ?}, então a ordem
         * de {@link #params()} não muda.
         */
        String clausulaPorDataDeMovimento() {
            return clausula.replace(PREDICADO_DATA_DA_VENDA, PREDICADO_DATA_DO_MOVIMENTO);
        }
    }

    private List<VendaAgregada> buscarLinhasBase(Filtro filtro) {
        String sql = """
                SELECT v.id_venda, v.id_empresa, e.razao_social AS nome_empresa, v.data_venda,
                       v.id_cliente, cli.nome AS nome_cliente,
                       v.id_funcionario, fn.nome AS nome_funcionario,
                       MAX(op.id_usuario) AS id_operador, MAX(op.nome_usuario) AS nome_operador,
                       COALESCE(SUM(pmd.qtd_produto), 0) AS qtd_itens,
                       COALESCE(SUM(pmd.qtd_produto * pmd.preco_venda), 0) AS valor_bruto,
                       COALESCE(SUM(pmd.valor_desconto), 0) AS valor_desconto,
                       COALESCE(SUM(pmd.valor_acrescimo), 0) AS valor_acrescimo
                FROM venda v
                JOIN empresa e ON e.id_empresa = v.id_empresa AND e.id_tenant = v.id_tenant
                LEFT JOIN cliente cli ON cli.id_cliente = v.id_cliente AND cli.id_tenant = v.id_tenant
                LEFT JOIN produto_movimento_mestre pmm
                       ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant AND pmm.tipo_movimento = 'VENDA'
                LEFT JOIN produto_movimento_detalhe pmd
                       ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant AND pmd.credito_debito = 'D'
                LEFT JOIN funcionario fn ON fn.id_funcionario = v.id_funcionario AND fn.id_tenant = v.id_tenant
                LEFT JOIN LATERAL (
                    SELECT u2.id_usuario, u2.nome_usuario
                    FROM caixa_detalhe cd2
                    JOIN caixa_mestre cm2 ON cm2.id_caixa = cd2.id_caixa AND cm2.id_tenant = cd2.id_tenant
                    JOIN usuario u2 ON u2.id_usuario = cm2.id_usuario AND u2.id_tenant = cm2.id_tenant
                    WHERE cd2.id_tenant = v.id_tenant AND cd2.id_venda = v.id_venda AND cd2.tipo_operacao = 'RECEBIMENTO_VENDA'
                    ORDER BY cd2.criado_em
                    LIMIT 1
                ) op ON true
                """
                + filtro.clausula()
                + " GROUP BY v.id_venda, v.id_empresa, e.razao_social, v.data_venda, v.id_cliente, cli.nome,"
                + " v.id_funcionario, fn.nome"
                + " ORDER BY v.data_venda";

        return jdbc.sql(sql)
                .params(filtro.params())
                .query((rs, n) -> new VendaAgregada(
                        rs.getLong("id_venda"), rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                        rs.getObject("data_venda", OffsetDateTime.class),
                        getLongOuNulo(rs, "id_cliente"), rs.getString("nome_cliente"),
                        getLongOuNulo(rs, "id_funcionario"), rs.getString("nome_funcionario"),
                        getLongOuNulo(rs, "id_operador"), rs.getString("nome_operador"),
                        rs.getBigDecimal("qtd_itens"), rs.getBigDecimal("valor_bruto"),
                        rs.getBigDecimal("valor_desconto"), rs.getBigDecimal("valor_acrescimo")))
                .list();
    }

    /** Empresa: ADMIN filtra livremente (lista, vazia/nula = todas); OPERADOR sempre só a
     *  empresa ativa da sessão (claim {@code eid}), ignorando o que foi enviado — mesmo espírito
     *  de {@code PesquisaVendaService}/{@code CaixaService}. Vendedor reaplica o mesmo
     *  {@code EXISTS} de item já usado na Pesquisa de Vendas. */
    private Filtro construirFiltro(Jwt jwt, LocalDate dataInicial, LocalDate dataFinal,
                                    List<Long> idsEmpresaSolicitadas, Long idFuncionario) {
        StringBuilder sb = new StringBuilder(
                " WHERE v.id_tenant = plataforma.tenant_atual() AND v.cancelada = false"
                        + " AND " + PREDICADO_DATA_DA_VENDA);
        List<Object> params = new ArrayList<>();
        params.add(dataInicial);
        params.add(dataFinal);

        List<Long> idsEmpresaEfetivo = resolverIdsEmpresa(jwt, idsEmpresaSolicitadas);
        if (idsEmpresaEfetivo != null) {
            String placeholders = String.join(",", idsEmpresaEfetivo.stream().map(id -> "?").toList());
            sb.append(" AND v.id_empresa IN (").append(placeholders).append(")");
            params.addAll(idsEmpresaEfetivo);
        }

        if (idFuncionario != null) {
            sb.append("""
                     AND EXISTS (
                         SELECT 1 FROM produto_movimento_mestre pmm2
                         JOIN produto_movimento_detalhe pmd2
                                ON pmd2.id_movimento = pmm2.id_movimento AND pmd2.id_tenant = pmm2.id_tenant
                         WHERE pmm2.id_tenant = v.id_tenant AND pmm2.id_venda = v.id_venda
                               AND pmm2.tipo_movimento = 'VENDA' AND pmd2.id_funcionario = ?
                     )
                    """);
            params.add(idFuncionario);
        }

        return new Filtro(sb.toString(), params);
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

    // --- KPIs / composição -----------------------------------------------------------------

    private Kpis calcularKpis(List<VendaAgregada> linhas, Filtro filtro) {
        long nVendas = linhas.size();
        BigDecimal somaBruto = somar(linhas, VendaAgregada::valorBruto);
        BigDecimal somaDesconto = somar(linhas, VendaAgregada::valorDesconto);
        BigDecimal somaLiquido = somar(linhas, VendaAgregada::valorLiquido);
        BigDecimal itensVendidos = somar(linhas, VendaAgregada::qtdItens);
        BigDecimal valorDevolucao = buscarValorDevolucao(filtro);

        BigDecimal ticketMedioValor = nVendas == 0 ? BigDecimal.ZERO
                : somaLiquido.divide(BigDecimal.valueOf(nVendas), 2, RoundingMode.HALF_UP);
        BigDecimal percentualMedioDesconto = percentual(somaDesconto, somaBruto);
        BigDecimal percentualDevolucao = percentual(valorDevolucao, somaBruto);
        BigDecimal mediaItensPorVenda = nVendas == 0 ? BigDecimal.ZERO
                : itensVendidos.divide(BigDecimal.valueOf(nVendas), 3, RoundingMode.HALF_UP);

        return new Kpis(ticketMedioValor, nVendas, percentualMedioDesconto, somaDesconto,
                percentualDevolucao, valorDevolucao, itensVendidos, mediaItensPorVenda);
    }

    private ComposicaoFaturamento calcularComposicao(List<VendaAgregada> linhas, Kpis kpis) {
        BigDecimal somaBruto = somar(linhas, VendaAgregada::valorBruto);
        BigDecimal somaDesconto = somar(linhas, VendaAgregada::valorDesconto);
        BigDecimal somaAcrescimo = somar(linhas, VendaAgregada::valorAcrescimo);
        BigDecimal vendaLiquida = somaBruto.subtract(somaDesconto).add(somaAcrescimo).subtract(kpis.valorDevolucao());
        return new ComposicaoFaturamento(somaBruto, somaDesconto, somaAcrescimo, kpis.valorDevolucao(), vendaLiquida);
    }

    private static BigDecimal percentual(BigDecimal parte, BigDecimal total) {
        if (total.signum() == 0) return BigDecimal.ZERO;
        return parte.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal somar(List<VendaAgregada> linhas, Function<VendaAgregada, BigDecimal> extrator) {
        return linhas.stream().map(extrator).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Valor devolvido no período — <b>pela data da devolução</b>, não pela data da venda.
     *
     * <p>⚠️ Este javadoc já disse <i>"sempre 0 hoje — nenhuma tela cria movimento de devolução
     * ainda"</i>, e isso deixou de ser verdade em <b>2026-08-19</b>, quando a Devolução de Produtos
     * entrou. O KPI trouxe número real por dias com o comentário afirmando o contrário.
     *
     * <p>Duas correções em 2026-08-22 (auditoria, item 6):
     * <ol>
     *   <li>{@link Filtro#clausulaPorDataDeMovimento()} em vez de {@code clausula()} — ver o
     *       javadoc de lá para o porquê (mês fechado que mudava sozinho, e discordância com o DRE
     *       e as Comissões, que sempre usaram {@code data_movimento});</li>
     *   <li>{@code vd.cancelada = false}, que o DRE já tinha: cancelar uma devolução <b>não</b>
     *       apaga as linhas {@code DEVOLUCAO} do ledger — só marca
     *       {@code venda_devolucao.cancelada} e lança o movimento de estorno. Sem o filtro, uma
     *       devolução cancelada seguia abatendo a venda líquida.</li>
     * </ol>
     */
    private BigDecimal buscarValorDevolucao(Filtro filtro) {
        String sql = """
                SELECT COALESCE(SUM(ABS(pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto + pmd.valor_acrescimo)), 0) AS valor
                FROM venda v
                JOIN produto_movimento_mestre pmm
                       ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant AND pmm.tipo_movimento = 'DEVOLUCAO'
                JOIN produto_movimento_detalhe pmd
                       ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                JOIN venda_devolucao vd
                       ON vd.id_devolucao = pmm.id_devolucao AND vd.id_tenant = pmm.id_tenant
                      AND vd.cancelada = false
                """
                + filtro.clausulaPorDataDeMovimento();
        return jdbc.sql(sql).params(filtro.params()).query(BigDecimal.class).single();
    }

    // --- Gráficos ----------------------------------------------------------------------------

    private Graficos calcularGraficos(List<VendaAgregada> linhas, Filtro filtro, LocalDate dataInicial, LocalDate dataFinal) {
        List<PontoGrafico> porDia = zeroFillPorDia(linhas, dataInicial, dataFinal);
        List<PontoGrafico> porHora = zeroFillPorHora(linhas);
        List<PontoGrafico> porDiaSemana = zeroFillPorDiaSemana(linhas);
        List<PontoGrafico> topVendedores = topN(linhas, l -> l.idFuncionario() == null ? "Sem vendedor" : l.nomeFuncionario());
        List<PontoGrafico> topClientes = topN(linhas, l -> l.idCliente() == null ? "Consumidor Final" : l.nomeCliente());
        List<PontoGrafico> topMarcas = buscarTopMarcas(filtro);
        List<LinhaCarteiraGrafico> porCarteira = buscarPorCarteira(filtro);
        return new Graficos(porDia, topMarcas, topVendedores, topClientes, porCarteira, porHora, porDiaSemana);
    }

    private static List<PontoGrafico> zeroFillPorDia(List<VendaAgregada> linhas, LocalDate dataInicial, LocalDate dataFinal) {
        Map<LocalDate, BigDecimal> porData = linhas.stream().collect(Collectors.groupingBy(
                l -> l.dataVenda().toLocalDate(),
                Collectors.reducing(BigDecimal.ZERO, VendaAgregada::valorLiquido, BigDecimal::add)));
        List<PontoGrafico> pontos = new ArrayList<>();
        for (LocalDate d = dataInicial; !d.isAfter(dataFinal); d = d.plusDays(1)) {
            pontos.add(new PontoGrafico(d.toString(), porData.getOrDefault(d, BigDecimal.ZERO)));
        }
        return pontos;
    }

    private static List<PontoGrafico> zeroFillPorHora(List<VendaAgregada> linhas) {
        Map<Integer, BigDecimal> porHora = linhas.stream().collect(Collectors.groupingBy(
                l -> l.dataVenda().getHour(),
                Collectors.reducing(BigDecimal.ZERO, VendaAgregada::valorLiquido, BigDecimal::add)));
        List<PontoGrafico> pontos = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            pontos.add(new PontoGrafico(String.format("%02d", h), porHora.getOrDefault(h, BigDecimal.ZERO)));
        }
        return pontos;
    }

    private static List<PontoGrafico> zeroFillPorDiaSemana(List<VendaAgregada> linhas) {
        Map<DayOfWeek, BigDecimal> porDia = linhas.stream().collect(Collectors.groupingBy(
                l -> l.dataVenda().getDayOfWeek(),
                Collectors.reducing(BigDecimal.ZERO, VendaAgregada::valorLiquido, BigDecimal::add)));
        List<PontoGrafico> pontos = new ArrayList<>();
        for (DayOfWeek dia : DayOfWeek.values()) {
            pontos.add(new PontoGrafico(DIAS_SEMANA_PT[dia.getValue() - 1], porDia.getOrDefault(dia, BigDecimal.ZERO)));
        }
        return pontos;
    }

    private static List<PontoGrafico> topN(List<VendaAgregada> linhas, Function<VendaAgregada, String> rotulo) {
        Map<String, BigDecimal> somas = linhas.stream().collect(Collectors.groupingBy(rotulo,
                Collectors.reducing(BigDecimal.ZERO, VendaAgregada::valorLiquido, BigDecimal::add)));
        return somas.entrySet().stream()
                .map(e -> new PontoGrafico(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(PontoGrafico::valor).reversed())
                .limit(10)
                .toList();
    }

    /** Granularidade de item (não de venda) — uma venda pode ter produtos de marcas diferentes. */
    private List<PontoGrafico> buscarTopMarcas(Filtro filtro) {
        String sql = """
                SELECT COALESCE(p.marca, 'Sem marca') AS rotulo,
                       SUM(pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto + pmd.valor_acrescimo) AS valor
                FROM venda v
                JOIN produto_movimento_mestre pmm
                       ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant AND pmm.tipo_movimento = 'VENDA'
                JOIN produto_movimento_detalhe pmd
                       ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant AND pmd.credito_debito = 'D'
                JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                """
                + filtro.clausula()
                + " GROUP BY COALESCE(p.marca, 'Sem marca') ORDER BY valor DESC LIMIT 10";
        return jdbc.sql(sql).params(filtro.params())
                .query((rs, n) -> new PontoGrafico(rs.getString("rotulo"), rs.getBigDecimal("valor")))
                .list();
    }

    /** Mix de forma de pagamento das vendas do período (por {@code data_venda}, não por data de
     *  recebimento) — inclui crediário mesmo que a parcela ainda não tenha sido recebida. Todos
     *  os tipos de carteira com algum recebível no período, sem limite de 10. Agrupado por
     *  {@code (nome_carteira, categoria_carteira)}, não só nome — a mesma bandeira pode existir
     *  uma vez por categoria (ex.: "HIPER" em Cartão Débito e em Cartão Crédito, cada uma com
     *  saldo próprio) e precisam aparecer como barras separadas (mesmo motivo de
     *  `categoriaCarteira` no Fechamento de Caixa, 2026-07-31). */
    private List<LinhaCarteiraGrafico> buscarPorCarteira(Filtro filtro) {
        String sql = """
                SELECT tc.nome_carteira, tc.categoria_carteira::text AS categoria_carteira, SUM(cr.valor_receber) AS valor
                FROM venda v
                JOIN contas_receber cr ON cr.id_venda = v.id_venda AND cr.id_tenant = v.id_tenant
                JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                """
                + filtro.clausula()
                + " GROUP BY tc.nome_carteira, tc.categoria_carteira ORDER BY valor DESC";
        return jdbc.sql(sql).params(filtro.params())
                .query((rs, n) -> new LinhaCarteiraGrafico(
                        rs.getString("nome_carteira"), rs.getString("categoria_carteira"), rs.getBigDecimal("valor")))
                .list();
    }

    // --- Totalizador / drill-down ------------------------------------------------------------

    private Totalizador montarTotalizador(List<VendaAgregada> linhas, TotalizarPor totalizarPor) {
        if (totalizarPor == null || totalizarPor == TotalizarPor.NAO_TOTALIZAR) {
            List<LinhaAnalitica> analiticas = linhas.stream().map(RelatorioVendasService::paraAnalitica).toList();
            return new Totalizador("ANALITICO", null, analiticas);
        }
        Map<String, List<VendaAgregada>> grupos = linhas.stream()
                .collect(Collectors.groupingBy(l -> chaveGrupo(l, totalizarPor)));
        List<LinhaAgrupada> agrupadas = grupos.values().stream()
                .map(grupo -> new LinhaAgrupada(
                        chaveGrupo(grupo.get(0), totalizarPor), nomeGrupo(grupo.get(0), totalizarPor),
                        grupo.size(), somar(grupo, VendaAgregada::valorLiquido)))
                .sorted(Comparator.comparing(LinhaAgrupada::valorVenda).reversed())
                .toList();
        return new Totalizador("AGRUPADO", agrupadas, null);
    }

    private static String chaveGrupo(VendaAgregada l, TotalizarPor totalizarPor) {
        return switch (totalizarPor) {
            case DATA_VENDA -> l.dataVenda().toLocalDate().toString();
            case CLIENTE -> l.idCliente() == null ? "SEM_CLIENTE" : String.valueOf(l.idCliente());
            case VENDEDOR -> l.idFuncionario() == null ? "SEM_VENDEDOR" : String.valueOf(l.idFuncionario());
            case OPERADOR_CAIXA -> l.idOperador() == null ? "SEM_OPERADOR" : String.valueOf(l.idOperador());
            case EMPRESA -> String.valueOf(l.idEmpresa());
            case NAO_TOTALIZAR -> "";
        };
    }

    private static String nomeGrupo(VendaAgregada l, TotalizarPor totalizarPor) {
        return switch (totalizarPor) {
            case DATA_VENDA -> l.dataVenda().toLocalDate().toString();
            case CLIENTE -> l.idCliente() == null ? "Consumidor Final" : l.nomeCliente();
            case VENDEDOR -> l.idFuncionario() == null ? "Sem vendedor" : l.nomeFuncionario();
            case OPERADOR_CAIXA -> l.idOperador() == null ? "Sem operador" : l.nomeOperador();
            case EMPRESA -> l.nomeEmpresa();
            case NAO_TOTALIZAR -> "";
        };
    }

    private static LinhaAnalitica paraAnalitica(VendaAgregada l) {
        return new LinhaAnalitica(l.idVenda(), l.idEmpresa(), l.nomeEmpresa(), l.dataVenda(),
                l.nomeCliente(), l.nomeFuncionario(), l.nomeOperador(), l.qtdItens(),
                l.valorBruto(), l.valorAcrescimo(), l.valorDesconto(), l.valorLiquido());
    }

    // --- Helpers compartilhados (mesmo padrão de PesquisaVendaService) ----------------------

    private static Long getLongOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    private static boolean ehAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }

    private static long idEmpresaSessao(Jwt jwt) {
        return ((Number) jwt.getClaim("eid")).longValue();
    }
}
