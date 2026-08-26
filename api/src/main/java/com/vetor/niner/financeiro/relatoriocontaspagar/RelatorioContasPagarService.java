package com.vetor.niner.financeiro.relatoriocontaspagar;

import com.vetor.niner.financeiro.relatoriocontaspagar.RelatorioContasPagarDtos.FatiaGrafico;
import com.vetor.niner.financeiro.relatoriocontaspagar.RelatorioContasPagarDtos.KpisContaPagar;
import com.vetor.niner.financeiro.relatoriocontaspagar.RelatorioContasPagarDtos.LinhaContaPagar;
import com.vetor.niner.financeiro.relatoriocontaspagar.RelatorioContasPagarDtos.RelatorioContasPagarResponse;
import com.vetor.niner.financeiro.relatoriocontaspagar.RelatorioContasPagarDtos.SubtotalEmpresaContaPagar;
import com.vetor.niner.financeiro.relatoriocontaspagar.RelatorioContasPagarDtos.TotalGeralContaPagar;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relatório de Contas a Pagar / Pagas (docs/telas/relatorio-contas-pagar.md).
 *
 * <h2>⭐ "Paga" é {@code data_pagamento IS NOT NULL} — não {@code documento_pago}</h2>
 *
 * As duas colunas existem em {@code contas_pagar} e <b>podem divergir</b>: o formulário do CRUD
 * deixa preencher uma sem a outra. O critério adotado aqui é o mesmo que o <b>Fluxo de Caixa</b>
 * já usa ({@code FluxoCaixaService}: {@code cp.data_pagamento IS NULL} = a pagar).
 *
 * <p>⛔ Usar {@code documento_pago} faria duas telas financeiras darem <b>respostas opostas sobre o
 * mesmo fato</b> — o defeito que a V059 documentou entre o DRE-caixa e o Fluxo de Caixa. Quando as
 * duas colunas discordam, a linha é marcada como {@code divergente} e a tela mostra: o relatório
 * <b>exibe</b> o conflito em vez de escolher em silêncio.
 *
 * <h2>⛔ Este relatório NÃO respeita {@code cfg_plano_contas.inclui_dre}</h2>
 *
 * Ao contrário da DRE e da Lucratividade, e <b>pelo motivo oposto</b>. Aqui a pergunta é "quanto
 * sai do caixa", não "qual foi o lucro": compra de mercadoria ({@code 3.03.x}), amortização de
 * empréstimo ({@code 5.02.x}) e compra de imobilizado ({@code 6.01.x}) são <b>desembolso real</b>.
 *
 * <p>⚠️ Quem "consertar" isto aplicando o filtro do DRE vai fazer o relatório esconder justamente
 * a maior saída de dinheiro da loja. Há teste prendendo o comportamento.
 *
 * <h2>⚠️ Vencido é medido no fuso da LOJA</h2>
 *
 * {@code (data_vencimento AT TIME ZONE 'America/Sao_Paulo')::date < (now() AT TIME ZONE
 * 'America/Sao_Paulo')::date}. Um {@code CURRENT_DATE} cru compararia contra o dia do <b>banco</b>
 * (que roda em UTC) e viraria o dia seguinte às 21h de Brasília — o defeito varrido em 2026-08-19.
 */
@Service
public class RelatorioContasPagarService {

    private static final int PERIODO_MAXIMO_DIAS = 400;

    /** Quantas fatias nomeadas cada gráfico mostra antes de somar o resto em "Outros". */
    private static final int FATIAS_NO_GRAFICO = 7;

    /**
     * Allowlist de colunas ordenáveis — ⛔ <b>nunca</b> concatenar na SQL a coluna vinda do
     * cliente. Mesmo padrão de todos os relatórios do produto.
     *
     * <p>⚠️ {@code nomeEmpresa} é tratado à parte em {@link #buscarLinhas}: o subtotal por empresa
     * só funciona se ela continuar sendo o critério <b>primário</b>, então a coluna escolhida pelo
     * usuário entra como secundária dentro de cada empresa.
     */
    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.ofEntries(
            Map.entry("nomeEmpresa", "e.razao_social"),
            Map.entry("nomeFornecedor", "COALESCE(f.nome_fantasia, f.razao_social)"),
            Map.entry("idPlanoContas", "cp.id_plano_contas"),
            Map.entry("notaFiscal", "cp.nota_fiscal"),
            Map.entry("numeroDuplicata", "cp.numero_duplicata"),
            Map.entry("dataLancamento", "cp.data_lancamento"),
            Map.entry("dataVencimento", "cp.data_vencimento"),
            Map.entry("dataPagamento", "cp.data_pagamento"),
            Map.entry("valorPagar", "cp.valor_pagar"),
            Map.entry("valorPago", "cp.valor_pago"),
            Map.entry("valorEmAberto", "(cp.valor_pagar - cp.valor_pago)"));

