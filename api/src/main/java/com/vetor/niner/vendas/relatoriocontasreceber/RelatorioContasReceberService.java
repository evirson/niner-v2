package com.vetor.niner.vendas.relatoriocontasreceber;

import com.vetor.niner.vendas.relatoriocontasreceber.RelatorioContasReceberDtos.LinhaContaReceber;
import com.vetor.niner.vendas.relatoriocontasreceber.RelatorioContasReceberDtos.RelatorioContasReceberResponse;
import com.vetor.niner.vendas.relatoriocontasreceber.RelatorioContasReceberDtos.SubtotalEmpresaContaReceber;
import com.vetor.niner.vendas.relatoriocontasreceber.RelatorioContasReceberDtos.TotalGeralContaReceber;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relatório de Contas a Receber / Recebidas — ver {@code package-info.java} pro resumo de
 * escopo. Uma linha por parcela (não agregada), categorias CARTAO_DEBITO/CARTAO_CREDITO/
 * CREDIARIO. Três períodos independentes e opcionais (venda/vencimento/recebimento) — pelo menos
 * um precisa vir completo (início + fim). Sem vendedor (removido a pedido do dono do produto,
 * 2026-08-03 — este relatório é sobre a parcela, não sobre quem vendeu).
 */
@Service
public class RelatorioContasReceberService {

    private static final int PERIODO_MAXIMO_DIAS = 400;

    // Allowlist de colunas ordenáveis (nunca string-concatenar a coluna vinda do cliente na SQL —
    // mesmo padrão de PesquisaVendaService). "nomeEmpresa" é tratado à parte em `buscarLinhas`:
    // o agrupamento por empresa (subtotal) só funciona se ela continuar sendo o critério primário
    // de ordenação, então a coluna escolhida pelo usuário sempre entra como critério SECUNDÁRIO
    // dentro de cada empresa (mesmo truque do Relatório de Comissões: com uma única empresa no
    // resultado, o critério primário vira um no-op e a coluna escolhida manda de fato).
    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.ofEntries(
            Map.entry("nomeEmpresa", "e.razao_social"),
            Map.entry("idVenda", "cr.id_venda"),
            Map.entry("nomeCliente", "cli.nome"),
            Map.entry("nomeCarteira", "tc.nome_carteira"),
            Map.entry("numeroParcela", "cr.numero_parcela"),
            Map.entry("dataVenda", "v.data_venda"),
            Map.entry("dataVencimento", "cr.data_vencimento"),
            Map.entry("dataRecebimento", "cr.data_recebimento"),
            Map.entry("nomeEmpresaPagamento", "ep.razao_social"),
            Map.entry("valorBruto", "cr.valor_receber"),
            Map.entry("taxaAdministrativa", "tc.taxa_administradora"),
            Map.entry("valorLiquido", "(cr.valor_receber - cr.valor_receber * COALESCE(tc.taxa_administradora, 0) / 100)"));

    private final JdbcClient jdbc;

    public RelatorioContasReceberService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public enum StatusParcela {
        ABERTO, RECEBIDA
    }

    public enum CategoriaParcela {
        CARTAO_DEBITO, CARTAO_CREDITO, CREDIARIO
    }

    public record FiltrosPeriodo(
            LocalDate dataVendaInicial, LocalDate dataVendaFinal,
            LocalDate dataVencimentoInicial, LocalDate dataVencimentoFinal,
            LocalDate dataRecebimentoInicial, LocalDate dataRecebimentoFinal) {
    }

    @Transactional(readOnly = true)
    public RelatorioContasReceberResponse gerar(
            Jwt jwt, FiltrosPeriodo periodos, List<Long> idsEmpresaSolicitadas, StatusParcela status,
            CategoriaParcela categoria, String ordenarPor, String direcao) {
        validarPeriodo("venda", periodos.dataVendaInicial(), periodos.dataVendaFinal());
        validarPeriodo("vencimento", periodos.dataVencimentoInicial(), periodos.dataVencimentoFinal());
        validarPeriodo("recebimento", periodos.dataRecebimentoInicial(), periodos.dataRecebimentoFinal());
        if (periodos.dataVendaInicial() == null && periodos.dataVencimentoInicial() == null && periodos.dataRecebimentoInicial() == null) {
            throw new IllegalArgumentException(
                    "Informe ao menos um período (venda, vencimento ou recebimento).");
        }

        List<Long> idsEmpresaEfetivo = resolverIdsEmpresa(jwt, idsEmpresaSolicitadas);
        List<LinhaContaReceber> linhas = buscarLinhas(periodos, idsEmpresaEfetivo, status, categoria, ordenarPor, direcao);

        List<SubtotalEmpresaContaReceber> subtotais = calcularSubtotais(linhas);
        TotalGeralContaReceber totalGeral = calcularTotalGeral(linhas);

        return new RelatorioContasReceberResponse(linhas, subtotais, totalGeral);
    }

