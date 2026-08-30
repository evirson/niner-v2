package com.vetor.niner.financeiro.fluxocaixa;

import com.vetor.niner.comum.tempo.FusoDaLoja;
import com.vetor.niner.financeiro.fluxocaixa.FluxoCaixaDtos.Agrupamento;
import com.vetor.niner.financeiro.fluxocaixa.FluxoCaixaDtos.Atividade;
import com.vetor.niner.financeiro.fluxocaixa.FluxoCaixaDtos.FluxoProjecaoResponse;
import com.vetor.niner.financeiro.fluxocaixa.FluxoCaixaDtos.FluxoRealizadoResponse;
import com.vetor.niner.financeiro.fluxocaixa.FluxoCaixaDtos.LinhaAtividade;
import com.vetor.niner.financeiro.fluxocaixa.FluxoCaixaDtos.LinhaProjecao;
import com.vetor.niner.financeiro.fluxocaixa.FluxoCaixaDtos.OrigemDinheiro;
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
 * Fluxo de Caixa (docs/telas/fluxo-caixa.md) — realizado (o que aconteceu) e projeção (o que vem).
 *
 * <p><b>Realizado lê só movimento de dinheiro</b> ({@code caixa_detalhe} +
 * {@code conta_corrente_movimento}), nunca lançamento — é isso que garante a identidade
 * "saldo inicial + entradas − saídas = saldo real". Isso só passou a ser suficiente em 2026-08-14,
 * quando a baixa de conta a pagar passou a gravar o movimento correspondente
 * ({@code ContaPagarService}); antes disso as saídas simplesmente não existiam nas tabelas de
 * dinheiro. Contas pagas antes dessa data não aparecem aqui, por decisão registrada na spec.
 *
 * <p><b>Classificação por atividade</b> vem de {@code cfg_plano_contas.grupo_dfc}. Quando o
 * movimento não tem plano de contas — caso do PDV e do Recebimento de Crediário, que gravam
 * {@code caixa_detalhe} sem {@code id_plano_contas} — cai em OPERACIONAL, que é o que uma venda
 * recebida é de fato. <b>O dinheiro é sempre contado, mesmo sem classificação</b>: preferir o
 * saldo correto a uma classificação bonita é o que mantém o relatório confiável.
 *
 * <p><b>Projeção não inventa venda futura</b> — só compromissos já registrados (contas a receber e
 * a pagar em aberto). Previsão de faturamento é outra feature, e outro tipo de erro.
 */
@Service
public class FluxoCaixaService {

    private static final int PERIODO_MAXIMO_DIAS = 400;

    private static final Map<String, String> ROTULO_ATIVIDADE = Map.of(
            "OPERACIONAL", "ATIVIDADES OPERACIONAIS",
            "INVESTIMENTO", "ATIVIDADES DE INVESTIMENTO",
            "FINANCIAMENTO", "ATIVIDADES DE FINANCIAMENTO");

    private static final List<String> ORDEM_ATIVIDADES = List.of("OPERACIONAL", "INVESTIMENTO", "FINANCIAMENTO");

    private final JdbcClient jdbc;
    private final FusoDaLoja fusoDaLoja;

    public FluxoCaixaService(JdbcClient jdbc, FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.fusoDaLoja = fusoDaLoja;
    }

    // ------------------------------------------------------------------ realizado