    private final JdbcClient jdbc;

    public RelatorioContasPagarService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public enum SituacaoConta {
        ABERTA, PAGA
    }

    public record FiltrosPeriodo(
            LocalDate dataLancamentoInicial, LocalDate dataLancamentoFinal,
            LocalDate dataVencimentoInicial, LocalDate dataVencimentoFinal,
            LocalDate dataPagamentoInicial, LocalDate dataPagamentoFinal) {
    }

    @Transactional(readOnly = true)
    public RelatorioContasPagarResponse gerar(
            Jwt jwt, FiltrosPeriodo periodos, List<Long> idsEmpresaSolicitadas, Long idFornecedor,
            String idPlanoContas, SituacaoConta situacao, String ordenarPor, String direcao) {

        validarPeriodo("lançamento", periodos.dataLancamentoInicial(), periodos.dataLancamentoFinal());
        validarPeriodo("vencimento", periodos.dataVencimentoInicial(), periodos.dataVencimentoFinal());
        validarPeriodo("pagamento", periodos.dataPagamentoInicial(), periodos.dataPagamentoFinal());

        if (periodos.dataLancamentoInicial() == null
                && periodos.dataVencimentoInicial() == null
                && periodos.dataPagamentoInicial() == null) {
            throw new IllegalArgumentException(
                    "Informe ao menos um período (lançamento, vencimento ou pagamento).");
        }

        List<Long> idsEmpresaEfetivo = resolverIdsEmpresa(jwt, idsEmpresaSolicitadas);
        List<LinhaContaPagar> linhas = buscarLinhas(
                periodos, idsEmpresaEfetivo, idFornecedor, idPlanoContas, situacao, ordenarPor, direcao);

        return new RelatorioContasPagarResponse(
                linhas,
                calcularSubtotais(linhas),
                calcularTotalGeral(linhas),
                calcularKpis(linhas),
                agrupar(linhas, LinhaContaPagar::descricaoPlanoContas),
                agrupar(linhas, LinhaContaPagar::nomeFornecedor));
    }

    private void validarPeriodo(String nome, LocalDate inicial, LocalDate fim) {
        if (inicial == null && fim == null) {
            return;
        }
        if (inicial == null || fim == null) {
            throw new IllegalArgumentException("Informe início e fim do período de " + nome + ".");
        }
        if (inicial.isAfter(fim)) {
            throw new IllegalArgumentException(
                    "Data inicial de " + nome + " não pode ser maior que a final.");
        }
        if (inicial.plusDays(PERIODO_MAXIMO_DIAS).isBefore(fim)) {
            throw new IllegalArgumentException(
                    "Período de " + nome + " não pode exceder " + PERIODO_MAXIMO_DIAS + " dias.");
        }
    }

    /** OPERADOR fica preso na empresa da sessão; ADMIN escolhe, e nenhuma escolhida = todas. */
    private List<Long> resolverIdsEmpresa(Jwt jwt, List<Long> idsEmpresaSolicitadas) {
        if (!ehAdmin(jwt)) {
            return List.of(idEmpresaSessao(jwt));
        }
        if (idsEmpresaSolicitadas == null || idsEmpresaSolicitadas.isEmpty()) {
            return null;
        }
        return idsEmpresaSolicitadas;
    }

