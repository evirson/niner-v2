package com.vetor.niner.vendas.relatorioordemservico;

import com.vetor.niner.vendas.relatorioordemservico.RelatorioOrdensServicoDtos.LinhaExecutor;
import com.vetor.niner.vendas.relatorioordemservico.RelatorioOrdensServicoDtos.MovimentoOrdens;
import com.vetor.niner.vendas.relatorioordemservico.RelatorioOrdensServicoDtos.RelatorioOrdensServicoResponse;
import com.vetor.niner.vendas.relatorioordemservico.RelatorioOrdensServicoDtos.SubtotalEmpresaOrdens;
import com.vetor.niner.vendas.relatorioordemservico.RelatorioOrdensServicoDtos.TotalGeralOrdens;
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
 * Relatório de Ordens de Serviço — ver {@code package-info.java} e
 * {@code docs/telas/relatorio-ordem-servico.md}.
 */
@Service
public class RelatorioOrdensServicoService {

    private static final int PERIODO_MAXIMO_DIAS = 400;

    /** Fuso da loja em toda comparação de data — o do banco é UTC e viraria o dia às 21h. */
    private static final String FUSO = "America/Sao_Paulo";

    /**
     * Allowlist — nunca string-concatenar a coluna vinda do cliente na SQL.
     *
     * <p>⚠️ <b>Alias do SELECT vale no {@code ORDER BY} sozinho, mas NÃO dentro de uma expressão.</b>
     * {@code valorTotal} era {@code (valor_servicos + valor_pecas)} e o Postgres respondia
     * <i>column "valor_servicos" does not exist</i> — a tela abre ordenando por essa coluna, então
     * o relatório <b>não gerava nenhuma vez</b>. Por isso aqui ele é a soma sem {@code FILTER}
     * (serviços + peças é o total de todos os itens), e é essa a razão de o Relatório de Comissões
     * repetir a expressão inteira em vez do alias nos casos equivalentes.
     */
    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "nomeEmpresa", "e.razao_social",
            "nomeFuncionario", "nome_funcionario",
            "qtdOrdens", "qtd_ordens",
            "valorServicos", "valor_servicos",
            "valorPecas", "valor_pecas",
            "valorTotal", "SUM(i.qtd_produto * i.preco_venda)",
            "tempoMedioHoras", "tempo_medio_horas");

    private final JdbcClient jdbc;

    public RelatorioOrdensServicoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RelatorioOrdensServicoResponse gerar(
            Jwt jwt, LocalDate dataInicial, LocalDate dataFinal, List<Long> idsEmpresaSolicitadas,
            String ordenarPor, String direcao) {
        validarPeriodo(dataInicial, dataFinal);
        List<Long> idsEmpresa = resolverIdsEmpresa(jwt, idsEmpresaSolicitadas);

        MovimentoOrdens movimento = buscarMovimento(dataInicial, dataFinal, idsEmpresa);
        List<LinhaExecutor> linhas = buscarLinhas(dataInicial, dataFinal, idsEmpresa, ordenarPor, direcao);
        TotalGeralOrdens totalGeral = buscarTotalGeral(dataInicial, dataFinal, idsEmpresa);

        return new RelatorioOrdensServicoResponse(
                movimento, linhas, calcularSubtotais(linhas), totalGeral);
    }

    private void validarPeriodo(LocalDate dataInicial, LocalDate dataFinal) {
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

    /** ADMIN filtra livremente (vazio = todas); OPERADOR só a empresa da sessão — mesmo padrão do
     *  Relatório de Vendas e do de Comissões. */
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
     * Movimento do período — ⚠️ cada contador na SUA data. Um único {@code WHERE} de período com
     * quatro {@code COUNT(FILTER)} daria números errados em silêncio: contaria "faturadas entre as
     * ABERTAS no período", que é outra pergunta. Por isso são subconsultas, cada uma com o seu eixo.
     */
    private MovimentoOrdens buscarMovimento(LocalDate dataInicial, LocalDate dataFinal, List<Long> idsEmpresa) {
        FiltroEmpresa fe = filtroEmpresa(idsEmpresa, "os");
        String sql = """
                SELECT
                  (SELECT count(*) FROM ordem_servico os
                    WHERE os.id_tenant = plataforma.tenant_atual()
                      AND (os.data_abertura AT TIME ZONE '%1$s')::date BETWEEN ? AND ?%2$s) AS qtd_abertas,
                  (SELECT count(*) FROM ordem_servico os
                    WHERE os.id_tenant = plataforma.tenant_atual() AND os.data_conclusao IS NOT NULL
                      AND (os.data_conclusao AT TIME ZONE '%1$s')::date BETWEEN ? AND ?%2$s) AS qtd_concluidas,
                  (SELECT count(*) FROM ordem_servico os
                    WHERE os.id_tenant = plataforma.tenant_atual() AND os.data_faturamento IS NOT NULL
                      AND (os.data_faturamento AT TIME ZONE '%1$s')::date BETWEEN ? AND ?%2$s) AS qtd_faturadas,
                  (SELECT count(*) FROM ordem_servico os
                    WHERE os.id_tenant = plataforma.tenant_atual() AND os.data_cancelamento IS NOT NULL
                      AND (os.data_cancelamento AT TIME ZONE '%1$s')::date BETWEEN ? AND ?%2$s) AS qtd_canceladas,
                  -- Faturado e desconto saem do eixo de FATURAMENTO: é quando o dinheiro foi cobrado.
                  (SELECT COALESCE(SUM(t.total), 0) FROM (
                     SELECT COALESCE((SELECT SUM(i.qtd_produto * i.preco_venda)
                                        FROM ordem_servico_item i
                                       WHERE i.id_tenant = os.id_tenant
                                         AND i.id_ordem_servico = os.id_ordem_servico), 0)
                            - os.valor_desconto AS total
                       FROM ordem_servico os
                      WHERE os.id_tenant = plataforma.tenant_atual() AND os.data_faturamento IS NOT NULL
                        AND (os.data_faturamento AT TIME ZONE '%1$s')::date BETWEEN ? AND ?%2$s) t) AS valor_faturado,
                  (SELECT COALESCE(SUM(os.valor_desconto), 0) FROM ordem_servico os
                    WHERE os.id_tenant = plataforma.tenant_atual() AND os.data_faturamento IS NOT NULL
                      AND (os.data_faturamento AT TIME ZONE '%1$s')::date BETWEEN ? AND ?%2$s) AS valor_desconto,
                  -- Tempo de CALENDÁRIO (abertura → conclusão), não de bancada: inclui a espera pela
                  -- aprovação do cliente e a peça que não chegou. Está escrito na tela.
                  (SELECT AVG(EXTRACT(EPOCH FROM (os.data_conclusao - os.data_abertura)) / 3600)
                     FROM ordem_servico os
                    WHERE os.id_tenant = plataforma.tenant_atual() AND os.data_conclusao IS NOT NULL
                      AND os.situacao <> 'CANCELADA'
                      AND (os.data_conclusao AT TIME ZONE '%1$s')::date BETWEEN ? AND ?%2$s) AS tempo_medio_horas
                """.formatted(FUSO, fe.clausula());

        List<Object> params = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            params.add(dataInicial);
            params.add(dataFinal);
            params.addAll(fe.params());
        }

        return jdbc.sql(sql).params(params).query((rs, n) -> {
            int faturadas = rs.getInt("qtd_faturadas");
            BigDecimal valorFaturado = duasCasas(rs.getBigDecimal("valor_faturado"));
            return new MovimentoOrdens(
                    rs.getInt("qtd_abertas"), rs.getInt("qtd_concluidas"), faturadas,
                    rs.getInt("qtd_canceladas"),
                    valorFaturado, duasCasas(rs.getBigDecimal("valor_desconto")),
                    faturadas == 0 ? BigDecimal.ZERO.setScale(2)
                            : valorFaturado.divide(BigDecimal.valueOf(faturadas), 2, RoundingMode.HALF_UP),
                    horas(rs.getBigDecimal("tempo_medio_horas")));
        }).single();
    }

    /**
     * Produtividade por (empresa, executor) das OS CONCLUÍDAS no período.
     *
     * <p>⚠️ Agrupa pelo executor do ITEM ({@code i.id_funcionario}), nunca pelo do cabeçalho — este
     * é quem atendeu. ⚠️ E {@code id_funcionario} nulo vira a linha "(SEM EXECUTOR)" em vez de ser
     * filtrado: sem ela o total por executor não fecharia com o total geral e a tela não diria por
     * quê.
     */
    private List<LinhaExecutor> buscarLinhas(
            LocalDate dataInicial, LocalDate dataFinal, List<Long> idsEmpresa,
            String ordenarPor, String direcao) {
        FiltroEmpresa fe = filtroEmpresa(idsEmpresa, "os");
        String sql = """
                SELECT os.id_empresa, e.razao_social AS nome_empresa,
                       COALESCE(i.id_funcionario, 0) AS id_funcionario,
                       COALESCE(fn.nome, '(SEM EXECUTOR)') AS nome_funcionario,
                       COUNT(DISTINCT os.id_ordem_servico) AS qtd_ordens,
                       COALESCE(SUM(i.qtd_produto * i.preco_venda)
                                FILTER (WHERE p.tipo_item = 'SERVICO'), 0) AS valor_servicos,
                       COALESCE(SUM(i.qtd_produto * i.preco_venda)
                                FILTER (WHERE p.tipo_item <> 'SERVICO'), 0) AS valor_pecas,
                       AVG(EXTRACT(EPOCH FROM (os.data_conclusao - os.data_abertura)) / 3600)
                           AS tempo_medio_horas
                  FROM ordem_servico os
                  JOIN ordem_servico_item i
                    ON i.id_tenant = os.id_tenant AND i.id_ordem_servico = os.id_ordem_servico
                  JOIN produto_barra pb
                    ON pb.id_tenant = i.id_tenant AND pb.id_variacao = i.id_variacao
                  JOIN produto p
                    ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                  JOIN empresa e
                    ON e.id_tenant = os.id_tenant AND e.id_empresa = os.id_empresa
                  LEFT JOIN funcionario fn
                    ON fn.id_tenant = i.id_tenant AND fn.id_funcionario = i.id_funcionario
                 WHERE os.id_tenant = plataforma.tenant_atual()
                   AND os.data_conclusao IS NOT NULL
                   AND os.situacao <> 'CANCELADA'
                   AND (os.data_conclusao AT TIME ZONE '%s')::date BETWEEN ? AND ?%s
                 GROUP BY os.id_empresa, e.razao_social, COALESCE(i.id_funcionario, 0), fn.nome
                 %s
                """.formatted(FUSO, fe.clausula(), montarOrdenacao(ordenarPor, direcao));

        List<Object> params = new ArrayList<>(List.of(dataInicial, dataFinal));
        params.addAll(fe.params());

        return jdbc.sql(sql).params(params).query((rs, n) -> {
            BigDecimal servicos = duasCasas(rs.getBigDecimal("valor_servicos"));
            BigDecimal pecas = duasCasas(rs.getBigDecimal("valor_pecas"));
            return new LinhaExecutor(
                    rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                    rs.getLong("id_funcionario"), rs.getString("nome_funcionario"),
                    rs.getInt("qtd_ordens"), servicos, pecas, servicos.add(pecas),
                    horas(rs.getBigDecimal("tempo_medio_horas")));
        }).list();
    }

    /**
     * ⚠️ O total NÃO é a soma das linhas, e é por isso que ele tem consulta própria: uma OS com dois
     * executores aparece uma vez para cada um, então somar {@code qtdOrdens} contaria a mesma OS
     * duas vezes. Os valores até somariam certo (cada item pertence a um executor só), mas manter
     * as duas contas na mesma consulta mantém o par consistente.
     */
    private TotalGeralOrdens buscarTotalGeral(LocalDate dataInicial, LocalDate dataFinal, List<Long> idsEmpresa) {
        FiltroEmpresa fe = filtroEmpresa(idsEmpresa, "os");
        String sql = """
                SELECT COUNT(DISTINCT os.id_ordem_servico) AS qtd_ordens,
                       COALESCE(SUM(i.qtd_produto * i.preco_venda)
                                FILTER (WHERE p.tipo_item = 'SERVICO'), 0) AS valor_servicos,
                       COALESCE(SUM(i.qtd_produto * i.preco_venda)
                                FILTER (WHERE p.tipo_item <> 'SERVICO'), 0) AS valor_pecas
                  FROM ordem_servico os
                  JOIN ordem_servico_item i
                    ON i.id_tenant = os.id_tenant AND i.id_ordem_servico = os.id_ordem_servico
                  JOIN produto_barra pb
                    ON pb.id_tenant = i.id_tenant AND pb.id_variacao = i.id_variacao
                  JOIN produto p
                    ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                 WHERE os.id_tenant = plataforma.tenant_atual()
                   AND os.data_conclusao IS NOT NULL
                   AND os.situacao <> 'CANCELADA'
                   AND (os.data_conclusao AT TIME ZONE '%s')::date BETWEEN ? AND ?%s
                """.formatted(FUSO, fe.clausula());

        List<Object> params = new ArrayList<>(List.of(dataInicial, dataFinal));
        params.addAll(fe.params());

        return jdbc.sql(sql).params(params).query((rs, n) -> {
            BigDecimal servicos = duasCasas(rs.getBigDecimal("valor_servicos"));
            BigDecimal pecas = duasCasas(rs.getBigDecimal("valor_pecas"));
            return new TotalGeralOrdens(rs.getInt("qtd_ordens"), servicos, pecas, servicos.add(pecas));
        }).single();
    }

    /** Subtotal por empresa — derivado das linhas já carregadas, como no de Comissões. ⚠️ Aqui
     *  {@code qtdOrdens} SOMA as linhas de propósito: dentro de uma empresa o subtotal acompanha a
     *  grade logo acima dele; o total geral (esse sim distinto) fica no rodapé. */
    private List<SubtotalEmpresaOrdens> calcularSubtotais(List<LinhaExecutor> linhas) {
        Map<Long, SubtotalEmpresaOrdens> porEmpresa = new LinkedHashMap<>();
        for (LinhaExecutor l : linhas) {
            porEmpresa.merge(l.idEmpresa(),
                    new SubtotalEmpresaOrdens(l.idEmpresa(), l.nomeEmpresa(), l.qtdOrdens(),
                            l.valorServicos(), l.valorPecas(), l.valorTotal()),
                    (a, b) -> new SubtotalEmpresaOrdens(a.idEmpresa(), a.nomeEmpresa(),
                            a.qtdOrdens() + b.qtdOrdens(),
                            a.valorServicos().add(b.valorServicos()),
                            a.valorPecas().add(b.valorPecas()),
                            a.valorTotal().add(b.valorTotal())));
        }
        return new ArrayList<>(porEmpresa.values());
    }

    /** Empresa é sempre o critério PRIMÁRIO — é o que mantém o agrupamento do subtotal válido.
     *
     *  <p>⚠️ O {@code ordenarPor == null} não é zelo: {@code ordenarPor} é opcional no contrato (a
     *  tela abre sem coluna escolhida) e {@code Map.of(...).get(null)} <b>lança NPE</b> — mapa
     *  imutável recusa chave nula. Foi o que o primeiro teste pegou aqui, e é por isso que os
     *  outros 20 serviços do projeto escrevem essa mesma guarda antes do {@code get}. */
    private String montarOrdenacao(String ordenarPor, String direcao) {
        String coluna = ordenarPor == null ? null : COLUNAS_ORDENAVEIS.get(ordenarPor);
        String dir = "desc".equalsIgnoreCase(direcao) ? "DESC" : "ASC";
        if (coluna == null || "nomeEmpresa".equals(ordenarPor)) {
            return "ORDER BY e.razao_social " + ("nomeEmpresa".equals(ordenarPor) ? dir : "ASC")
                    + ", nome_funcionario ASC";
        }
        return "ORDER BY e.razao_social ASC, " + coluna + " " + dir + ", nome_funcionario ASC";
    }

    /** Filtro de empresa como cláusula + parâmetros, para ser repetido nas subconsultas do
     *  movimento sem duplicar a montagem (e sem concatenar id vindo do cliente na SQL). */
    private record FiltroEmpresa(String clausula, List<Object> params) {
    }

    private FiltroEmpresa filtroEmpresa(List<Long> idsEmpresa, String alias) {
        if (idsEmpresa == null) {
            return new FiltroEmpresa("", List.of());
        }
        String placeholders = String.join(",", idsEmpresa.stream().map(id -> "?").toList());
        return new FiltroEmpresa(
                " AND " + alias + ".id_empresa IN (" + placeholders + ")",
                List.copyOf(idsEmpresa));
    }

    private static BigDecimal duasCasas(BigDecimal valor) {
        return (valor == null ? BigDecimal.ZERO : valor).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Tempo médio em horas — <b>{@code null} quando não houve OS concluída</b>, nunca zero.
     *
     * <p>⚠️ <b>Os dois defeitos que isto conserta, achados ABRINDO A TELA:</b> (a) o {@code COALESCE
     * (…, 0)} transformava <i>"não há o que medir"</i> em <i>"medi, deu zero"</i> — duas coisas
     * diferentes, e a tela só pode escrever "—" para a primeira; (b) arredondar para <b>uma</b> casa
     * matava toda duração abaixo de 3 minutos: as OS reais do banco levaram de 0,0047 h a 0,0594 h e
     * a coluna inteira saiu vazia, dando a impressão de relatório quebrado.
     *
     * <p>Quatro casas porque quem escolhe a unidade é a <b>tela</b> (minutos, horas ou dias) e ela
     * não pode recuperar o que o arredondamento já jogou fora — a decisão é de apresentação, o
     * número aqui é o dado.
     */
    private static BigDecimal horas(BigDecimal valor) {
        return valor == null ? null : valor.setScale(4, RoundingMode.HALF_UP);
    }

    private static boolean ehAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }

    private static long idEmpresaSessao(Jwt jwt) {
        return ((Number) jwt.getClaim("eid")).longValue();
    }
}