    @Transactional(readOnly = true)
    public FluxoRealizadoResponse realizado(
            Jwt jwt, LocalDate dataInicial, LocalDate dataFinal, List<Long> idsEmpresa, OrigemDinheiro origem) {
        validarPeriodo(dataInicial, dataFinal);
        OrigemDinheiro origemEfetiva = origem == null ? OrigemDinheiro.TODAS : origem;
        List<Long> empresas = resolverEmpresas(jwt, idsEmpresa);
        // ⚠️ O fuso da LOJA vale para o SQL também, não só para o `hoje` do Java (auditoria
        // 2026-08-29). Ver o javadoc de `parametros`.
        String fuso = fusoDaLoja.daSessao(jwt).getId();

        BigDecimal saldoInicial = saldoAte(fuso, dataInicial.minusDays(1), empresas, origemEfetiva);
        List<Movimento> movimentos = movimentosDoPeriodo(fuso, dataInicial, dataFinal, empresas, origemEfetiva);

        Map<String, Map<String, BigDecimal>> porAtividade = new LinkedHashMap<>();
        BigDecimal entradas = BigDecimal.ZERO;
        BigDecimal saidas = BigDecimal.ZERO;
        for (Movimento m : movimentos) {
            porAtividade.computeIfAbsent(m.grupo(), g -> new LinkedHashMap<>())
                    .merge(m.rotulo(), m.valor(), BigDecimal::add);
            if (m.valor().signum() >= 0) {
                entradas = entradas.add(m.valor());
            } else {
                saidas = saidas.add(m.valor().abs());
            }
        }

        List<Atividade> atividades = new ArrayList<>();
        for (String grupo : ORDEM_ATIVIDADES) {
            Map<String, BigDecimal> linhas = porAtividade.getOrDefault(grupo, Map.of());
            BigDecimal total = linhas.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            atividades.add(new Atividade(grupo, ROTULO_ATIVIDADE.get(grupo), arredondar(total),
                    linhas.entrySet().stream()
                            .map(e -> new LinhaAtividade(e.getKey(), e.getKey(), arredondar(e.getValue())))
                            .toList()));
        }

        BigDecimal saldoFinal = saldoInicial.add(entradas).subtract(saidas);
        // "Hoje" é o da LOJA do usuário, não o da JVM (que só tem fuso definido em produção).
        LocalDate hoje = fusoDaLoja.hoje(jwt);
        BigDecimal saldoRealAtual = saldoAte(fuso, hoje, empresas, origemEfetiva);
        // Conciliação só faz sentido quando o período alcança hoje — comparar o saldo final de um
        // período antigo com o saldo de hoje acusaria uma "diferença" que é só o tempo passando.
        BigDecimal diferenca = dataFinal.isBefore(hoje)
                ? null
                : arredondar(saldoFinal.subtract(saldoRealAtual));

        return new FluxoRealizadoResponse(dataInicial, dataFinal, arredondar(saldoInicial), atividades,
                arredondar(entradas), arredondar(saidas), arredondar(saldoFinal),
                arredondar(saldoRealAtual), diferenca);
    }

    private record Movimento(String grupo, String rotulo, BigDecimal valor) {
    }

