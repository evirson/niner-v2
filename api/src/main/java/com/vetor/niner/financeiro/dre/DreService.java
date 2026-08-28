package com.vetor.niner.financeiro.dre;

import com.vetor.niner.financeiro.dre.DreDtos.Comparacao;
import com.vetor.niner.financeiro.dre.DreDtos.DreResponse;
import com.vetor.niner.financeiro.dre.DreDtos.LinhaDre;
import com.vetor.niner.financeiro.dre.DreDtos.PeriodoDre;
import com.vetor.niner.financeiro.dre.DreDtos.Regime;
import com.vetor.niner.financeiro.dre.DreDtos.TipoLinha;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relatório de DRE gerencial (docs/telas/relatorio-dre.md) — dois regimes sobre a mesma estrutura
 * de linhas, montada a partir de {@code cfg_plano_contas.grupo_dre}/{@code sinal}.
 *
 * <p><b>Relatório híbrido, por decisão de produto (2026-08-14):</b> cinco linhas essenciais não
 * têm lançamento contábil no sistema (CMV, taxas de cartão, comissões, descontos e devoluções) —
 * elas são <b>derivadas do movimento real</b> aqui, na consulta, e o resto vem somado dos
 * lançamentos. A alternativa (gerar lançamento a cada venda) mexeria no PDV/cancelamento/devolução
 * e criaria risco de o lançado divergir do movimento.
 *
 * <p><b>Compra de mercadoria nunca entra</b> — {@code cfg_plano_contas.inclui_dre = false} na conta
 * de compra (3.03.x): compra é estoque (ativo), e o custo entra como CMV quando a mercadoria sai
 * vendida, com o custo real gravado na própria linha da venda.
 *
 * <p><b>Fontes de lançamento e a regra anti-dupla-contagem:</b> despesa vem <i>só</i> de
 * {@code contas_pagar}; {@code conta_corrente_movimento} entra <i>só</i> para contas de receita
 * (RECEITA_BRUTA/RESULTADO_FINANCEIRO), que o Contas a Pagar não consegue produzir. Sem essa
 * separação, um pagamento baixado em Contas a Pagar e também lançado na Movimentação de Conta
 * Corrente sairia duas vezes — as tabelas são independentes, dar baixa não gera movimento de
 * caixa (verificado no código em 2026-08-14).
 */
@Service
public class DreService {

    private static final int PERIODO_MAXIMO_DIAS = 400;

    /** Ordem fixa de exibição da DRE. O `sinal` de cada grupo vem do plano de contas. */
    private static final List<GrupoDre> GRUPOS = List.of(
            new GrupoDre("RECEITA_BRUTA", "RECEITA BRUTA"),
            new GrupoDre("DEDUCOES", "(−) DEDUÇÕES"),
            new GrupoDre("CUSTO_VARIAVEL", "(−) CUSTOS VARIÁVEIS"),
            new GrupoDre("DESPESA_FIXA", "(−) DESPESAS FIXAS"),
            new GrupoDre("DEPRECIACAO", "(−) DEPRECIAÇÃO"),
            new GrupoDre("RESULTADO_FINANCEIRO", "(±) RESULTADO FINANCEIRO"),
            new GrupoDre("TRIBUTO_LUCRO", "(−) TRIBUTO SOBRE O LUCRO"));

    private record GrupoDre(String chave, String rotulo) {
    }

    /** Uma conta analítica com movimento no período, dentro de um grupo. */
    private record ValorConta(String idPlanoContas, String descricao, BigDecimal valor) {
    }

    /**
     * Linha derivada do movimento: carrega o **próprio grupo de DRE**, em vez de perguntar ao
     * plano de contas do tenant.
     *
     * <p>Isso não é atalho — é o que mantém a DRE funcionando em qualquer tenant: o signup semeia
     * só 3 contas (a árvore mínima da conta de compra, {@code SignupService}); o plano padrão
     * completo de 76 contas é um seed à parte (`db/scripts/seed_plano_contas_padrao.sql`) que nem
     * todo tenant aplicou. Se a receita/CMV dependessem de `1.01.001`/`3.01.001` existirem em
     * `cfg_plano_contas`, a DRE de um tenant novo viria zerada mesmo com vendas — foi exatamente
     * o que aconteceu no primeiro teste (2026-08-14). O código da conta segue sendo usado como
     * chave de exibição, e a descrição cadastrada pelo lojista prevalece quando a conta existe.
     */
    private record Derivada(String grupo, String idPlanoContas, String descricao, BigDecimal valor) {
    }

