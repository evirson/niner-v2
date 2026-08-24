package com.vetor.niner.financeiro.lucratividade;

import com.vetor.niner.financeiro.lucratividade.LucratividadeDtos.ContaPaga;
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
import java.util.List;

/**
 * Relatório de Lucratividade (docs/telas/relatorio-lucratividade.md) — venda, custo, lucro bruto,
 * contas pagas por plano de contas e lucro líquido, numa página só.
 *
 * <p><b>Não é a DRE.</b> A DRE tem regime escolhível, comparação entre períodos e a estrutura
 * contábil de grupos; aqui a leitura é direta e o regime é fixo. Os dois relatórios convivem de
 * propósito: quem quer a leitura contábil vai na DRE, quem quer saber se sobrou dinheiro vem aqui.
 *
 * <p><b>⚠️ O período tem duas naturezas, e isso é decisão de produto.</b> O <b>crédito</b> (venda,
 * devolução e o custo que sai com elas) conta pela <b>data da venda</b>; o <b>débito</b> conta pela
 * <b>data de pagamento</b> do Contas a Pagar. É um regime misto — competência de um lado, caixa do
 * outro — pedido assim para casar "o que vendi neste mês" com "o que saiu da minha conta neste
 * mês". A consequência é real e está escrita na ajuda da tela: conta de janeiro paga em fevereiro
 * pesa em fevereiro.
 *
 * <p><b>⚠️ Compra de mercadoria não entra nas contas pagas</b> — {@code cfg_plano_contas
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

    private final JdbcClient jdbc;

    public LucratividadeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public LucratividadeResponse gerar(
            Jwt jwt, LocalDate dataInicial, LocalDate dataFinal, List<Long> idsEmpresaSolicitadas) {
        exigirAdmin(jwt);
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

        List<ContaPaga> contasPagas = apurarContasPagas(dataInicial, dataFinal, idsEmpresa, vendaLiquida, lucroBruto);
        BigDecimal totalContasPagas = contasPagas.stream()
                .map(ContaPaga::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lucroLiquido = lucroBruto.subtract(totalContasPagas);

        return new LucratividadeResponse(
                new PeriodoLucratividade(dataInicial, dataFinal),
                vendaBruta, valorDevolvido, vendaLiquida, cmv, lucroBruto,
                percentual(lucroBruto, vendaLiquida),
                contasPagas, totalContasPagas, lucroLiquido,
                percentual(lucroLiquido, vendaBruta),
                percentual(lucroLiquido, vendaLiquida));
    }

    // ------------------------------------------------------------------ apuração

    /**
     * Vendas do período: valor e custo na mesma varredura do ledger (uma linha por item vendido).
     *
     * <p>O valor da linha é {@code qtd × preço + acréscimo − desconto} — o que o cliente
     * efetivamente pagou, o mesmo número que sai na papeleta. A DRE separa o desconto numa linha de
     * dedução porque a estrutura contábil pede; aqui o lojista quer "o que vendi", e o desconto já
     * é parte do preço praticado.
     *
     * @return {@code [valor, custo]}
     */
    private BigDecimal[] apurarVendas(LocalDate inicio, LocalDate fim, List<Long> idsEmpresa) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(pmd.qtd_produto * pmd.preco_venda
                                            + pmd.valor_acrescimo - pmd.valor_desconto), 0) AS valor,
                               COALESCE(SUM(pmd.qtd_produto * pmd.preco_custo), 0) AS custo
                        FROM venda v
                        JOIN produto_movimento_mestre pmm
                             ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant
                                AND pmm.tipo_movimento = 'VENDA'
                        JOIN produto_movimento_detalhe pmd
                             ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                                AND pmd.credito_debito = 'D'
                        WHERE v.id_tenant = plataforma.tenant_atual() AND v.cancelada = false
                              AND (v.data_venda AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                        """ + filtroEmpresa("v.id_empresa", idsEmpresa))
                .params(parametros(inicio, fim, idsEmpresa))
                .query((rs, n) -> new BigDecimal[] { rs.getBigDecimal("valor"), rs.getBigDecimal("custo") })
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

    /**
     * Item 5 — contas pagas no período, agrupadas por conta analítica do plano de contas.
     *
     * <p>O corte é a <b>data de pagamento</b>: conta vencida no período mas paga depois não entra;
     * conta lançada antes e paga dentro entra.
     *
     * <p>{@code COALESCE(NULLIF(valor_pago, 0), valor_pagar)} — conta marcada como paga sem o valor
     * preenchido vale pelo valor original, mesma convenção da DRE em regime de caixa.
     */
    private List<ContaPaga> apurarContasPagas(
            LocalDate inicio, LocalDate fim, List<Long> idsEmpresa,
            BigDecimal vendaLiquida, BigDecimal lucroBruto) {
        List<ContaPaga> contas = new ArrayList<>();
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
                        ORDER BY cp.id_plano_contas
                        """)
                .params(parametros(inicio, fim, idsEmpresa))
                .query((rs, n) -> {
                    BigDecimal valor = rs.getBigDecimal("valor");
                    contas.add(new ContaPaga(
                            rs.getString("id_plano_contas"), rs.getString("descricao"), valor,
                            percentual(valor, vendaLiquida), percentual(valor, lucroBruto)));
                    return 1;
                })
                .list();
        return contas;
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