    /** Um movimento por linha, já com sinal (crédito positivo, débito negativo) e classificado. */
    private List<Movimento> movimentosDoPeriodo(
            String fuso, LocalDate inicio, LocalDate fim, List<Long> empresas, OrigemDinheiro origem) {
        List<Movimento> movimentos = new ArrayList<>();

        if (origem != OrigemDinheiro.CONTA_CORRENTE) {
            // ⭐ A contrapartida do fundo no período é a VARIAÇÃO dele, não a soma das aberturas
            // (2026-08-29). O relatório promete `saldo inicial + movimentos = saldo final`, e
            // `saldoAte` passou a contar o fundo uma vez por operador (ver `fundoDeCaixaAte`):
            // somar aqui todas as aberturas quebraria a igualdade em toda loja que abre o caixa
            // mais de uma vez no período.
            // ⚠️ O caso que motivou esta linha em 2026-08-14 continua certo: no PRIMEIRO dia de
            // uso o fundo anterior é 0 e o do fim é 200 — a variação é 200, exatamente o que o
            // teste exigia ("acusou −150 onde o dinheiro real era 850"). No vigésimo dia a
            // variação é 0, que também é a verdade: nenhum dinheiro novo entrou na gaveta.
            BigDecimal fundoAntes = fundoDeCaixaAte(fuso, inicio.minusDays(1), empresas);
            BigDecimal fundoDepois = fundoDeCaixaAte(fuso, fim, empresas);
            BigDecimal variacaoDoFundo = fundoDepois.subtract(fundoAntes);
            if (variacaoDoFundo.signum() != 0) {
                movimentos.add(new Movimento("OPERACIONAL",
                        variacaoDoFundo.signum() > 0 ? "Fundo de troco (aporte)" : "Fundo de troco (retirada)",
                        variacaoDoFundo));
            }

            movimentos.addAll(jdbc.sql("""
                            SELECT COALESCE(NULLIF(pc.grupo_dfc::text, 'NAO_APLICA'), 'OPERACIONAL') AS grupo,
                                   COALESCE(pc.descricao, CASE cd.tipo_operacao::text
                                       WHEN 'RECEBIMENTO_VENDA' THEN 'Recebimento de venda'
                                       WHEN 'RECEBIMENTO_PARCELA_CREDIARIO' THEN 'Recebimento de crediário'
                                       WHEN 'TROCO' THEN 'Troco'
                                       WHEN 'CREDITO_CAIXA' THEN 'Suprimento de caixa'
                                       ELSE 'Sangria / saída de caixa' END) AS rotulo,
                                   SUM(CASE WHEN cd.credito_debito = 'C' THEN cd.valor ELSE -cd.valor END) AS valor
                            FROM caixa_detalhe cd
                            JOIN caixa_mestre cm ON cm.id_caixa = cd.id_caixa AND cm.id_tenant = cd.id_tenant
                            LEFT JOIN cfg_plano_contas pc
                                 ON pc.id_plano_contas = cd.id_plano_contas AND pc.id_tenant = cd.id_tenant
                            -- ⛔ VALE-MERCADORIA NÃO É DINHEIRO (auditoria 2026-08-29, rodada 1). O resgate
                            -- credita `caixa_detalhe` como qualquer outra carteira, e do outro lado a
                            -- emissão do vale NÃO lança nada — não existe contrapartida. Venda de R$ 90
                            -- em dinheiro, devolução com vale de R$ 90, e a venda seguinte paga com ele:
                            -- o saldo dizia R$ 180 com R$ 90 na gaveta, e a linha `Diferença` não
                            -- denunciava porque `saldoFinal` e `saldoRealAtual` saem da MESMA soma
                            -- inflada — o mesmo modo de falha do fundo de caixa. Pior: é cumulativo e
                            -- permanente, porque `exigirExcedenteSangrado` só cobra a carteira de
                            -- abertura, então esse crédito nunca sai do acumulado.
                            -- ⚠️ A linha CONTINUA em `caixa_detalhe`, de propósito: o fechamento agrupa
                            -- por carteira e o operador confere o vale físico que recebeu. O que ela não
                            -- pode é entrar num relatório que responde "quanto dinheiro existe".
                            LEFT JOIN tipo_carteira tcv
                                 ON tcv.id_carteira = cd.id_carteira AND tcv.id_tenant = cd.id_tenant
                            WHERE cd.id_tenant = plataforma.tenant_atual()
                                  AND (cd.criado_em AT TIME ZONE ?::text)::date BETWEEN ? AND ?
                                  AND COALESCE(tcv.categoria_carteira::text, '') <> 'VALE_MERCADORIA'
                            """ + filtroEmpresa("cm.id_empresa", empresas) + """

                            GROUP BY 1, 2
                            """)
                    .params(parametros(fuso, inicio, fim, empresas))
                    .query((rs, n) -> new Movimento(rs.getString("grupo"), rs.getString("rotulo"), rs.getBigDecimal("valor")))
                    .list());
        }

        if (origem != OrigemDinheiro.CAIXA) {
            // ⚠️ `conta_corrente` TEM `id_empresa` (V028, NOT NULL, com FK) — o comentário que
            // ficava aqui afirmava o contrário, e por causa dele o banco entrava SEM filtro de
            // empresa (auditoria 2026-08-29). O Fluxo de Caixa da Filial somava os R$ 200 mil da
            // Matriz, e a linha de conciliação não acusava nada porque `saldoAte` errava do mesmo
            // jeito nos dois lados. O JOIN é o que traz a empresa da conta.
            movimentos.addAll(jdbc.sql("""
                            SELECT COALESCE(NULLIF(pc.grupo_dfc::text, 'NAO_APLICA'), 'OPERACIONAL') AS grupo,
                                   pc.descricao AS rotulo,
                                   SUM(CASE WHEN ccm.credito_debito = 'C' THEN ccm.valor ELSE -ccm.valor END) AS valor
                            FROM conta_corrente_movimento ccm
                            JOIN conta_corrente cc
                                 ON cc.id_conta_corrente = ccm.id_conta_corrente AND cc.id_tenant = ccm.id_tenant
                            JOIN cfg_plano_contas pc
                                 ON pc.id_plano_contas = ccm.id_plano_contas AND pc.id_tenant = ccm.id_tenant
                            WHERE ccm.id_tenant = plataforma.tenant_atual()
                                  AND (ccm.data_movimento AT TIME ZONE ?::text)::date BETWEEN ? AND ?
                            """ + filtroEmpresa("cc.id_empresa", empresas) + """
                            GROUP BY 1, 2
                            """)
                    .params(parametros(fuso, inicio, fim, empresas))
                    .query((rs, n) -> new Movimento(rs.getString("grupo"), rs.getString("rotulo"), rs.getBigDecimal("valor")))
                    .list());
        }
        return movimentos;
    }