    private final JdbcClient jdbc;

    public DreService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DreResponse gerar(
            Jwt jwt, LocalDate dataInicial, LocalDate dataFinal, Regime regime,
            List<Long> idsEmpresaSolicitadas, Comparacao comparacao) {
        // ⛔ O "exigirAdmin" que existia aqui saiu em 2026-08-27: a V078 tirou esta tela de
        // `admin_apenas`, então o administrador podia concedê-la — e o operador tomava 403 numa
        // tela que a grade jurava ter liberado, sem nada explicando por quê. Quem decide agora é a
        // permissão por tela (PermissaoInterceptor). Era o defeito das "duas trancas na mesma
        // porta", que o commit fa85474 removeu de outras dez telas e esqueceu destas duas.
        validarPeriodo(dataInicial, dataFinal);
        Regime regimeEfetivo = regime == null ? Regime.COMPETENCIA : regime;
        Comparacao comparacaoEfetiva = comparacao == null ? Comparacao.NENHUM : comparacao;
        List<Long> idsEmpresa = resolverIdsEmpresa(jwt, idsEmpresaSolicitadas);

        Map<String, List<ValorConta>> atual = apurar(dataInicial, dataFinal, regimeEfetivo, idsEmpresa);
        PeriodoDre periodoComparado = calcularPeriodoComparado(dataInicial, dataFinal, comparacaoEfetiva);
        Map<String, List<ValorConta>> comparado = periodoComparado == null
                ? Map.of()
                : apurar(periodoComparado.dataInicial(), periodoComparado.dataFinal(), regimeEfetivo, idsEmpresa);

        return montarResposta(regimeEfetivo, new PeriodoDre(dataInicial, dataFinal), periodoComparado, atual, comparado);
    }

