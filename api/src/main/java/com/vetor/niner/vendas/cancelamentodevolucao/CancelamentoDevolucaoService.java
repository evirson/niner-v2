package com.vetor.niner.vendas.cancelamentodevolucao;

import com.vetor.niner.comum.tempo.FusoDaLoja;
import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.vendas.cancelamentodevolucao.CancelamentoDevolucaoDtos.CancelamentoDevolucaoEfetivadoResponse;
import com.vetor.niner.vendas.cancelamentodevolucao.CancelamentoDevolucaoDtos.CancelarDevolucaoRequest;
import com.vetor.niner.vendas.cancelamentodevolucao.CancelamentoDevolucaoDtos.DevolucaoDetalheCancelamentoResponse;
import com.vetor.niner.vendas.cancelamentodevolucao.CancelamentoDevolucaoDtos.DevolucaoParaCancelamentoResponse;
import com.vetor.niner.vendas.cancelamentodevolucao.CancelamentoDevolucaoDtos.ItemDevolucaoDetalhe;
import com.vetor.niner.vendas.cancelamentodevolucao.CancelamentoDevolucaoDtos.PaginaDevolucoesCancelamento;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Cancelamento de Devolução de Produtos — ver {@code package-info.java} pro resumo de escopo.
 * ADMIN e OPERADOR têm acesso, mas OPERADOR só enxerga/cancela devoluções da empresa em que está
 * logado (claim {@code eid} do JWT); ADMIN cancela de qualquer empresa do tenant.
 */
@Service
public class CancelamentoDevolucaoService {