    /**
     * Saldo acumulado até uma data (inclusive). Soma o {@code saldo_inicial} dos caixas abertos até
     * lá — é dinheiro que estava na gaveta e não tem lançamento em {@code caixa_detalhe}; ignorá-lo
     * faria o saldo do relatório nunca bater com o dinheiro real.
     */
    /**
     * O fundo de troco que está fisicamente na gaveta na data — <b>uma vez por operador</b>, não
     * uma vez por abertura.
     *
     * <h2>⛔ O que estava errado</h2>
     *
     * <p>Era {@code SUM(saldo_inicial)} de <b>todas</b> as aberturas até a data. Uma loja que abre
     * todo dia com R$ 200 (a mesma cédula que dorme na gaveta) e não vende nada mostrava, após 20
     * dias úteis, <b>"saldo real: R$ 4.000"</b> — com R$ 200 na gaveta. A Projeção parte desse
     * número, e a conciliação não denunciava porque os dois lados usavam a mesma soma inflada.
     *
     * <h2>A decisão (dono do produto, 2026-08-29)</h2>
     *
     * <p><i>"Fundo de caixa compõe o saldo final"</i> — ele conta. E a Sangria de Caixa (V094),
     * criada no mesmo pedido, é o que tira o resto: o dinheiro vendido sai da gaveta para a conta
     * corrente e aparece do outro lado.
     *
     * <p>⭐ Juntando as duas coisas, a conta que fecha é esta: <b>o fundo do último caixa de cada
     * operador</b>. É o dinheiro que ele tem na mão agora; o de ontem é a mesma cédula, e o que
     * saiu virou sangria e está no banco. {@code DISTINCT ON} por (empresa, usuário) porque duas
     * pessoas com caixa próprio têm duas gavetas de verdade.
     *
     * <p>⚠️ Funciona para data passada também: pergunta "qual era a última abertura ATÉ aquele
     * dia", não "qual está aberto agora".
     */
    private BigDecimal fundoDeCaixaAte(String fuso, LocalDate data, List<Long> empresas) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(ultimo.saldo_inicial), 0) FROM (
                            SELECT DISTINCT ON (cm.id_empresa, cm.id_usuario) cm.saldo_inicial
                              FROM caixa_mestre cm
                             WHERE cm.id_tenant = plataforma.tenant_atual()
                               AND (cm.data_abertura AT TIME ZONE ?::text)::date <= ?
                        """ + filtroEmpresa("cm.id_empresa", empresas) + """

                             ORDER BY cm.id_empresa, cm.id_usuario, cm.data_abertura DESC, cm.id_caixa DESC
                        ) ultimo
                        """)
                .params(parametrosData(fuso, data, empresas))
                .query(BigDecimal.class).single();
    }

    private BigDecimal saldoAte(String fuso, LocalDate data, List<Long> empresas, OrigemDinheiro origem) {
        BigDecimal saldo = BigDecimal.ZERO;

        if (origem != OrigemDinheiro.CONTA_CORRENTE) {
            saldo = saldo.add(fundoDeCaixaAte(fuso, data, empresas));

            saldo = saldo.add(jdbc.sql("""
                            SELECT COALESCE(SUM(CASE WHEN cd.credito_debito = 'C' THEN cd.valor ELSE -cd.valor END), 0)
                            FROM caixa_detalhe cd
                            JOIN caixa_mestre cm ON cm.id_caixa = cd.id_caixa AND cm.id_tenant = cd.id_tenant
                            -- ⛔ Vale-mercadoria fora: não é dinheiro na gaveta. Ver `movimentosDoPeriodo`.
                            LEFT JOIN tipo_carteira tcv
                                 ON tcv.id_carteira = cd.id_carteira AND tcv.id_tenant = cd.id_tenant
                            WHERE cd.id_tenant = plataforma.tenant_atual()
                                  AND (cd.criado_em AT TIME ZONE ?::text)::date <= ?
                                  AND COALESCE(tcv.categoria_carteira::text, '') <> 'VALE_MERCADORIA'
                            """ + filtroEmpresa("cm.id_empresa", empresas))
                    .params(parametrosData(fuso, data, empresas))
                    .query(BigDecimal.class).single());
        }

        if (origem != OrigemDinheiro.CAIXA) {
            saldo = saldo.add(jdbc.sql("""
                            SELECT COALESCE(SUM(CASE WHEN ccm.credito_debito = 'C' THEN ccm.valor ELSE -ccm.valor END), 0)
                            FROM conta_corrente_movimento ccm
                            JOIN conta_corrente cc
                                 ON cc.id_conta_corrente = ccm.id_conta_corrente AND cc.id_tenant = ccm.id_tenant
                            WHERE ccm.id_tenant = plataforma.tenant_atual()
                                  AND (ccm.data_movimento AT TIME ZONE ?::text)::date <= ?
                            """ + filtroEmpresa("cc.id_empresa", empresas) + """
                            """)
                    .params(parametrosData(fuso, data, empresas))
                    .query(BigDecimal.class).single());
        }
        return saldo;
    }

    // ------------------------------------------------------------------ projeção

    @Transactional(readOnly = true)
    public FluxoProjecaoResponse projecao(
            Jwt jwt, LocalDate dataInicial, LocalDate dataFinal, List<Long> idsEmpresa, Agrupamento agrupamento) {
        validarPeriodo(dataInicial, dataFinal);
        Agrupamento agrupamentoEfetivo = agrupamento == null ? Agrupamento.DIA : agrupamento;
        List<Long> empresas = resolverEmpresas(jwt, idsEmpresa);
        String fuso = fusoDaLoja.daSessao(jwt).getId();

        BigDecimal saldoAtual = saldoAte(fuso, fusoDaLoja.hoje(jwt), empresas, OrigemDinheiro.TODAS);

        // Vencidos entram no primeiro balde (data inicial), não na data original — senão o saldo
        // projetado mentiria sobre o presente, mostrando dinheiro que já era pra ter entrado.
        String truncamento = switch (agrupamentoEfetivo) {
            case DIA -> "day";
            case SEMANA -> "week";
            case MES -> "month";
        };

        Map<LocalDate, BigDecimal[]> porFaixa = new LinkedHashMap<>();

        jdbc.sql("""
                        SELECT GREATEST(date_trunc(?, cr.data_vencimento AT TIME ZONE ?::text)::date, ?::date) AS faixa,
                               COALESCE(SUM(cr.valor_receber), 0) AS valor
                        FROM contas_receber cr
                        JOIN venda v ON v.id_venda = cr.id_venda AND v.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND v.cancelada = false
                              AND cr.data_recebimento IS NULL
                              AND (cr.data_vencimento AT TIME ZONE ?::text)::date <= ?
                        """ + filtroEmpresa("v.id_empresa", empresas) + """

                        GROUP BY 1
                        """)
                .params(paramsProjecao(truncamento, fuso, dataInicial, dataFinal, empresas))
                .query((rs, n) -> {
                    porFaixa.computeIfAbsent(rs.getObject("faixa", LocalDate.class), d -> novoAcumulador())[0] =
                            rs.getBigDecimal("valor");
                    return 1;
                })
                .list();

        jdbc.sql("""
                        SELECT GREATEST(date_trunc(?, cp.data_vencimento AT TIME ZONE ?::text)::date, ?::date) AS faixa,
                               COALESCE(SUM(cp.valor_pagar), 0) AS valor
                        FROM contas_pagar cp
                        WHERE cp.id_tenant = plataforma.tenant_atual() AND cp.data_pagamento IS NULL
                              AND (cp.data_vencimento AT TIME ZONE ?::text)::date <= ?
                        """ + filtroEmpresa("cp.id_empresa", empresas) + """

                        GROUP BY 1
                        """)
                .params(paramsProjecao(truncamento, fuso, dataInicial, dataFinal, empresas))
                .query((rs, n) -> {
                    porFaixa.computeIfAbsent(rs.getObject("faixa", LocalDate.class), d -> novoAcumulador())[1] =
                            rs.getBigDecimal("valor");
                    return 1;
                })
                .list();

        List<LocalDate> datas = new ArrayList<>(porFaixa.keySet());
        datas.sort(LocalDate::compareTo);

        List<LinhaProjecao> linhas = new ArrayList<>();
        BigDecimal acumulado = saldoAtual;
        BigDecimal totalEntradas = BigDecimal.ZERO;
        BigDecimal totalSaidas = BigDecimal.ZERO;
        LocalDate primeiraNegativa = null;
        BigDecimal valorFaltante = null;

        for (LocalDate data : datas) {
            BigDecimal[] valores = porFaixa.get(data);
            BigDecimal entradas = valores[0];
            BigDecimal saidas = valores[1];
            BigDecimal doPeriodo = entradas.subtract(saidas);
            acumulado = acumulado.add(doPeriodo);
            totalEntradas = totalEntradas.add(entradas);
            totalSaidas = totalSaidas.add(saidas);
            if (primeiraNegativa == null && acumulado.signum() < 0) {
                primeiraNegativa = data;
                valorFaltante = acumulado.abs();
            }
            linhas.add(new LinhaProjecao(data, rotuloFaixa(data, agrupamentoEfetivo),
                    arredondar(entradas), arredondar(saidas), arredondar(doPeriodo), arredondar(acumulado),
                    // Só o primeiro balde pode conter vencido: o SQL usa GREATEST(faixa, dataInicial),
                    // então tudo que venceu antes do período é empurrado exatamente para cá.
                    data.isEqual(dataInicial)));
        }

        return new FluxoProjecaoResponse(dataInicial, dataFinal, arredondar(saldoAtual), linhas,
                arredondar(totalEntradas), arredondar(totalSaidas), arredondar(acumulado),
                primeiraNegativa, valorFaltante == null ? null : arredondar(valorFaltante));
    }

    private static BigDecimal[] novoAcumulador() {
        return new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO };
    }

    private static String rotuloFaixa(LocalDate data, Agrupamento agrupamento) {
        return switch (agrupamento) {
            case DIA -> "%02d/%02d/%d".formatted(data.getDayOfMonth(), data.getMonthValue(), data.getYear());
            case SEMANA -> "Semana de %02d/%02d".formatted(data.getDayOfMonth(), data.getMonthValue());
            case MES -> "%02d/%d".formatted(data.getMonthValue(), data.getYear());
        };
    }

    /** ⚠️ O fuso aparece DUAS vezes no SQL da projeção (no `date_trunc` e no corte por vencimento),
     *  e a ordem posicional é: truncamento, fuso, início, fuso, fim, empresas. */
    private static List<Object> paramsProjecao(
            String truncamento, String fuso, LocalDate inicio, LocalDate fim, List<Long> empresas) {
        List<Object> params = new ArrayList<>();
        params.add(truncamento);
        params.add(fuso);
        params.add(inicio);
        params.add(fuso);
        params.add(fim);
        if (empresas != null) {
            params.addAll(empresas);
        }
        return params;
    }

    // ------------------------------------------------------------------ comuns

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

    /** ADMIN filtra livremente (vazio = todas); OPERADOR só a empresa ativa da sessão. */
    private static List<Long> resolverEmpresas(Jwt jwt, List<Long> idsEmpresa) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        boolean admin = roles != null && roles.contains("ADMIN");
        if (!admin) {
            return List.of(((Number) jwt.getClaim("eid")).longValue());
        }
        return (idsEmpresa == null || idsEmpresa.isEmpty()) ? null : idsEmpresa;
    }

    private static String filtroEmpresa(String coluna, List<Long> empresas) {
        if (empresas == null) {
            return "";
        }
        return " AND " + coluna + " IN (" + String.join(",", empresas.stream().map(id -> "?").toList()) + ")";
    }

    /**
     * ⚠️ O FUSO vai como parâmetro, e é o da LOJA (auditoria 2026-08-29, rodada 1). Era
     * {@code 'America/Sao_Paulo'} cravado no SQL enquanto o {@code hoje} do Java vinha de
     * {@code fusoDaLoja} — as duas pontas discordavam. Numa loja de Manaus (UTC−4), a venda em
     * dinheiro das 23h30 caía no bucket do dia seguinte, o "Saldo real atual" mostrava menos
     * dinheiro do que a gaveta tem, e a linha <b>Diferença</b> acusava divergência inexistente —
     * exatamente o que a conciliação existe para NÃO produzir. Vale para AM/RO/RR/MT/MS (1 h por
     * dia) e AC (2 h). Mesmo padrão de {@code RecebimentoCrediarioService.listarParcelas}.
     */
    private static List<Object> parametros(String fuso, LocalDate inicio, LocalDate fim, List<Long> empresas) {
        List<Object> params = new ArrayList<>();
        params.add(fuso);
        params.add(inicio);
        params.add(fim);
        if (empresas != null) {
            params.addAll(empresas);
        }
        return params;
    }

    private static List<Object> parametrosData(String fuso, LocalDate data, List<Long> empresas) {
        List<Object> params = new ArrayList<>();
        params.add(fuso);
        params.add(data);
        if (empresas != null) {
            params.addAll(empresas);
        }
        return params;
    }

    private static BigDecimal arredondar(BigDecimal valor) {
        return valor == null ? BigDecimal.ZERO.setScale(2) : valor.setScale(2, RoundingMode.HALF_UP);
    }
}