    /** ADMIN-only (decisão de 2026-08-14): a DRE expõe lucro, despesa e pró-labore. Mesmo
     *  mecanismo de {@code ConfiguracaoTelaService.exigirAdmin} — claim `roles` do JWT. */
    private static void exigirAdmin(Jwt jwt) {
        if (!ehAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas administradores podem consultar a DRE.");
        }
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

    /** ADMIN filtra livremente (vazio = todas); qualquer outro papel não chega aqui (ADMIN-only). */
    private static List<Long> resolverIdsEmpresa(Jwt jwt, List<Long> idsEmpresaSolicitadas) {
        return (idsEmpresaSolicitadas == null || idsEmpresaSolicitadas.isEmpty()) ? null : idsEmpresaSolicitadas;
    }

    /** "Período anterior" tem exatamente o mesmo número de dias e termina na véspera do início —
     *  períodos de tamanhos diferentes não são comparáveis. */
    private static PeriodoDre calcularPeriodoComparado(LocalDate inicial, LocalDate fim, Comparacao comparacao) {
        return switch (comparacao) {
            case NENHUM -> null;
            case ANO_ANTERIOR -> new PeriodoDre(inicial.minusYears(1), fim.minusYears(1));
            case PERIODO_ANTERIOR -> {
                long dias = ChronoUnit.DAYS.between(inicial, fim) + 1;
                yield new PeriodoDre(inicial.minusDays(dias), inicial.minusDays(1));
            }
        };
    }

    // ------------------------------------------------------------------ apuração

    /** Junta as linhas derivadas do movimento com as linhas de lançamento, por grupo de DRE. */
    private Map<String, List<ValorConta>> apurar(
            LocalDate inicio, LocalDate fim, Regime regime, List<Long> idsEmpresa) {
        Map<String, List<ValorConta>> porGrupo = new LinkedHashMap<>();
        for (Derivada linha : derivadasDoMovimento(inicio, fim, regime, idsEmpresa)) {
            adicionar(porGrupo, linha.grupo(),
                    new ValorConta(linha.idPlanoContas(), descricaoDaConta(linha), linha.valor()));
        }
        for (Map.Entry<String, List<ValorConta>> e : lancamentos(inicio, fim, regime, idsEmpresa).entrySet()) {
            for (ValorConta linha : e.getValue()) {
                adicionar(porGrupo, e.getKey(), linha);
            }
        }
        return porGrupo;
    }

    private static void adicionar(Map<String, List<ValorConta>> porGrupo, String grupo, ValorConta linha) {
        if (grupo == null || linha.valor().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        List<ValorConta> lista = porGrupo.computeIfAbsent(grupo, g -> new ArrayList<>());
        for (int i = 0; i < lista.size(); i++) {
            if (lista.get(i).idPlanoContas().equals(linha.idPlanoContas())) {
                ValorConta anterior = lista.get(i);
                lista.set(i, new ValorConta(anterior.idPlanoContas(), anterior.descricao(),
                        anterior.valor().add(linha.valor())));
                return;
            }
        }
        lista.add(linha);
    }

    /** Nome que o lojista deu à conta, quando ela existe no plano dele; senão, o rótulo padrão. */
    private String descricaoDaConta(Derivada derivada) {
        return jdbc.sql("""
                        SELECT descricao FROM cfg_plano_contas
                        WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?
                        """)
                .param(derivada.idPlanoContas())
                .query(String.class)
                .optional()
                .orElse(derivada.descricao());
    }

    /**
     * As cinco linhas que não têm lançamento, derivadas do movimento real.
     *
     * <p>Competência olha {@code venda.data_venda}; caixa olha {@code contas_receber
     * .data_recebimento} e reconhece CMV/comissão <b>proporcionalmente ao recebido</b> (recebi 30%
     * da venda → 30% do custo). Reconhecer o custo pelo pagamento ao fornecedor traria de volta o
     * erro de "compra = despesa" que o {@code inclui_dre = false} da conta de compra evita.
     */
    private List<Derivada> derivadasDoMovimento(
            LocalDate inicio, LocalDate fim, Regime regime, List<Long> idsEmpresa) {
        return regime == Regime.COMPETENCIA
                ? derivadasCompetencia(inicio, fim, idsEmpresa)
                : derivadasCaixa(inicio, fim, idsEmpresa);
    }

    private List<Derivada> derivadasCompetencia(LocalDate inicio, LocalDate fim, List<Long> idsEmpresa) {
        String filtroEmpresaVenda = filtroEmpresa("v.id_empresa", idsEmpresa);
        String filtroEmpresaMovimento = filtroEmpresa("pmm.id_empresa", idsEmpresa);

        // Vendas do período: receita bruta, desconto concedido, CMV e comissão, tudo na mesma
        // varredura do ledger (uma linha por item vendido).
        var vendas = jdbc.sql("""
                        SELECT COALESCE(SUM(pmd.qtd_produto * pmd.preco_venda + pmd.valor_acrescimo), 0) AS receita,
                               COALESCE(SUM(pmd.valor_desconto), 0) AS desconto,
                               COALESCE(SUM(pmd.qtd_produto * pmd.preco_custo), 0) AS cmv,
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
                        """ + filtroEmpresaVenda)
                .params(parametros(inicio, fim, idsEmpresa))
                .query((rs, n) -> new BigDecimal[] {
                        rs.getBigDecimal("receita"), rs.getBigDecimal("desconto"),
                        rs.getBigDecimal("cmv"), rs.getBigDecimal("comissao") })
                .single();

        // Devoluções do período: deduzem receita e revertem o CMV correspondente.
        var devolucoes = jdbc.sql("""
                        SELECT COALESCE(SUM(pmd.qtd_produto * pmd.preco_venda), 0) AS valor,
                               COALESCE(SUM(pmd.qtd_produto * pmd.preco_custo), 0) AS cmv
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                             ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                                AND pmd.credito_debito = 'C'
                        -- ⚠️ Devolução CANCELADA não deduz receita nem reverte CMV (achado de
                        -- auditoria, 2026-08-21). Cancelar não apaga as linhas `DEVOLUCAO` do
                        -- ledger — só marca `venda_devolucao.cancelada` e lança o movimento
                        -- compensatório. Sem o filtro, o resultado do mês errava nas DUAS pontas,
                        -- nos dois regimes, e nada na tela apontava a causa.
                        JOIN venda_devolucao vd
                             ON vd.id_tenant = pmm.id_tenant AND vd.id_devolucao = pmm.id_devolucao
                            AND vd.cancelada = false
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.tipo_movimento = 'DEVOLUCAO'
                              AND (pmm.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                        """ + filtroEmpresaMovimento)
                .params(parametros(inicio, fim, idsEmpresa))
                .query((rs, n) -> new BigDecimal[] { rs.getBigDecimal("valor"), rs.getBigDecimal("cmv") })
                .single();

        // Taxa de cartão/PIX: percentual da carteira sobre o valor pago naquela forma. Em
        // competência conta na data da VENDA, mesmo que a parcela só caia depois.
        BigDecimal taxas = jdbc.sql("""
                        SELECT COALESCE(SUM(cr.valor_receber * tc.taxa_administradora / 100), 0)
                        FROM contas_receber cr
                        JOIN venda v ON v.id_venda = cr.id_venda AND v.id_tenant = cr.id_tenant
                        JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND v.cancelada = false
                              AND (v.data_venda AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ? AND tc.taxa_administradora > 0
                        """ + filtroEmpresaVenda)
                .params(parametros(inicio, fim, idsEmpresa))
                .query(BigDecimal.class).single();

        return montarDerivadas(vendas[0], vendas[1], vendas[2], vendas[3], devolucoes[0], devolucoes[1], taxas);
    }

    private List<Derivada> derivadasCaixa(LocalDate inicio, LocalDate fim, List<Long> idsEmpresa) {
        // `fator` = quanto desta venda foi recebido nesta parcela. CMV e comissão entram
        // proporcionais; a receita é o principal (valor_receber) e o que veio a mais
        // (valor_recebido − valor_receber = juros/multa) é resultado financeiro.
        String filtroEmpresaVenda = filtroEmpresa("v.id_empresa", idsEmpresa);
        var recebido = jdbc.sql("""
                        WITH total_venda AS (
                            SELECT cr.id_venda, SUM(cr.valor_receber) AS total
                            FROM contas_receber cr
                            WHERE cr.id_tenant = plataforma.tenant_atual()
                            GROUP BY cr.id_venda
                        ),
                        custo_venda AS (
                            SELECT pmm.id_venda,
                                   SUM(pmd.qtd_produto * pmd.preco_custo) AS cmv,
                                   SUM((pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto)
                                       * COALESCE(fn.perc_comissao, 0) / 100) AS comissao
                            FROM produto_movimento_mestre pmm
                            JOIN produto_movimento_detalhe pmd
                                 ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                                    AND pmd.credito_debito = 'D'
                            LEFT JOIN funcionario fn
                                 ON fn.id_funcionario = pmd.id_funcionario AND fn.id_tenant = pmd.id_tenant
                            WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.tipo_movimento = 'VENDA'
                            GROUP BY pmm.id_venda
                        )
                        SELECT COALESCE(SUM(cr.valor_receber), 0) AS receita,
                               COALESCE(SUM(cr.valor_desconto), 0) AS desconto,
                               COALESCE(SUM(cr.valor_recebido - cr.valor_receber), 0) AS juros,
                               COALESCE(SUM(cr.valor_receber * tc.taxa_administradora / 100), 0) AS taxas,
                               COALESCE(SUM(cv.cmv * (cr.valor_receber / NULLIF(tv.total, 0))), 0) AS cmv,
                               COALESCE(SUM(cv.comissao * (cr.valor_receber / NULLIF(tv.total, 0))), 0) AS comissao
                        FROM contas_receber cr
                        JOIN venda v ON v.id_venda = cr.id_venda AND v.id_tenant = cr.id_tenant
                        JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                        LEFT JOIN total_venda tv ON tv.id_venda = cr.id_venda
                        LEFT JOIN custo_venda cv ON cv.id_venda = cr.id_venda
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND v.cancelada = false
                              AND cr.data_recebimento IS NOT NULL
                              AND (cr.data_recebimento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                              -- ⚠️ Parcela que JÁ VEIO PAGA na migração não é receita do Nainer
                              -- (V059, 2026-08-22 — auditoria item 22). A venda `IMPORTACAO` é uma
                              -- casca sem item nem movimento, então o LEFT JOIN em custo_venda não
                              -- acha nada e o COALESCE zera o CMV: R$ 45.000 de parcelas migradas
                              -- apareciam como receita com custo R$ 0,00 — margem de 100%, sobre
                              -- mercadoria comprada e vendida no sistema anterior. E era incoerente
                              -- com o Fluxo de Caixa, que nunca recebeu esse dinheiro (o importador
                              -- não cria lançamento de caixa para parcela já paga).
                              --
                              -- O corte é por INSTANTE, não pela origem inteira: parcela legada que
                              -- estava em aberto e o cliente veio pagar DEPOIS, pelo Recebimento de
                              -- Crediário, entrou no caixa de verdade e continua sendo receita.
                              AND (v.importado_em IS NULL OR cr.data_recebimento >= v.importado_em)
                        """ + filtroEmpresaVenda)
                .params(parametros(inicio, fim, idsEmpresa))
                .query((rs, n) -> new BigDecimal[] {
                        rs.getBigDecimal("receita"), rs.getBigDecimal("desconto"), rs.getBigDecimal("juros"),
                        rs.getBigDecimal("taxas"), rs.getBigDecimal("cmv"), rs.getBigDecimal("comissao") })
                .single();

        List<Derivada> linhas = new ArrayList<>(montarDerivadas(
                recebido[0], recebido[1], recebido[4], recebido[5], BigDecimal.ZERO, BigDecimal.ZERO, recebido[3]));
        // Juros/multa de crediário recebidos são receita financeira, não receita de venda.
        linhas.add(new Derivada("RESULTADO_FINANCEIRO", "6.01.001", "Juros e Multas Recebidos",
                arredondar(recebido[2])));
        return linhas;
    }

    /** Mapeia os valores derivados para as contas do plano padrão — cada uma com o grupo fixo,
     *  independente do plano de contas do tenant (ver javadoc de {@link Derivada}). */
    private static List<Derivada> montarDerivadas(
            BigDecimal receita, BigDecimal desconto, BigDecimal cmv, BigDecimal comissao,
            BigDecimal devolucao, BigDecimal cmvDevolucao, BigDecimal taxas) {
        List<Derivada> linhas = new ArrayList<>();
        linhas.add(new Derivada("RECEITA_BRUTA", "1.01.001", "Venda de Mercadorias — Loja", arredondar(receita)));
        linhas.add(new Derivada("DEDUCOES", "2.02.001", "Devoluções de Vendas", arredondar(devolucao)));
        linhas.add(new Derivada("DEDUCOES", "2.02.002", "Descontos Concedidos", arredondar(desconto)));
        // O CMV das devoluções volta pro estoque, então reduz o custo do período.
        linhas.add(new Derivada("CUSTO_VARIAVEL", "3.01.001", "CMV — Custo das Mercadorias Vendidas",
                arredondar(cmv.subtract(cmvDevolucao))));
        linhas.add(new Derivada("CUSTO_VARIAVEL", "3.02.001", "Comissões sobre Vendas", arredondar(comissao)));
        linhas.add(new Derivada("CUSTO_VARIAVEL", "3.02.002", "Taxas de Cartão e PIX", arredondar(taxas)));
        return linhas;
    }

    /**
     * Linhas que vêm de lançamento, somadas por conta analítica.
     *
     * <p>Despesa só de {@code contas_pagar} (por {@code data_lancamento} em competência — o fato
     * gerador — ou {@code data_pagamento} em caixa). {@code conta_corrente_movimento} entra só para
     * contas de receita, que o Contas a Pagar não produz: é o que permite lançar "outras receitas"
     * sem criar dupla contagem com um pagamento baixado nos dois lugares.
     */
    private Map<String, List<ValorConta>> lancamentos(
            LocalDate inicio, LocalDate fim, Regime regime, List<Long> idsEmpresa) {
        String colunaData = regime == Regime.COMPETENCIA ? "cp.data_lancamento" : "cp.data_pagamento";
        // ⚠️ NULLIF é obrigatório aqui, não é defensividade. `contas_pagar.valor_pago` é
        // `numeric(12,2) NOT NULL DEFAULT 0` (V026:21) e ContaPagarService grava ZERO quando a tela
        // manda o campo vazio — ou seja, a coluna NUNCA é NULL e um COALESCE puro jamais cai no
        // fallback. Baixa "cheia" (só a data informada) entrava na DRE como R$ 0,00 enquanto o
        // caixa recebia o valor certo, e a mesma baixa aparecia com valores diferentes no Fluxo de
        // Caixa e na DRE — lucro inflado. Esta expressão reproduz exatamente a regra de
        // ContaPagarService.sincronizarMovimentoDeDinheiro: valor_pago quando informado E não-zero,
        // senão valor_pagar. Se uma das duas mudar, a outra tem que mudar junto.
        String valor = regime == Regime.COMPETENCIA
                ? "cp.valor_pagar"
                : "COALESCE(NULLIF(cp.valor_pago, 0), cp.valor_pagar)";

        Map<String, List<ValorConta>> porGrupo = new LinkedHashMap<>();

        // SQL montado com `formatted` (não concatenando text blocks): `colunaData` e `valor` são
        // constantes escolhidas por enum aqui dentro, nunca texto vindo do cliente.
        String sqlContasPagar = """
                SELECT pc.grupo_dre::text AS grupo, cp.id_plano_contas, pc.descricao,
                       COALESCE(SUM(%s), 0) AS valor
                FROM contas_pagar cp
                JOIN cfg_plano_contas pc
                     ON pc.id_plano_contas = cp.id_plano_contas AND pc.id_tenant = cp.id_tenant
                WHERE cp.id_tenant = plataforma.tenant_atual() AND pc.inclui_dre = true
                      AND pc.grupo_dre <> 'NAO_APLICA'
                      AND %s IS NOT NULL
                      AND (%s AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?%s
                GROUP BY pc.grupo_dre, cp.id_plano_contas, pc.descricao
                """.formatted(valor, colunaData, colunaData, filtroEmpresa("cp.id_empresa", idsEmpresa));

        jdbc.sql(sqlContasPagar)
                .params(parametros(inicio, fim, idsEmpresa))
                .query((rs, n) -> {
                    adicionar(porGrupo, rs.getString("grupo"), new ValorConta(
                            rs.getString("id_plano_contas"), rs.getString("descricao"), rs.getBigDecimal("valor")));
                    return 1;
                })
                .list();

        // Movimento de conta corrente: só contas de receita (ver javadoc). `credito_debito = 'C'`
        // é entrada de dinheiro; a conta corrente não tem id_empresa, então não entra no filtro.
        jdbc.sql("""
                        SELECT pc.grupo_dre::text AS grupo, ccm.id_plano_contas, pc.descricao,
                               COALESCE(SUM(ccm.valor), 0) AS valor
                        FROM conta_corrente_movimento ccm
                        JOIN cfg_plano_contas pc
                             ON pc.id_plano_contas = ccm.id_plano_contas AND pc.id_tenant = ccm.id_tenant
                        WHERE ccm.id_tenant = plataforma.tenant_atual() AND pc.inclui_dre = true
                              AND pc.grupo_dre IN ('RECEITA_BRUTA', 'RESULTADO_FINANCEIRO')
                              AND ccm.credito_debito = 'C'
                              AND (ccm.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                        GROUP BY pc.grupo_dre, ccm.id_plano_contas, pc.descricao
                        """)
                .params(inicio, fim)
                .query((rs, n) -> {
                    adicionar(porGrupo, rs.getString("grupo"), new ValorConta(
                            rs.getString("id_plano_contas"), rs.getString("descricao"), rs.getBigDecimal("valor")));
                    return 1;
                })
                .list();

        return porGrupo;
    }

    // ------------------------------------------------------------------ montagem

    /**
     * Monta as linhas na ordem fixa da DRE. Todo valor sai com o <b>sinal do efeito no
     * resultado</b> (dedução/custo/despesa negativos), então a tela só precisa somar na ordem.
     * AV é sobre a <b>receita líquida</b> (base que sobrevive a mudança de imposto/devolução).
     */
    private DreResponse montarResposta(
            Regime regime, PeriodoDre periodo, PeriodoDre periodoComparado,
            Map<String, List<ValorConta>> atual, Map<String, List<ValorConta>> comparado) {
        BigDecimal receitaLiquida = liquida(atual);

        List<LinhaDre> linhas = new ArrayList<>();
        BigDecimal acumulado = BigDecimal.ZERO;
        BigDecimal acumuladoComparado = BigDecimal.ZERO;

        for (GrupoDre grupo : GRUPOS) {
            BigDecimal totalGrupo = total(atual, grupo.chave());
            BigDecimal totalGrupoComparado = total(comparado, grupo.chave());
            acumulado = acumulado.add(totalGrupo);
            acumuladoComparado = acumuladoComparado.add(totalGrupoComparado);

            linhas.add(linha(grupo.chave(), grupo.rotulo(), 1, TipoLinha.GRUPO,
                    totalGrupo, totalGrupoComparado, receitaLiquida, periodoComparado != null));
            for (ValorConta conta : atual.getOrDefault(grupo.chave(), List.of())) {
                BigDecimal valor = comSinal(grupo.chave(), conta.valor());
                linhas.add(linha(conta.idPlanoContas(), conta.descricao(), 2, TipoLinha.CONTA,
                        valor, valorDaConta(comparado, grupo.chave(), conta.idPlanoContas()),
                        receitaLiquida, periodoComparado != null));
            }

            // Subtotais nascem logo depois do grupo que os fecha.
            if ("DEDUCOES".equals(grupo.chave())) {
                linhas.add(linha("RECEITA_LIQUIDA", "= RECEITA LÍQUIDA", 1, TipoLinha.SUBTOTAL,
                        acumulado, acumuladoComparado, receitaLiquida, periodoComparado != null));
            } else if ("CUSTO_VARIAVEL".equals(grupo.chave())) {
                linhas.add(linha("MARGEM_CONTRIBUICAO", "= MARGEM DE CONTRIBUIÇÃO", 1, TipoLinha.SUBTOTAL,
                        acumulado, acumuladoComparado, receitaLiquida, periodoComparado != null));
            } else if ("DESPESA_FIXA".equals(grupo.chave())) {
                linhas.add(linha("RESULTADO_OPERACIONAL", "= RESULTADO OPERACIONAL (EBITDA)", 1, TipoLinha.SUBTOTAL,
                        acumulado, acumuladoComparado, receitaLiquida, periodoComparado != null));
            }
        }

        linhas.add(linha("RESULTADO_LIQUIDO", "= RESULTADO LÍQUIDO DO PERÍODO", 1, TipoLinha.SUBTOTAL,
                acumulado, acumuladoComparado, receitaLiquida, periodoComparado != null));

        return new DreResponse(regime, periodo, periodoComparado, linhas,
                receitaLiquida, arredondar(acumulado));
    }

    private static LinhaDre linha(
            String chave, String rotulo, int nivel, TipoLinha tipo, BigDecimal valor,
            BigDecimal valorComparado, BigDecimal receitaLiquida, boolean temComparacao) {
        BigDecimal valorArredondado = arredondar(valor);
        if (!temComparacao) {
            return new LinhaDre(chave, rotulo, nivel, tipo, valorArredondado,
                    percentual(valorArredondado, receitaLiquida), null, null, null);
        }
        BigDecimal comparadoArredondado = arredondar(valorComparado);
        BigDecimal variacao = valorArredondado.subtract(comparadoArredondado);
        return new LinhaDre(chave, rotulo, nivel, tipo, valorArredondado,
                percentual(valorArredondado, receitaLiquida),
                comparadoArredondado, variacao, percentual(variacao, comparadoArredondado.abs()));
    }

    /** {@code null} quando não há base — não existe "% de zero", e devolver 0 mentiria. */
    private static BigDecimal percentual(BigDecimal valor, BigDecimal base) {
        if (base == null || base.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return valor.multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal liquida(Map<String, List<ValorConta>> valores) {
        return arredondar(total(valores, "RECEITA_BRUTA").add(total(valores, "DEDUCOES")));
    }

    private static BigDecimal total(Map<String, List<ValorConta>> valores, String grupo) {
        BigDecimal soma = BigDecimal.ZERO;
        for (ValorConta conta : valores.getOrDefault(grupo, List.of())) {
            soma = soma.add(comSinal(grupo, conta.valor()));
        }
        return soma;
    }

    private static BigDecimal valorDaConta(Map<String, List<ValorConta>> valores, String grupo, String idPlanoContas) {
        for (ValorConta conta : valores.getOrDefault(grupo, List.of())) {
            if (conta.idPlanoContas().equals(idPlanoContas)) {
                return comSinal(grupo, conta.valor());
            }
        }
        return BigDecimal.ZERO;
    }

    /** Receita soma; dedução, custo, despesa, depreciação e tributo subtraem. Resultado financeiro
     *  entra como veio (pode ser receita ou despesa). */
    private static BigDecimal comSinal(String grupo, BigDecimal valor) {
        return switch (grupo) {
            case "RECEITA_BRUTA", "RESULTADO_FINANCEIRO" -> valor;
            default -> valor.negate();
        };
    }

    private static BigDecimal arredondar(BigDecimal valor) {
        return valor == null ? BigDecimal.ZERO.setScale(2) : valor.setScale(2, RoundingMode.HALF_UP);
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

    private static boolean ehAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }
}
