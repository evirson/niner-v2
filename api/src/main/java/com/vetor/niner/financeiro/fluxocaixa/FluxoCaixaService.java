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

        BigDecimal saldoInicial = saldoAte(dataInicial.minusDays(1), empresas, origemEfetiva);
        List<Movimento> movimentos = movimentosDoPeriodo(dataInicial, dataFinal, empresas, origemEfetiva);

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
        BigDecimal saldoRealAtual = saldoAte(hoje, empresas, origemEfetiva);
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
            LocalDate inicio, LocalDate fim, List<Long> empresas, OrigemDinheiro origem) {
        List<Movimento> movimentos = new ArrayList<>();

        if (origem != OrigemDinheiro.CONTA_CORRENTE) {
            // Abertura de caixa dentro do período conta como ENTRADA (2026-08-14, achado no teste):
            // `caixa_mestre.saldo_inicial` é dinheiro que entra na gaveta sem gerar linha em
            // `caixa_detalhe`. Sem contá-lo aqui, o saldo final do período não fecha com o saldo
            // real — que é justamente a promessa do relatório (o teste acusou -150 onde o dinheiro
            // real era 850). `saldoAte` já o considerava; faltava a contrapartida no período.
            movimentos.addAll(jdbc.sql("""
                            SELECT 'OPERACIONAL' AS grupo, 'Abertura de caixa (saldo inicial)' AS rotulo,
                                   COALESCE(SUM(cm.saldo_inicial), 0) AS valor
                            FROM caixa_mestre cm
                            WHERE cm.id_tenant = plataforma.tenant_atual() AND cm.saldo_inicial <> 0
                                  AND (cm.data_abertura AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                            """ + filtroEmpresa("cm.id_empresa", empresas) + """

                            HAVING COALESCE(SUM(cm.saldo_inicial), 0) <> 0
                            """)
                    .params(parametros(inicio, fim, empresas))
                    .query((rs, n) -> new Movimento(rs.getString("grupo"), rs.getString("rotulo"), rs.getBigDecimal("valor")))
                    .list());

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
                            WHERE cd.id_tenant = plataforma.tenant_atual()
                                  AND (cd.criado_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                            """ + filtroEmpresa("cm.id_empresa", empresas) + """

                            GROUP BY 1, 2
                            """)
                    .params(parametros(inicio, fim, empresas))
                    .query((rs, n) -> new Movimento(rs.getString("grupo"), rs.getString("rotulo"), rs.getBigDecimal("valor")))
                    .list());
        }

        if (origem != OrigemDinheiro.CAIXA) {
            // conta_corrente não tem id_empresa — o filtro de empresa não se aplica ao banco.
            movimentos.addAll(jdbc.sql("""
                            SELECT COALESCE(NULLIF(pc.grupo_dfc::text, 'NAO_APLICA'), 'OPERACIONAL') AS grupo,
                                   pc.descricao AS rotulo,
                                   SUM(CASE WHEN ccm.credito_debito = 'C' THEN ccm.valor ELSE -ccm.valor END) AS valor
                            FROM conta_corrente_movimento ccm
                            JOIN cfg_plano_contas pc
                                 ON pc.id_plano_contas = ccm.id_plano_contas AND pc.id_tenant = ccm.id_tenant
                            WHERE ccm.id_tenant = plataforma.tenant_atual()
                                  AND (ccm.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                            GROUP BY 1, 2
                            """)
                    .params(inicio, fim)
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
    private BigDecimal saldoAte(LocalDate data, List<Long> empresas, OrigemDinheiro origem) {
        BigDecimal saldo = BigDecimal.ZERO;

        if (origem != OrigemDinheiro.CONTA_CORRENTE) {
            saldo = saldo.add(jdbc.sql("""
                            SELECT COALESCE(SUM(cm.saldo_inicial), 0)
                            FROM caixa_mestre cm
                            WHERE cm.id_tenant = plataforma.tenant_atual()
                                  AND (cm.data_abertura AT TIME ZONE 'America/Sao_Paulo')::date <= ?
                            """ + filtroEmpresa("cm.id_empresa", empresas))
                    .params(parametrosData(data, empresas))
                    .query(BigDecimal.class).single());

            saldo = saldo.add(jdbc.sql("""
                            SELECT COALESCE(SUM(CASE WHEN cd.credito_debito = 'C' THEN cd.valor ELSE -cd.valor END), 0)
                            FROM caixa_detalhe cd
                            JOIN caixa_mestre cm ON cm.id_caixa = cd.id_caixa AND cm.id_tenant = cd.id_tenant
                            WHERE cd.id_tenant = plataforma.tenant_atual()
                                  AND (cd.criado_em AT TIME ZONE 'America/Sao_Paulo')::date <= ?
                            """ + filtroEmpresa("cm.id_empresa", empresas))
                    .params(parametrosData(data, empresas))
                    .query(BigDecimal.class).single());
        }

        if (origem != OrigemDinheiro.CAIXA) {
            saldo = saldo.add(jdbc.sql("""
                            SELECT COALESCE(SUM(CASE WHEN ccm.credito_debito = 'C' THEN ccm.valor ELSE -ccm.valor END), 0)
                            FROM conta_corrente_movimento ccm
                            WHERE ccm.id_tenant = plataforma.tenant_atual()
                                  AND (ccm.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date <= ?
                            """)
                    .param(data)
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

        BigDecimal saldoAtual = saldoAte(fusoDaLoja.hoje(jwt), empresas, OrigemDinheiro.TODAS);

        // Vencidos entram no primeiro balde (data inicial), não na data original — senão o saldo
        // projetado mentiria sobre o presente, mostrando dinheiro que já era pra ter entrado.
        String truncamento = switch (agrupamentoEfetivo) {
            case DIA -> "day";
            case SEMANA -> "week";
            case MES -> "month";
        };

        Map<LocalDate, BigDecimal[]> porFaixa = new LinkedHashMap<>();

        jdbc.sql("""
                        SELECT GREATEST(date_trunc(?, cr.data_vencimento AT TIME ZONE 'America/Sao_Paulo')::date, ?::date) AS faixa,
                               COALESCE(SUM(cr.valor_receber), 0) AS valor
                        FROM contas_receber cr
                        JOIN venda v ON v.id_venda = cr.id_venda AND v.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND v.cancelada = false
                              AND cr.data_recebimento IS NULL
                              AND (cr.data_vencimento AT TIME ZONE 'America/Sao_Paulo')::date <= ?
                        """ + filtroEmpresa("v.id_empresa", empresas) + """

                        GROUP BY 1
                        """)
                .params(paramsProjecao(truncamento, dataInicial, dataFinal, empresas))
                .query((rs, n) -> {
                    porFaixa.computeIfAbsent(rs.getObject("faixa", LocalDate.class), d -> novoAcumulador())[0] =
                            rs.getBigDecimal("valor");
                    return 1;
                })
                .list();

        jdbc.sql("""
                        SELECT GREATEST(date_trunc(?, cp.data_vencimento AT TIME ZONE 'America/Sao_Paulo')::date, ?::date) AS faixa,
                               COALESCE(SUM(cp.valor_pagar), 0) AS valor
                        FROM contas_pagar cp
                        WHERE cp.id_tenant = plataforma.tenant_atual() AND cp.data_pagamento IS NULL
                              AND (cp.data_vencimento AT TIME ZONE 'America/Sao_Paulo')::date <= ?
                        """ + filtroEmpresa("cp.id_empresa", empresas) + """

                        GROUP BY 1
                        """)
                .params(paramsProjecao(truncamento, dataInicial, dataFinal, empresas))
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

    private static List<Object> paramsProjecao(
            String truncamento, LocalDate inicio, LocalDate fim, List<Long> empresas) {
        List<Object> params = new ArrayList<>();
        params.add(truncamento);
        params.add(inicio);
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

    private static List<Object> parametros(LocalDate inicio, LocalDate fim, List<Long> empresas) {
        List<Object> params = new ArrayList<>();
        params.add(inicio);
        params.add(fim);
        if (empresas != null) {
            params.addAll(empresas);
        }
        return params;
    }

    private static List<Object> parametrosData(LocalDate data, List<Long> empresas) {
        List<Object> params = new ArrayList<>();
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