    private void validarPeriodo(String nome, LocalDate inicial, LocalDate fim) {
        if (inicial == null && fim == null) {
            return;
        }
        if (inicial == null || fim == null) {
            throw new IllegalArgumentException("Informe início e fim do período de " + nome + ".");
        }
        if (inicial.isAfter(fim)) {
            throw new IllegalArgumentException("Data inicial de " + nome + " não pode ser maior que a final.");
        }
        if (inicial.plusDays(PERIODO_MAXIMO_DIAS).isBefore(fim)) {
            throw new IllegalArgumentException("Período de " + nome + " não pode exceder " + PERIODO_MAXIMO_DIAS + " dias.");
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

    private List<LinhaContaReceber> buscarLinhas(
            FiltrosPeriodo periodos, List<Long> idsEmpresaEfetivo, StatusParcela status, CategoriaParcela categoria,
            String ordenarPor, String direcao) {
        StringBuilder filtro = new StringBuilder(
                " WHERE cr.id_tenant = plataforma.tenant_atual() AND v.cancelada = false"
                        + " AND tc.categoria_carteira IN ('CARTAO_DEBITO', 'CARTAO_CREDITO', 'CREDIARIO')");
        List<Object> params = new ArrayList<>();

        if (periodos.dataVendaInicial() != null) {
            filtro.append(" AND v.data_venda::date BETWEEN ? AND ?");
            params.add(periodos.dataVendaInicial());
            params.add(periodos.dataVendaFinal());
        }
        if (periodos.dataVencimentoInicial() != null) {
            filtro.append(" AND cr.data_vencimento::date BETWEEN ? AND ?");
            params.add(periodos.dataVencimentoInicial());
            params.add(periodos.dataVencimentoFinal());
        }
        if (periodos.dataRecebimentoInicial() != null) {
            filtro.append(" AND cr.data_recebimento::date BETWEEN ? AND ?");
            params.add(periodos.dataRecebimentoInicial());
            params.add(periodos.dataRecebimentoFinal());
        }
        if (status == StatusParcela.ABERTO) {
            filtro.append(" AND cr.data_recebimento IS NULL");
        } else if (status == StatusParcela.RECEBIDA) {
            filtro.append(" AND cr.data_recebimento IS NOT NULL");
        }
        if (categoria != null) {
            filtro.append(" AND tc.categoria_carteira::text = ?");
            params.add(categoria.name());
        }
        if (idsEmpresaEfetivo != null) {
            String placeholders = String.join(",", idsEmpresaEfetivo.stream().map(id -> "?").toList());
            filtro.append(" AND v.id_empresa IN (").append(placeholders).append(")");
            params.addAll(idsEmpresaEfetivo);
        }

        // total_parcelas: subconsulta correlacionada (não window function) de propósito — precisa
        // sempre do total VERDADEIRO da linha de pagamento (mesma venda + mesmo tipo_carteira),
        // independente dos filtros de período/status aplicados no WHERE de fora (uma parcela 3/3
        // continua sendo "3/3" mesmo filtrando só quem já foi recebido, por exemplo).
        String sql = """
                SELECT v.id_empresa, e.razao_social AS nome_empresa, ep.razao_social AS nome_empresa_pagamento,
                       cr.id_venda, v.id_cliente, cli.nome AS nome_cliente,
                       tc.nome_carteira, tc.categoria_carteira::text AS categoria_carteira,
                       cr.numero_parcela,
                       (SELECT COUNT(*) FROM contas_receber cr2
                        WHERE cr2.id_tenant = cr.id_tenant AND cr2.id_venda = cr.id_venda AND cr2.id_carteira = cr.id_carteira
                       ) AS total_parcelas,
                       v.data_venda, cr.data_vencimento, cr.data_recebimento,
                       cr.valor_receber AS valor_bruto, COALESCE(tc.taxa_administradora, 0) AS taxa_administrativa
                FROM contas_receber cr
                JOIN venda v ON v.id_venda = cr.id_venda AND v.id_tenant = cr.id_tenant
                JOIN empresa e ON e.id_empresa = v.id_empresa AND e.id_tenant = v.id_tenant
                LEFT JOIN empresa ep ON ep.id_empresa = cr.id_empresa_pagamento AND ep.id_tenant = cr.id_tenant
                LEFT JOIN cliente cli ON cli.id_cliente = v.id_cliente AND cli.id_tenant = v.id_tenant
                JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                """
                + filtro
                + montarOrdenacao(ordenarPor, direcao);

        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> {
                    BigDecimal valorBruto = rs.getBigDecimal("valor_bruto");
                    BigDecimal taxa = rs.getBigDecimal("taxa_administrativa");
                    BigDecimal valorLiquido = valorBruto.subtract(
                            valorBruto.multiply(taxa).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
                    return new LinhaContaReceber(
                            rs.getLong("id_empresa"), rs.getString("nome_empresa"), rs.getString("nome_empresa_pagamento"),
                            rs.getLong("id_venda"), getLongOuNulo(rs, "id_cliente"), rs.getString("nome_cliente"),
                            rs.getString("nome_carteira"), rs.getString("categoria_carteira"),
                            rs.getInt("numero_parcela"), rs.getInt("total_parcelas"),
                            rs.getObject("data_venda", OffsetDateTime.class), rs.getObject("data_vencimento", OffsetDateTime.class),
                            rs.getObject("data_recebimento", OffsetDateTime.class),
                            valorBruto, taxa, valorLiquido);
                })
                .list();
    }

    /** Empresa é sempre o critério PRIMÁRIO (asc, ou a direção escolhida se o usuário clicar na
     *  própria coluna Empresa) — é o que mantém o agrupamento do subtotal por empresa válido
     *  (ver comentário de {@code COLUNAS_ORDENAVEIS}). A coluna escolhida entra como SECUNDÁRIO
     *  dentro de cada empresa; sem coluna nenhuma (estado inicial da tela), cai no default
     *  histórico (vencimento). {@code cr.id_venda}/{@code cr.numero_parcela} no fim garantem
     *  ordem determinística mesmo com muitos empates. */
    private static String montarOrdenacao(String ordenarPor, String direcao) {
        String direcaoSql = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";
        String colunaSql = ordenarPor == null ? null : COLUNAS_ORDENAVEIS.get(ordenarPor);
        String ordenacaoEmpresa = "nomeEmpresa".equals(ordenarPor) ? direcaoSql : "ASC";
        String ordenacaoSecundaria = (colunaSql == null || "nomeEmpresa".equals(ordenarPor))
                ? "cr.data_vencimento ASC"
                : colunaSql + " " + direcaoSql;
        return " ORDER BY e.razao_social " + ordenacaoEmpresa + ", " + ordenacaoSecundaria
                + ", cr.id_venda, cr.numero_parcela";
    }

    private static List<SubtotalEmpresaContaReceber> calcularSubtotais(List<LinhaContaReceber> linhas) {
        record Acumulador(String nomeEmpresa, BigDecimal[] valores) {
        }
        LinkedHashMap<Long, Acumulador> porEmpresa = new LinkedHashMap<>();
        for (LinhaContaReceber l : linhas) {
            Acumulador acc = porEmpresa.computeIfAbsent(l.idEmpresa(),
                    k -> new Acumulador(l.nomeEmpresa(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO}));
            acc.valores()[0] = acc.valores()[0].add(l.valorBruto());
            acc.valores()[1] = acc.valores()[1].add(l.valorLiquido());
        }
        List<SubtotalEmpresaContaReceber> subtotais = new ArrayList<>();
        porEmpresa.forEach((idEmpresa, acc) -> subtotais.add(
                new SubtotalEmpresaContaReceber(idEmpresa, acc.nomeEmpresa(), acc.valores()[0], acc.valores()[1])));
        return subtotais;
    }

    private static TotalGeralContaReceber calcularTotalGeral(List<LinhaContaReceber> linhas) {
        BigDecimal valorBruto = BigDecimal.ZERO;
        BigDecimal valorLiquido = BigDecimal.ZERO;
        for (LinhaContaReceber l : linhas) {
            valorBruto = valorBruto.add(l.valorBruto());
            valorLiquido = valorLiquido.add(l.valorLiquido());
        }
        return new TotalGeralContaReceber(valorBruto, valorLiquido);
    }

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