    private List<LinhaContaPagar> buscarLinhas(
            FiltrosPeriodo periodos, List<Long> idsEmpresaEfetivo, Long idFornecedor,
            String idPlanoContas, SituacaoConta situacao, String ordenarPor, String direcao) {

        // ⚠️ id_tenant no TEXTO do SQL, não só pela política de RLS (P8).
        StringBuilder filtro = new StringBuilder(" WHERE cp.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();

        if (periodos.dataLancamentoInicial() != null) {
            filtro.append(" AND (cp.data_lancamento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?");
            params.add(periodos.dataLancamentoInicial());
            params.add(periodos.dataLancamentoFinal());
        }
        if (periodos.dataVencimentoInicial() != null) {
            filtro.append(" AND (cp.data_vencimento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?");
            params.add(periodos.dataVencimentoInicial());
            params.add(periodos.dataVencimentoFinal());
        }
        if (periodos.dataPagamentoInicial() != null) {
            filtro.append(" AND (cp.data_pagamento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?");
            params.add(periodos.dataPagamentoInicial());
            params.add(periodos.dataPagamentoFinal());
        }
        if (situacao == SituacaoConta.ABERTA) {
            filtro.append(" AND cp.data_pagamento IS NULL");
        } else if (situacao == SituacaoConta.PAGA) {
            filtro.append(" AND cp.data_pagamento IS NOT NULL");
        }
        if (idFornecedor != null) {
            filtro.append(" AND cp.id_fornecedor = ?");
            params.add(idFornecedor);
        }
        if (idPlanoContas != null && !idPlanoContas.isBlank()) {
            // ⭐ Casa por PREFIXO, aproveitando a máscara de largura fixa da V016: escolher a conta
            // sintética `3.03.000` traz toda a subárvore, que é o que o lojista espera ao perguntar
            // "quanto gastei com mercadoria". `LIKE` com o prefixo até o último ponto.
            filtro.append(" AND cp.id_plano_contas LIKE ?");
            params.add(prefixoDoPlano(idPlanoContas));
        }
        if (idsEmpresaEfetivo != null) {
            String marcadores = String.join(",", idsEmpresaEfetivo.stream().map(id -> "?").toList());
            filtro.append(" AND cp.id_empresa IN (").append(marcadores).append(")");
            params.addAll(idsEmpresaEfetivo);
        }

        String colunaEscolhida = ordenarPor == null ? null : COLUNAS_ORDENAVEIS.get(ordenarPor);
        String dir = "ASC".equalsIgnoreCase(direcao) ? "ASC" : "DESC";
        // Empresa sempre primária (o subtotal depende disso); a escolha do usuário é secundária.
        String ordem = colunaEscolhida == null || "e.razao_social".equals(colunaEscolhida)
                ? "e.razao_social ASC, cp.data_vencimento " + dir
                : "e.razao_social ASC, " + colunaEscolhida + " " + dir;

        String sql = """
                SELECT cp.id_conta_pagar, cp.id_empresa, e.razao_social AS nome_empresa,
                       cp.id_fornecedor, COALESCE(f.nome_fantasia, f.razao_social) AS nome_fornecedor,
                       cp.id_plano_contas, pc.descricao AS descricao_plano_contas,
                       cp.nota_fiscal, cp.numero_duplicata,
                       cp.data_lancamento, cp.data_vencimento, cp.data_pagamento,
                       cp.valor_pagar, cp.valor_pago,
                       (cp.valor_pagar - cp.valor_pago) AS valor_em_aberto,
                       cp.documento_pago,
                       -- ⚠️ Vencido no fuso da LOJA. `CURRENT_DATE` cru compararia com o dia do
                       -- banco (UTC), que vira o dia seguinte às 21h de Brasília.
                       ((cp.data_vencimento AT TIME ZONE 'America/Sao_Paulo')::date
                          < (now() AT TIME ZONE 'America/Sao_Paulo')::date) AS vencida
                  FROM contas_pagar cp
                  JOIN empresa e ON e.id_tenant = cp.id_tenant AND e.id_empresa = cp.id_empresa
                  JOIN fornecedor f ON f.id_tenant = cp.id_tenant AND f.id_fornecedor = cp.id_fornecedor
                  JOIN cfg_plano_contas pc ON pc.id_tenant = cp.id_tenant
                                          AND pc.id_plano_contas = cp.id_plano_contas
                """ + filtro + " ORDER BY " + ordem;

        return jdbc.sql(sql).params(params).query(RelatorioContasPagarService::mapear).list();
    }

    /**
     * O prefixo de busca de um código do plano de contas.
     *
     * <p>A máscara é {@code 9.99.999} (V016). Uma conta sintética termina em zeros
     * ({@code 3.03.000}) e deve trazer a subárvore inteira; uma analítica
     * ({@code 3.03.001}) traz só ela mesma.
     *
     * <p>⚠️ Sem isto, filtrar por uma conta sintética devolveria <b>zero linhas</b> — porque
     * lançamento nunca cai em conta sintética (V016: só ANALITICA recebe lançamento). O lojista
     * escolheria "Compra de mercadoria" e veria o relatório vazio.
     */
    static String prefixoDoPlano(String idPlanoContas) {
        String codigo = idPlanoContas.trim();
        if (codigo.endsWith("000") && codigo.length() > 3) {
            return codigo.substring(0, codigo.length() - 3) + "%";
        }
        return codigo;
    }

    private static LinhaContaPagar mapear(ResultSet rs, int n) throws SQLException {
        boolean documentoPago = rs.getBoolean("documento_pago");
        OffsetDateTime dataPagamento = rs.getObject("data_pagamento", OffsetDateTime.class);
        boolean paga = dataPagamento != null;
        boolean vencida = rs.getBoolean("vencida");

        Integer notaFiscal = rs.getInt("nota_fiscal");
        if (rs.wasNull()) {
            notaFiscal = null;
        }

        return new LinhaContaPagar(
                rs.getLong("id_conta_pagar"), rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                rs.getLong("id_fornecedor"), rs.getString("nome_fornecedor"),
                rs.getString("id_plano_contas"), rs.getString("descricao_plano_contas"),
                notaFiscal, rs.getString("numero_duplicata"),
                rs.getObject("data_lancamento", OffsetDateTime.class),
                rs.getObject("data_vencimento", OffsetDateTime.class),
                dataPagamento,
                rs.getBigDecimal("valor_pagar"), rs.getBigDecimal("valor_pago"),
                rs.getBigDecimal("valor_em_aberto"),
                documentoPago,
                paga ? "PAGA" : (vencida ? "VENCIDA" : "A_VENCER"),
                // ⚠️ As duas colunas discordam. Mostrar é melhor que escolher em silêncio.
                documentoPago != paga);
    }

    private static List<SubtotalEmpresaContaPagar> calcularSubtotais(List<LinhaContaPagar> linhas) {
        Map<Long, SubtotalEmpresaContaPagar> por = new LinkedHashMap<>();
        for (LinhaContaPagar l : linhas) {
            por.merge(l.idEmpresa(),
                    new SubtotalEmpresaContaPagar(l.idEmpresa(), l.nomeEmpresa(),
                            l.valorPagar(), l.valorPago(), l.valorEmAberto()),
                    (a, b) -> new SubtotalEmpresaContaPagar(a.idEmpresa(), a.nomeEmpresa(),
                            a.valorPagar().add(b.valorPagar()),
                            a.valorPago().add(b.valorPago()),
                            a.valorEmAberto().add(b.valorEmAberto())));
        }
        return List.copyOf(por.values());
    }

    private static TotalGeralContaPagar calcularTotalGeral(List<LinhaContaPagar> linhas) {
        BigDecimal pagar = BigDecimal.ZERO;
        BigDecimal pago = BigDecimal.ZERO;
        BigDecimal aberto = BigDecimal.ZERO;
        for (LinhaContaPagar l : linhas) {
            pagar = pagar.add(l.valorPagar());
            pago = pago.add(l.valorPago());
            aberto = aberto.add(l.valorEmAberto());
        }
        return new TotalGeralContaPagar(pagar, pago, aberto);
    }

    /**
     * Os cinco KPIs.
     *
     * <p>⚠️ {@code vencido + aVencer = emAberto}, nunca {@code totalPeriodo}. São campos separados
     * de propósito — ver o javadoc de {@code KpisContaPagar}.
     */
    private static KpisContaPagar calcularKpis(List<LinhaContaPagar> linhas) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal aberto = BigDecimal.ZERO;
        BigDecimal vencido = BigDecimal.ZERO;
        BigDecimal aVencer = BigDecimal.ZERO;
        BigDecimal pago = BigDecimal.ZERO;

        for (LinhaContaPagar l : linhas) {
            total = total.add(l.valorPagar());
            if ("PAGA".equals(l.situacao())) {
                pago = pago.add(l.valorPago());
            } else {
                aberto = aberto.add(l.valorEmAberto());
                if ("VENCIDA".equals(l.situacao())) {
                    vencido = vencido.add(l.valorEmAberto());
                } else {
                    aVencer = aVencer.add(l.valorEmAberto());
                }
            }
        }
        return new KpisContaPagar(total, aberto, vencido, aVencer, pago);
    }

