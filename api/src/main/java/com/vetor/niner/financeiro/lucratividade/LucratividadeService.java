package com.vetor.niner.financeiro.lucratividade;

import com.vetor.niner.financeiro.lucratividade.LucratividadeDtos.LinhaDespesa;
import com.vetor.niner.financeiro.lucratividade.LucratividadeDtos.LucratividadeResponse;
import com.vetor.niner.financeiro.lucratividade.LucratividadeDtos.PeriodoLucratividade;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Relatório de Lucratividade (docs/telas/relatorio-lucratividade.md) — venda, custo, lucro bruto,
 * despesas por plano de contas e lucro líquido, numa página só.
 *
 * <p><b>Não é a DRE.</b> A DRE tem regime escolhível, comparação entre períodos e a estrutura
 * contábil de grupos; aqui a leitura é direta e o regime é fixo. Os dois relatórios convivem de
 * propósito: quem quer a leitura contábil vai na DRE, quem quer saber se sobrou dinheiro vem aqui.
 *
 * <p><b>⚠️ As datas do relatório têm TRÊS naturezas</b>, e isso é decisão de produto:
 * <ol>
 *   <li><b>Venda, devolução e custo</b> — data da <b>venda</b>;</li>
 *   <li><b>Contas pagas</b> — data de <b>pagamento</b> do Contas a Pagar (pedido explícito: casar
 *       "o que vendi neste mês" com "o que saiu da minha conta neste mês");</li>
 *   <li><b>Comissão e taxa de cartão</b> — data da <b>venda</b>, porque elas <b>não têm data de
 *       pagamento</b>: não existe lançamento em Contas a Pagar para elas. É a única data que
 *       possuem.</li>
 * </ol>
 * A consequência está escrita na tela e na ajuda, não só aqui: conta de janeiro paga em fevereiro
 * pesa em fevereiro, enquanto a comissão da venda de janeiro pesa em janeiro.
 *
 * <p><b>⚠️ Compra de mercadoria não entra nas despesas</b> — {@code cfg_plano_contas
 * .inclui_dre = false}, a mesma marca que a DRE respeita. A mercadoria já está contada no CMV,
 * quando <i>sai vendida</i>; somá-la de novo no desembolso contaria a mesma coisa duas vezes e
 * transformaria em prejuízo um mês que deu lucro. Vale igual para amortização de empréstimo
 * (troca dívida por dinheiro) e compra de imobilizado (investimento). ⛔ <b>A regra é dado, não
 * código</b>: quem decide é a marca do plano de contas, editável pelo lojista — não existe lista
 * de códigos escrita aqui.
 */
@Service
public class LucratividadeService {

    private static final int PERIODO_MAXIMO_DIAS = 400;
    private static final int ESCALA_PERCENTUAL = 2;

    /** Contas das duas despesas derivadas — as mesmas que a DRE usa, para os relatórios baterem. */
    private static final String CONTA_COMISSAO = "3.02.001";
    private static final String CONTA_TAXA_CARTAO = "3.02.002";
    private static final String DESCRICAO_COMISSAO = "Comissões sobre Vendas";
    private static final String DESCRICAO_TAXA_CARTAO = "Taxas de Cartão e PIX";

    private final JdbcClient jdbc;