    private static final int TAMANHO_PAGINA_PADRAO = 50;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;
    private static final int PERIODO_MAXIMO_DIAS = 365;
    private static final DateTimeFormatter FMT_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "dataDevolucao", "vd.data_devolucao",
            "idDevolucao", "vd.id_devolucao",
            "nomeEmpresa", "nome_empresa",
            "valorVale", "valor_vale");

    private final JdbcClient jdbc;
    private final FusoDaLoja fusoDaLoja;

    public CancelamentoDevolucaoService(JdbcClient jdbc, FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.fusoDaLoja = fusoDaLoja;
    }

    @Transactional(readOnly = true)
    public PaginaDevolucoesCancelamento listar(Jwt jwt, Long idDevolucao, LocalDate dataInicial, LocalDate dataFinal,
                                                Integer pagina, Integer limite, String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String coluna = ordenarPor == null ? "vd.data_devolucao" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "vd.data_devolucao");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE vd.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();

        if (!isAdmin(jwt)) {
            filtro.append(" AND vd.id_empresa = ?");
            params.add(idEmpresaAtiva(jwt));
        }

        if (idDevolucao != null) {
            filtro.append(" AND vd.id_devolucao = ?");
            params.add(idDevolucao);
        } else {
            if (dataInicial == null || dataFinal == null) {
                throw new IllegalArgumentException("Informe a data inicial e a data final, ou o número da devolução.");
            }
            if (dataInicial.isAfter(dataFinal)) {
                throw new IllegalArgumentException("Data inicial não pode ser maior que a data final.");
            }
            if (dataInicial.plusDays(PERIODO_MAXIMO_DIAS).isBefore(dataFinal)) {
                throw new IllegalArgumentException(
                        "Período de consulta não pode exceder " + PERIODO_MAXIMO_DIAS + " dias.");
            }
            filtro.append(" AND (vd.data_devolucao AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?");
            params.add(dataInicial);
            params.add(dataFinal);

            // Fora da busca direta por número, só vales ainda canceláveis aparecem (RN: só existe
            // cancelamento com vale ainda não usado; um já cancelado também não é mais acionável).
            filtro.append(" AND vd.vale_usado = false AND vd.cancelada = false");
        }

        long totalItens = jdbc.sql("""
                        SELECT count(*) FROM venda_devolucao vd
                        """ + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);

        String baseSelect = """
                        SELECT vd.id_devolucao, vd.id_empresa, e.razao_social AS nome_empresa, vd.data_devolucao,
                               vd.id_venda_credito, vd.vale_usado, vd.cancelada,
                               COALESCE((
                                   SELECT SUM(pmd.qtd_produto * pmd.preco_venda)
                                   FROM produto_movimento_mestre pmm
                                   JOIN produto_movimento_detalhe pmd
                                          ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                                   WHERE pmm.id_tenant = vd.id_tenant AND pmm.id_devolucao = vd.id_devolucao
                                         AND pmm.tipo_movimento = 'DEVOLUCAO'
                               ), 0) AS valor_vale
                        FROM venda_devolucao vd
                        JOIN empresa e ON e.id_empresa = vd.id_empresa AND e.id_tenant = vd.id_tenant
                        """;
        String ordenacao = " ORDER BY " + coluna + " " + direcaoOrdenacao
                + ", vd.id_devolucao " + direcaoOrdenacao + " LIMIT ? OFFSET ?";

        List<DevolucaoParaCancelamentoResponse> itens = jdbc.sql(baseSelect + filtro + ordenacao)
                .params(paramsPagina)
                .query((rs, n) -> new DevolucaoParaCancelamentoResponse(
                        rs.getLong("id_devolucao"), rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                        rs.getObject("data_devolucao", OffsetDateTime.class), rs.getBigDecimal("valor_vale"),
                        getLongOuNulo(rs, "id_venda_credito"), rs.getBoolean("vale_usado"), rs.getBoolean("cancelada")))
                .list();

        return new PaginaDevolucoesCancelamento(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public DevolucaoDetalheCancelamentoResponse buscarDetalhe(Jwt jwt, long idDevolucao) {
        Cabecalho c = buscarCabecalho(idDevolucao);
        exigirAcessoAEmpresa(jwt, c.idEmpresa());
        List<ItemDevolucaoDetalhe> itens = buscarItens(idDevolucao);
        BigDecimal valorVale = itens.stream().map(ItemDevolucaoDetalhe::valorItem).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DevolucaoDetalheCancelamentoResponse(
                c.idDevolucao(), c.idEmpresa(), c.nomeEmpresa(), c.dataDevolucao(), valorVale, c.idVendaCredito(),
                c.valeUsado(), c.cancelada(), c.dataCancelamento(), c.nomeUsuarioCancelamento(), c.motivoCancelamento(),
                itens);
    }

    @Transactional
    public CancelamentoDevolucaoEfetivadoResponse cancelar(Jwt jwt, long idDevolucao, CancelarDevolucaoRequest req) {
        long idUsuario = idUsuario(jwt);

        Cabecalho c = jdbc.sql("""
                        SELECT vd.id_devolucao, vd.id_empresa, e.razao_social AS nome_empresa, vd.data_devolucao,
                               vd.id_venda_credito, vd.vale_usado, vd.cancelada, vd.data_cancelamento,
                               vd.motivo_cancelamento, u.nome_usuario AS nome_usuario_cancelamento
                        FROM venda_devolucao vd
                        JOIN empresa e ON e.id_empresa = vd.id_empresa AND e.id_tenant = vd.id_tenant
                        LEFT JOIN usuario u ON u.id_usuario = vd.id_usuario_cancelamento AND u.id_tenant = vd.id_tenant
                        WHERE vd.id_tenant = plataforma.tenant_atual() AND vd.id_devolucao = ?
                        FOR UPDATE OF vd
                        """)
                .param(idDevolucao)
                .query((rs, n) -> lerCabecalho(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Devolução não encontrada."));

        exigirAcessoAEmpresa(jwt, c.idEmpresa());

        if (c.cancelada()) {
            throw new ConflitoDadosException(
                    "A devolução nº " + c.idDevolucao() + " já foi cancelada em "
                            + fusoDaLoja.formatar(c.dataCancelamento(), c.idEmpresa(), FMT_DATA)
                            + " por " + c.nomeUsuarioCancelamento() + ".");
        }
        if (c.valeUsado()) {
            throw new ConflitoDadosException(
                    "O vale-mercadoria nº " + c.idDevolucao() + " já foi usado — não é possível cancelar a devolução.");
        }

        OffsetDateTime agora = OffsetDateTime.now();
        jdbc.sql("""
                        UPDATE venda_devolucao SET cancelada = true, data_cancelamento = ?, id_usuario_cancelamento = ?,
                            motivo_cancelamento = ?
                        WHERE id_tenant = plataforma.tenant_atual() AND id_devolucao = ?
                        """)
                .params(agora, idUsuario, req.motivo(), idDevolucao)
                .update();

        estornarEstoque(c.idEmpresa(), idDevolucao);

        return new CancelamentoDevolucaoEfetivadoResponse(idDevolucao, agora);
    }

    /** Tira do estoque a quantidade que a devolução original tinha colocado de volta — novo
     *  {@code produto_movimento_mestre} (tipo CANCELAMENTO_DEVOLUCAO) + um {@code
     *  produto_movimento_detalhe} 'D' por item (inverso do 'C' da devolução original; a trigger
     *  já existente baixa/soma {@code produto_estoque} sozinha, mesmo mecanismo de qualquer
     *  outra movimentação). */
    private void estornarEstoque(long idEmpresa, long idDevolucao) {
        record ItemDevolvido(long idVariacao, BigDecimal qtd, BigDecimal precoVenda, BigDecimal precoCusto, Long idFuncionario) {
        }
        List<ItemDevolvido> itensDevolvidos = jdbc.sql("""
                        SELECT pmd.id_variacao, pmd.qtd_produto, pmd.preco_venda, pmd.preco_custo, pmd.id_funcionario
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_devolucao = ? AND pmm.tipo_movimento = 'DEVOLUCAO'
                              AND pmd.credito_debito = 'C'
                        """)
                .param(idDevolucao)
                .query((rs, n) -> new ItemDevolvido(
                        rs.getLong("id_variacao"), rs.getBigDecimal("qtd_produto"), rs.getBigDecimal("preco_venda"),
                        rs.getBigDecimal("preco_custo"), getLongOuNulo(rs, "id_funcionario")))
                .list();
        if (itensDevolvidos.isEmpty()) return;

        long idMovimento = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_devolucao)
                        VALUES (plataforma.tenant_atual(), ?, 'CANCELAMENTO_DEVOLUCAO', ?)
                        RETURNING id_movimento
                        """)
                .params(idEmpresa, idDevolucao).query(Long.class).single();

        for (ItemDevolvido item : itensDevolvidos) {
            jdbc.sql("""
                            INSERT INTO produto_movimento_detalhe
                                (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto,
                                 preco_venda, preco_custo, id_funcionario)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'D', ?, ?, ?, ?)
                            """)
                    .params(idMovimento, idEmpresa, item.idVariacao(), item.qtd(), item.precoVenda(), item.precoCusto(), item.idFuncionario())
                    .update();
        }
    }

    private record Cabecalho(long idDevolucao, long idEmpresa, String nomeEmpresa, OffsetDateTime dataDevolucao,
                              Long idVendaCredito, boolean valeUsado, boolean cancelada,
                              OffsetDateTime dataCancelamento, String nomeUsuarioCancelamento, String motivoCancelamento) {
    }

    private Cabecalho buscarCabecalho(long idDevolucao) {
        return jdbc.sql("""
                        SELECT vd.id_devolucao, vd.id_empresa, e.razao_social AS nome_empresa, vd.data_devolucao,
                               vd.id_venda_credito, vd.vale_usado, vd.cancelada, vd.data_cancelamento,
                               vd.motivo_cancelamento, u.nome_usuario AS nome_usuario_cancelamento
                        FROM venda_devolucao vd
                        JOIN empresa e ON e.id_empresa = vd.id_empresa AND e.id_tenant = vd.id_tenant
                        LEFT JOIN usuario u ON u.id_usuario = vd.id_usuario_cancelamento AND u.id_tenant = vd.id_tenant
                        WHERE vd.id_tenant = plataforma.tenant_atual() AND vd.id_devolucao = ?
                        """)
                .param(idDevolucao)
                .query((rs, n) -> lerCabecalho(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Devolução não encontrada."));
    }

    private static Cabecalho lerCabecalho(ResultSet rs) throws SQLException {
        return new Cabecalho(
                rs.getLong("id_devolucao"), rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                rs.getObject("data_devolucao", OffsetDateTime.class), getLongOuNulo(rs, "id_venda_credito"),
                rs.getBoolean("vale_usado"), rs.getBoolean("cancelada"),
                rs.getObject("data_cancelamento", OffsetDateTime.class), rs.getString("nome_usuario_cancelamento"),
                rs.getString("motivo_cancelamento"));
    }

    private List<ItemDevolucaoDetalhe> buscarItens(long idDevolucao) {
        return jdbc.sql("""
                        SELECT p.descricao AS descricao_produto, co.descricao AS variacao_cor,
                               ta.descricao AS variacao_tamanho, pmd.qtd_produto, pmd.preco_venda,
                               pmd.qtd_produto * pmd.preco_venda AS valor_item
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                              AND pmd.credito_debito = 'C'
                        JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                        JOIN produto p        ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
                        LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_devolucao = ? AND pmm.tipo_movimento = 'DEVOLUCAO'
                        ORDER BY pmd.id_movimento_detalhe
                        """)
                .param(idDevolucao)
                .query((rs, n) -> new ItemDevolucaoDetalhe(
                        rs.getString("descricao_produto"), rs.getString("variacao_cor"), rs.getString("variacao_tamanho"),
                        rs.getBigDecimal("qtd_produto"), rs.getBigDecimal("preco_venda"), rs.getBigDecimal("valor_item")))
                .list();
    }

    /** {@code rs.getObject(coluna, Long.class)} não funciona pra colunas {@code integer}
     *  (driver do Postgres só converte pro tipo exato) — {@code getLong}+{@code wasNull} é o
     *  jeito seguro de ler uma coluna {@code integer} nullable como {@code Long}. */
    private static Long getLongOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    private static boolean isAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }

    private static long idEmpresaAtiva(Jwt jwt) {
        return ((Number) jwt.getClaim("eid")).longValue();
    }

    /** OPERADOR só acessa/cancela devoluções da empresa em que está logado; ADMIN não tem essa
     *  restrição (pode cancelar de qualquer empresa do tenant). */
    private static void exigirAcessoAEmpresa(Jwt jwt, long idEmpresaDevolucao) {
        if (!isAdmin(jwt) && idEmpresaDevolucao != idEmpresaAtiva(jwt)) {
            throw new ResponseStatusException(FORBIDDEN,
                    "Você só pode cancelar devoluções da empresa em que está logado.");
        }
    }

    private static long idUsuario(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