    /**
     * Agrupa por um rótulo e corta em "Outros".
     *
     * <p>⚠️ Soma {@code valorPagar}, não o em aberto — a pergunta do gráfico é "em que / para quem
     * eu comprometi dinheiro", e um gráfico que encolhesse conforme se paga responderia outra
     * coisa.
     *
     * <p>⚠️ E o corte é <b>somado</b> em "Outros", nunca descartado: um gráfico cujas fatias não
     * fecham o total faz o leitor desconfiar de tudo o que está na tela.
     */
    private static List<FatiaGrafico> agrupar(List<LinhaContaPagar> linhas,
                                              java.util.function.Function<LinhaContaPagar, String> rotulo) {
        Map<String, BigDecimal> por = new LinkedHashMap<>();
        for (LinhaContaPagar l : linhas) {
            String chave = rotulo.apply(l);
            por.merge(chave == null || chave.isBlank() ? "(sem classificação)" : chave,
                    l.valorPagar(), BigDecimal::add);
        }

        List<FatiaGrafico> todas = new ArrayList<>();
        por.forEach((k, v) -> todas.add(new FatiaGrafico(k, v)));
        todas.sort(Comparator.comparing(FatiaGrafico::valor).reversed());

        if (todas.size() <= FATIAS_NO_GRAFICO) {
            return todas;
        }
        List<FatiaGrafico> recorte = new ArrayList<>(todas.subList(0, FATIAS_NO_GRAFICO));
        BigDecimal outros = todas.subList(FATIAS_NO_GRAFICO, todas.size()).stream()
                .map(FatiaGrafico::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        recorte.add(new FatiaGrafico("Outros", outros));
        return recorte;
    }

    private static boolean ehAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }

    private static long idEmpresaSessao(Jwt jwt) {
        return ((Number) jwt.getClaim("eid")).longValue();
    }
}