    public LucratividadeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public LucratividadeResponse gerar(
            Jwt jwt, LocalDate dataInicial, LocalDate dataFinal, List<Long> idsEmpresaSolicitadas) {
        // ⛔ O "exigirAdmin" que existia aqui saiu em 2026-08-27: a V078 tirou esta tela de
        // `admin_apenas`, então o administrador podia concedê-la — e o operador tomava 403 numa
        // tela que a grade jurava ter liberado, sem nada explicando por quê. Quem decide agora é a
        // permissão por tela (PermissaoInterceptor). Era o defeito das "duas trancas na mesma
        // porta", que o commit fa85474 removeu de outras dez telas e esqueceu destas duas.
        validarPeriodo(dataInicial, dataFinal);
        List<Long> idsEmpresa = (idsEmpresaSolicitadas == null || idsEmpresaSolicitadas.isEmpty())
                ? null : idsEmpresaSolicitadas;

        BigDecimal[] vendas = apurarVendas(dataInicial, dataFinal, idsEmpresa);
        BigDecimal[] devolucoes = apurarDevolucoes(dataInicial, dataFinal, idsEmpresa);

        BigDecimal vendaBruta = vendas[0];
        BigDecimal valorDevolvido = devolucoes[0];
        BigDecimal vendaLiquida = vendaBruta.subtract(valorDevolvido);
        BigDecimal cmv = vendas[1].subtract(devolucoes[1]);
        BigDecimal lucroBruto = vendaLiquida.subtract(cmv);

        List<LinhaDespesa> despesas = apurarDespesas(
                dataInicial, dataFinal, idsEmpresa, vendas[2], vendaLiquida, lucroBruto);
        BigDecimal totalDespesas = despesas.stream()
                .map(LinhaDespesa::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lucroLiquido = lucroBruto.subtract(totalDespesas);

        return new LucratividadeResponse(
                new PeriodoLucratividade(dataInicial, dataFinal),
                vendaBruta, valorDevolvido, vendaLiquida, cmv, lucroBruto,
                percentual(lucroBruto, vendaLiquida),
                despesas, totalDespesas, lucroLiquido,
                percentual(lucroLiquido, vendaBruta),
                percentual(lucroLiquido, vendaLiquida));
    }

    // ------------------------------------------------------------------ crédito

    /**
     * Vendas do período: valor, custo e comissão na mesma varredura do ledger (uma linha por item
     * vendido).
     *
     * <p>O valor da linha é {@code qtd × preço + acréscimo − desconto} — o que o cliente
     * efetivamente pagou, o mesmo número que sai na papeleta. A DRE separa o desconto numa linha de
     * dedução porque a estrutura contábil pede; aqui o lojista quer "o que vendi", e o desconto já
     * é parte do preço praticado.
     *
     * <p>A <b>comissão</b> sai daqui, e não de uma consulta própria, porque depende do
     * {@code perc_comissao} do funcionário <b>daquela linha</b> — a mesma venda pode ter itens de
     * vendedores diferentes. A base é {@code qtd × preço − desconto}, igual à da DRE, para os dois
     * relatórios baterem.
     *
     * @return {@code [valor, custo, comissao]}
     */
    private BigDecimal[] apurarVendas(LocalDate inicio, LocalDate fim, List<Long> idsEmpresa) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(pmd.qtd_produto * pmd.preco_venda
                                            + pmd.valor_acrescimo - pmd.valor_desconto), 0) AS valor,
                               COALESCE(SUM(pmd.qtd_produto * pmd.preco_custo), 0) AS custo,
                               COALESCE(SUM((pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto)
                                            * COALESCE(fn.perc_comissao, 0) / 100), 0) AS comissao
                        FROM venda v
                        JOIN produto_movimento_mestre pmm
                             ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant
                                AND pmm.tipo_movimento = 'VENDA'
                        JOIN produto_movimento_detalhe pmd
                             ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                                AND pmd.credito_debito = 'D'
                        LEFT JOIN funcionario fn
                             ON fn.id_funcionario = pmd.id_funcionario AND fn.id_tenant = pmd.id_tenant
                        WHERE v.id_tenant = plataforma.tenant_atual() AND v.cancelada = false
                              AND (v.data_venda AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                        """ + filtroEmpresa("v.id_empresa", idsEmpresa))
                .params(parametros(inicio, fim, idsEmpresa))
                .query((rs, n) -> new BigDecimal[] {
                        rs.getBigDecimal("valor"), rs.getBigDecimal("custo"), rs.getBigDecimal("comissao") })
                .single();
    }

    /**
     * Devoluções do período: abatem venda e revertem o custo, pelos dois lados.
     *
     * <p>⚠️ <b>Devolução CANCELADA não deduz nada</b> — cancelar não apaga as linhas {@code
     * DEVOLUCAO} do ledger, só marca {@code venda_devolucao.cancelada} e lança o movimento
     * compensatório. Sem este filtro o resultado do mês erraria nas duas pontas, e nada na tela
     * apontaria a causa. É o mesmo defeito que a auditoria de 2026-08-21 encontrou na DRE.
     *
     * <p>⚠️ O corte é pela <b>data da devolução</b>, não pela data da venda de origem: devolver em
     * março uma venda de fevereiro reduz o total de <b>março</b>. O contrário obrigaria a
     * recalcular meses já fechados a cada devolução.
     *
     * <p>⚠️ <b>A devolução NÃO estorna comissão nem taxa de cartão</b>, igual à DRE: o vendedor
     * vendeu, e a operadora do cartão já cobrou a taxa sobre a transação original. Reverter seria
     * uma regra de negócio nova, e divergente da DRE.
     *
     * @return {@code [valor, custo]}
     */
    private BigDecimal[] apurarDevolucoes(LocalDate inicio, LocalDate fim, List<Long> idsEmpresa) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(pmd.qtd_produto * pmd.preco_venda), 0) AS valor,
                               COALESCE(SUM(pmd.qtd_produto * pmd.preco_custo), 0) AS custo
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                             ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                                AND pmd.credito_debito = 'C'
                        JOIN venda_devolucao vd
                             ON vd.id_tenant = pmm.id_tenant AND vd.id_devolucao = pmm.id_devolucao
                            AND vd.cancelada = false
                        WHERE pmm.id_tenant = plataforma.tenant_atual()
                              AND pmm.tipo_movimento = 'DEVOLUCAO'
                              AND (pmm.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                        """ + filtroEmpresa("pmm.id_empresa", idsEmpresa))
                .params(parametros(inicio, fim, idsEmpresa))
                .query((rs, n) -> new BigDecimal[] { rs.getBigDecimal("valor"), rs.getBigDecimal("custo") })
                .single();
    }

    // ------------------------------------------------------------------ débito

    /** Item 5 — contas pagas + as duas despesas derivadas, ordenadas pelo código da conta. */
    private List<LinhaDespesa> apurarDespesas(
            LocalDate inicio, LocalDate fim, List<Long> idsEmpresa,
            BigDecimal comissao, BigDecimal vendaLiquida, BigDecimal lucroBruto) {
        List<LinhaDespesa> linhas = new ArrayList<>(contasPagas(inicio, fim, idsEmpresa, vendaLiquida, lucroBruto));

        acrescentarDerivada(linhas, CONTA_COMISSAO, DESCRICAO_COMISSAO,
                arredondar(comissao), vendaLiquida, lucroBruto);
        acrescentarDerivada(linhas, CONTA_TAXA_CARTAO, DESCRICAO_TAXA_CARTAO,
                arredondar(taxasDeCartao(inicio, fim, idsEmpresa)), vendaLiquida, lucroBruto);

        linhas.sort(Comparator.comparing(LinhaDespesa::idPlanoContas));
        return linhas;
    }

    /**
     * Contas pagas no período, agrupadas por conta analítica do plano de contas.
     *
     * <p>O corte é a <b>data de pagamento</b>: conta vencida no período mas paga depois não entra;
     * conta lançada antes e paga dentro entra.
     *
     * <p>{@code COALESCE(NULLIF(valor_pago, 0), valor_pagar)} — conta marcada como paga sem o valor
     * preenchido vale pelo valor original. ⚠️ O {@code NULLIF} não é defensividade:
     * {@code valor_pago} é {@code NOT NULL DEFAULT 0} e o Contas a Pagar grava zero quando a tela
     * manda o campo vazio, então um {@code COALESCE} puro nunca cairia no fallback e a baixa
     * "cheia" entraria como R$ 0,00. Mesma expressão da DRE em regime de caixa — se uma mudar, a
     * outra muda junto.
     */
    private List<LinhaDespesa> contasPagas(
            LocalDate inicio, LocalDate fim, List<Long> idsEmpresa,
            BigDecimal vendaLiquida, BigDecimal lucroBruto) {
        List<LinhaDespesa> contas = new ArrayList<>();
        jdbc.sql("""
                        SELECT cp.id_plano_contas, pc.descricao,
                               COALESCE(SUM(COALESCE(NULLIF(cp.valor_pago, 0), cp.valor_pagar)), 0) AS valor
                        FROM contas_pagar cp
                        JOIN cfg_plano_contas pc
                             ON pc.id_plano_contas = cp.id_plano_contas AND pc.id_tenant = cp.id_tenant
                        WHERE cp.id_tenant = plataforma.tenant_atual()
                              AND pc.inclui_dre = true
                              AND cp.data_pagamento IS NOT NULL
                              AND (cp.data_pagamento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                        """ + filtroEmpresa("cp.id_empresa", idsEmpresa) + """
                        GROUP BY cp.id_plano_contas, pc.descricao
                        HAVING COALESCE(SUM(COALESCE(NULLIF(cp.valor_pago, 0), cp.valor_pagar)), 0) <> 0
                        """)
                .params(parametros(inicio, fim, idsEmpresa))
                .query((rs, n) -> {
                    BigDecimal valor = rs.getBigDecimal("valor");
                    contas.add(new LinhaDespesa(
                            rs.getString("id_plano_contas"), rs.getString("descricao"), valor, false,
                            percentual(valor, vendaLiquida), percentual(valor, lucroBruto)));
                    return 1;
                })
                .list();
        return contas;
    }

    /**
     * Taxa de cartão/PIX: percentual da carteira sobre o valor a receber, pela data da <b>venda</b>
     * — mesmo que a parcela só caia depois. Igual à DRE em competência.
     */
    private BigDecimal taxasDeCartao(LocalDate inicio, LocalDate fim, List<Long> idsEmpresa) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(cr.valor_receber * tc.taxa_administradora / 100), 0)
                        FROM contas_receber cr
                        JOIN venda v ON v.id_venda = cr.id_venda AND v.id_tenant = cr.id_tenant
                        JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND v.cancelada = false
                              AND tc.taxa_administradora > 0
                              AND (v.data_venda AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                        """ + filtroEmpresa("v.id_empresa", idsEmpresa))
                .params(parametros(inicio, fim, idsEmpresa))
                .query(BigDecimal.class).single();
    }

    /**
     * Acrescenta uma despesa derivada, se ela existir no período.
     *
     * <p>⚠️ <b>Zero não vira linha.</b> Loja sem comissionamento (o caso comum: {@code
     * perc_comissao = 0} em todo funcionário) veria "Comissões sobre Vendas — R$ 0,00" todo mês,
     * sugerindo que falta configurar algo. Mesma regra do {@code HAVING <> 0} das contas pagas.
     *
     * <p>A descrição é a que o lojista deu à conta, quando ela existe no plano dele. O código só
     * serve de chave — o signup semeia poucas contas, e o plano padrão completo é um seed à parte
     * que nem todo tenant aplicou; se a linha dependesse de {@code 3.02.001} existir, a comissão
     * sumiria do relatório de um tenant novo. É o mesmo cuidado que a DRE tomou em 2026-08-14.
     */
    private void acrescentarDerivada(
            List<LinhaDespesa> linhas, String idPlanoContas, String descricaoPadrao,
            BigDecimal valor, BigDecimal vendaLiquida, BigDecimal lucroBruto) {
        if (valor.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        String descricao = jdbc.sql("""
                        SELECT descricao FROM cfg_plano_contas
                        WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?
                        """)
                .param(idPlanoContas)
                .query(String.class).optional().orElse(descricaoPadrao);
        linhas.add(new LinhaDespesa(idPlanoContas, descricao, valor, true,
                percentual(valor, vendaLiquida), percentual(valor, lucroBruto)));
    }

    // ------------------------------------------------------------------ apoio

    /**
     * Percentual de {@code parte} sobre {@code base}, ou {@code null} quando não há base.
     *
     * <p>⚠️ <b>Base zero devolve {@code null}, nunca zero</b> — um {@code 0%} impresso afirmaria
     * "margem zero" onde na verdade não houve venda nenhuma, e o lojista leria isso como resultado.
     *
     * <p>⚠️ <b>Base negativa também devolve {@code null}</b>: "esta despesa é 40% de um prejuízo"
     * não é uma frase com significado, e os percentuais deixariam de somar. Acontece de verdade no
     * mês em que o CMV supera a venda (liquidação abaixo do custo).
     */
    private static BigDecimal percentual(BigDecimal parte, BigDecimal base) {
        if (base == null || base.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return parte.multiply(BigDecimal.valueOf(100))
                .divide(base, ESCALA_PERCENTUAL, RoundingMode.HALF_UP);
    }

    /** Valor derivado nasce com casas demais (percentual sobre percentual) — arredonda a centavo. */
    private static BigDecimal arredondar(BigDecimal valor) {
        return valor == null ? BigDecimal.ZERO : valor.setScale(2, RoundingMode.HALF_UP);
    }

    /** ADMIN-only, como a DRE: o relatório expõe lucro, despesa e pró-labore. */
    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem consultar a Lucratividade.");
        }
    }

    private static void validarPeriodo(LocalDate dataInicial, LocalDate dataFinal) {
        if (dataInicial == null || dataFinal == null) {
            throw new IllegalArgumentException("Informe a data inicial e a data final.");
        }
        if (dataInicial.isAfter(dataFinal)) {
            throw new IllegalArgumentException("Data inicial não pode ser maior que a data final.");
        }
        if (dataInicial.plusDays(PERIODO_MAXIMO_DIAS).isBefore(dataFinal)) {
            throw new IllegalArgumentException(
                    "Período de consulta não pode exceder " + PERIODO_MAXIMO_DIAS + " dias.");
        }
    }

    private static String filtroEmpresa(String coluna, List<Long> idsEmpresa) {
        if (idsEmpresa == null) {
            return "";
        }
        return " AND " + coluna + " IN (" + String.join(",", idsEmpresa.stream().map(id -> "?").toList()) + ")";
    }

    private static List<Object> parametros(LocalDate inicio, LocalDate fim, List<Long> idsEmpresa) {
        List<Object> params = new ArrayList<>();
        params.add(inicio);
        params.add(fim);
        if (idsEmpresa != null) {
            params.addAll(idsEmpresa);
        }
        return params;
    }
}
