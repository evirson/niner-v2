package com.vetor.niner.vendas.pesquisa;

import com.vetor.niner.vendas.pesquisa.PesquisaVendaDtos.ItemVendaPesquisaResponse;
import com.vetor.niner.vendas.pesquisa.PesquisaVendaDtos.MovimentoCaixaPesquisaResponse;
import com.vetor.niner.vendas.pesquisa.PesquisaVendaDtos.PaginaVendasPesquisa;
import com.vetor.niner.vendas.pesquisa.PesquisaVendaDtos.ParcelaPesquisaResponse;
import com.vetor.niner.vendas.pesquisa.PesquisaVendaDtos.VendaDetalhePesquisaResponse;
import com.vetor.niner.vendas.pesquisa.PesquisaVendaDtos.VendaPesquisaResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Pesquisa de Venda — ver {@code package-info.java} pro resumo de escopo (somente leitura,
 * qualquer papel). Empresa: ADMIN filtra livremente; OPERADOR sempre pesquisa/consulta só a
 * empresa ativa da sessão (claim {@code eid}), mesmo espírito de {@code CaixaService}.
 */
@Service
public class PesquisaVendaService {

    private static final int TAMANHO_PAGINA_PADRAO = 50;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;
    private static final int PERIODO_MAXIMO_DIAS = 365;

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "dataVenda", "v.data_venda",
            "numeroVenda", "v.id_venda",
            "valorVenda", "valor_venda",
            "nomeCliente", "nome_cliente",
            "nomeEmpresa", "nome_empresa",
            "nomeFuncionario", "nome_funcionario");

    private final JdbcClient jdbc;

    public PesquisaVendaService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PaginaVendasPesquisa listar(Jwt jwt, Long numeroVenda, Long idEmpresa, String situacao, Long idCliente,
                                        Long idFuncionario, LocalDate dataInicial, LocalDate dataFinal,
                                        Integer pagina, Integer limite, String ordenarPor, String direcao) {
        // Ternário Long/long dispararia unboxing de um idEmpresa nulo (ADMIN sem filtro) — if/else
        // explícito evita a promoção numérica que o `?:` faria entre os dois tipos.
        Long idEmpresaEfetivo;
        if (ehAdmin(jwt)) {
            idEmpresaEfetivo = idEmpresa;
        } else {
            idEmpresaEfetivo = idEmpresaSessao(jwt);
        }

        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String coluna = ordenarPor == null ? "v.data_venda" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "v.data_venda");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE v.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();

        if (numeroVenda != null) {
            filtro.append(" AND v.id_venda = ?");
            params.add(numeroVenda);
            if (idEmpresaEfetivo != null) {
                filtro.append(" AND v.id_empresa = ?");
                params.add(idEmpresaEfetivo);
            }
        } else {
            if (dataInicial == null || dataFinal == null) {
                throw new IllegalArgumentException("Informe a data inicial e a data final, ou o número da venda.");
            }
            if (dataInicial.isAfter(dataFinal)) {
                throw new IllegalArgumentException("Data inicial não pode ser maior que a data final.");
            }
            if (dataInicial.plusDays(PERIODO_MAXIMO_DIAS).isBefore(dataFinal)) {
                throw new IllegalArgumentException(
                        "Período de consulta não pode exceder " + PERIODO_MAXIMO_DIAS + " dias.");
            }
            filtro.append(" AND v.data_venda::date BETWEEN ? AND ?");
            params.add(dataInicial);
            params.add(dataFinal);

            if (idEmpresaEfetivo != null) {
                filtro.append(" AND v.id_empresa = ?");
                params.add(idEmpresaEfetivo);
            }
            if (idCliente != null) {
                filtro.append(" AND v.id_cliente = ?");
                params.add(idCliente);
            }
            if (idFuncionario != null) {
                filtro.append("""
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
            if ("ATIVAS".equalsIgnoreCase(situacao)) {
                filtro.append(" AND v.cancelada = false");
            } else if ("CANCELADAS".equalsIgnoreCase(situacao)) {
                filtro.append(" AND v.cancelada = true");
            }
        }

        // Uma única query enxuta (id_venda/cancelada/valor) cobre todo o filtro — dá o total de
        // páginas E o totalizador (qtd/soma só das ativas), sem duplicar a lógica do valor
        // líquido numa segunda query de agregação.
        record LinhaValor(boolean cancelada, BigDecimal valorVenda) {
        }
        String selectTotais = """
                        SELECT v.id_venda, v.cancelada,
                               COALESCE(SUM(CASE WHEN pmd.credito_debito = 'D'
                                   THEN pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto + pmd.valor_acrescimo
                                   ELSE 0 END), 0) AS valor_venda
                        FROM venda v
                        LEFT JOIN produto_movimento_mestre pmm
                               ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant AND pmm.tipo_movimento = 'VENDA'
                        LEFT JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        """;
        String agrupamentoTotais = " GROUP BY v.id_venda, v.cancelada";
        List<LinhaValor> linhasParaTotal = jdbc.sql(selectTotais + filtro + agrupamentoTotais)
                .params(params)
                .query((rs, n) -> new LinhaValor(rs.getBoolean("cancelada"), rs.getBigDecimal("valor_venda")))
                .list();

        long totalItens = linhasParaTotal.size();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);
        long totalItensAtivos = linhasParaTotal.stream().filter(l -> !l.cancelada()).count();
        BigDecimal somaValorAtivas = linhasParaTotal.stream()
                .filter(l -> !l.cancelada())
                .map(LinhaValor::valorVenda)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);

        String baseSelect = """
                        SELECT v.id_venda, v.id_empresa, e.razao_social AS nome_empresa, v.data_venda,
                               v.id_cliente, c.nome AS nome_cliente,
                               MAX(pmd.id_funcionario) AS id_funcionario, MAX(fn.nome) AS nome_funcionario,
                               v.cancelada,
                               COALESCE(SUM(CASE WHEN pmd.credito_debito = 'D'
                                   THEN pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto + pmd.valor_acrescimo
                                   ELSE 0 END), 0) AS valor_venda
                        FROM venda v
                        JOIN empresa e ON e.id_empresa = v.id_empresa AND e.id_tenant = v.id_tenant
                        LEFT JOIN cliente c ON c.id_cliente = v.id_cliente AND c.id_tenant = v.id_tenant
                        LEFT JOIN produto_movimento_mestre pmm
                               ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant AND pmm.tipo_movimento = 'VENDA'
                        LEFT JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        LEFT JOIN funcionario fn ON fn.id_funcionario = pmd.id_funcionario AND fn.id_tenant = pmd.id_tenant
                        """;
        String agrupamento = " GROUP BY v.id_venda, v.id_empresa, e.razao_social, v.data_venda, v.id_cliente, c.nome, v.cancelada";
        String ordenacao = " ORDER BY " + coluna + " " + direcaoOrdenacao
                + ", v.id_venda " + direcaoOrdenacao + " LIMIT ? OFFSET ?";

        List<VendaPesquisaResponse> itens = jdbc.sql(baseSelect + filtro + agrupamento + ordenacao)
                .params(paramsPagina)
                .query((rs, n) -> new VendaPesquisaResponse(
                        rs.getLong("id_venda"), rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                        rs.getObject("data_venda", OffsetDateTime.class),
                        getLongOuNulo(rs, "id_cliente"), rs.getString("nome_cliente"),
                        getLongOuNulo(rs, "id_funcionario"), rs.getString("nome_funcionario"),
                        rs.getBigDecimal("valor_venda"), rs.getBoolean("cancelada")))
                .list();

        return new PaginaVendasPesquisa(itens, paginaAtual, tamanho, totalItens, totalPaginas, totalItensAtivos, somaValorAtivas);
    }

    @Transactional(readOnly = true)
    public VendaDetalhePesquisaResponse buscarDetalhe(Jwt jwt, long idVenda) {
        Venda venda = buscarVenda(idVenda);
        if (!ehAdmin(jwt) && venda.idEmpresa() != idEmpresaSessao(jwt)) {
            // Não vaza a existência de uma venda de outra empresa pra um OPERADOR.
            throw new ResponseStatusException(NOT_FOUND, "Venda não encontrada.");
        }

        List<ItemVendaPesquisaResponse> itens = buscarItens(idVenda);
        BigDecimal valorTotal = itens.stream().map(ItemVendaPesquisaResponse::valorItem).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal desconto = itens.stream().map(ItemVendaPesquisaResponse::valorDesconto).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<MovimentoCaixaPesquisaResponse> movimentosCaixa = buscarMovimentosCaixa(idVenda);
        RecebidoAReceber ra = buscarRecebidoAReceber(idVenda);
        List<ParcelaPesquisaResponse> parcelas = buscarParcelas(idVenda);
        String condicaoPagamento = condicaoPagamento(idVenda);

        return new VendaDetalhePesquisaResponse(
                venda.idVenda(), venda.idEmpresa(), venda.nomeEmpresa(), venda.dataVenda(),
                venda.idCliente(), venda.nomeCliente(), venda.cpfCnpj(), venda.fisicaJuridica(),
                venda.idFuncionario(), venda.nomeFuncionario(), condicaoPagamento, desconto, valorTotal,
                ra.recebido(), ra.aReceber(), venda.cancelada(), venda.dataCancelamento(), venda.nomeUsuarioCancelamento(),
                venda.motivoCancelamento(), itens, movimentosCaixa, !parcelas.isEmpty(), parcelas);
    }

    private record Venda(long idVenda, long idEmpresa, String nomeEmpresa, OffsetDateTime dataVenda,
                          Long idCliente, String nomeCliente, String cpfCnpj, Boolean fisicaJuridica,
                          Long idFuncionario, String nomeFuncionario, boolean cancelada, OffsetDateTime dataCancelamento,
                          String nomeUsuarioCancelamento, String motivoCancelamento) {
    }

    private Venda buscarVenda(long idVenda) {
        record CabecalhoVenda(long idVenda, long idEmpresa, String nomeEmpresa, OffsetDateTime dataVenda,
                               Long idCliente, String nomeCliente, String cpfCnpj, Boolean fisicaJuridica,
                               boolean cancelada, OffsetDateTime dataCancelamento, String nomeUsuarioCancelamento,
                               String motivoCancelamento) {
        }
        CabecalhoVenda c = jdbc.sql("""
                        SELECT v.id_venda, v.id_empresa, e.razao_social AS nome_empresa, v.data_venda,
                               v.id_cliente, cli.nome AS nome_cliente, cli.cpf_cnpj, cli.fisica_juridica,
                               v.cancelada, v.data_cancelamento, v.motivo_cancelamento,
                               u.nome_usuario AS nome_usuario_cancelamento
                        FROM venda v
                        JOIN empresa e ON e.id_empresa = v.id_empresa AND e.id_tenant = v.id_tenant
                        LEFT JOIN cliente cli ON cli.id_cliente = v.id_cliente AND cli.id_tenant = v.id_tenant
                        LEFT JOIN usuario u ON u.id_usuario = v.id_usuario_cancelamento AND u.id_tenant = v.id_tenant
                        WHERE v.id_tenant = plataforma.tenant_atual() AND v.id_venda = ?
                        """)
                .param(idVenda)
                .query((rs, n) -> new CabecalhoVenda(
                        rs.getLong("id_venda"), rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                        rs.getObject("data_venda", OffsetDateTime.class),
                        getLongOuNulo(rs, "id_cliente"), rs.getString("nome_cliente"), rs.getString("cpf_cnpj"),
                        (Boolean) rs.getObject("fisica_juridica"),
                        rs.getBoolean("cancelada"), rs.getObject("data_cancelamento", OffsetDateTime.class),
                        rs.getString("nome_usuario_cancelamento"), rs.getString("motivo_cancelamento")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Venda não encontrada."));

        FuncionarioVenda fv = buscarFuncionarioDaVenda(idVenda);
        return new Venda(c.idVenda(), c.idEmpresa(), c.nomeEmpresa(), c.dataVenda(), c.idCliente(), c.nomeCliente(),
                c.cpfCnpj(), c.fisicaJuridica(), fv.idFuncionario(), fv.nomeFuncionario(), c.cancelada(), c.dataCancelamento(),
                c.nomeUsuarioCancelamento(), c.motivoCancelamento());
    }

    private record FuncionarioVenda(Long idFuncionario, String nomeFuncionario) {
    }

    private FuncionarioVenda buscarFuncionarioDaVenda(long idVenda) {
        return jdbc.sql("""
                        SELECT pmd.id_funcionario, fn.nome AS nome_funcionario
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        LEFT JOIN funcionario fn ON fn.id_funcionario = pmd.id_funcionario AND fn.id_tenant = pmd.id_tenant
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                        LIMIT 1
                        """)
                .param(idVenda)
                .query((rs, n) -> new FuncionarioVenda(getLongOuNulo(rs, "id_funcionario"), rs.getString("nome_funcionario")))
                .optional()
                .orElse(new FuncionarioVenda(null, null));
    }

    private List<ItemVendaPesquisaResponse> buscarItens(long idVenda) {
        return jdbc.sql("""
                        SELECT pb.sku AS codigo, p.descricao AS descricao_produto, co.descricao AS variacao_cor,
                               ta.descricao AS variacao_tamanho, pmd.qtd_produto, pmd.preco_venda, pmd.valor_desconto,
                               pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto + pmd.valor_acrescimo AS valor_item
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                              AND pmd.credito_debito = 'D'
                        JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                        JOIN produto p        ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
                        LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                        ORDER BY pmd.id_movimento_detalhe
                        """)
                .param(idVenda)
                .query((rs, n) -> new ItemVendaPesquisaResponse(
                        rs.getString("codigo"), rs.getString("descricao_produto"), rs.getString("variacao_cor"),
                        rs.getString("variacao_tamanho"), rs.getBigDecimal("qtd_produto"), rs.getBigDecimal("preco_venda"),
                        rs.getBigDecimal("valor_desconto"), rs.getBigDecimal("valor_item")))
                .list();
    }

    private List<MovimentoCaixaPesquisaResponse> buscarMovimentosCaixa(long idVenda) {
        return jdbc.sql("""
                        SELECT cd.criado_em, cd.tipo_operacao::text AS tipo_operacao, tc.nome_carteira,
                               cd.id_venda, cd.id_lote_recebimento, cd.credito_debito, cd.valor
                        FROM caixa_detalhe cd
                        JOIN tipo_carteira tc ON tc.id_carteira = cd.id_carteira AND tc.id_tenant = cd.id_tenant
                        WHERE cd.id_tenant = plataforma.tenant_atual() AND cd.id_venda = ?
                        ORDER BY cd.criado_em
                        """)
                .param(idVenda)
                .query((rs, n) -> new MovimentoCaixaPesquisaResponse(
                        rs.getObject("criado_em", OffsetDateTime.class), rs.getString("tipo_operacao"),
                        rs.getString("nome_carteira"),
                        origem(getLongOuNulo(rs, "id_venda"), getLongOuNulo(rs, "id_lote_recebimento")),
                        rs.getString("credito_debito"), rs.getBigDecimal("valor")))
                .list();
    }

    /** {@code id_lote_recebimento} prioriza sobre {@code id_venda} — um recebimento de parcela
     *  grava os dois, e "Recebimento nº X" é o evento relevante daquele lançamento, não a venda
     *  original (mesmo critério de {@code CaixaService.origem}). */
    private static String origem(Long idVenda, Long idLoteRecebimento) {
        if (idLoteRecebimento != null) return "Recebimento nº " + idLoteRecebimento;
        if (idVenda != null) return "Venda nº " + idVenda;
        return "—";
    }

    private record RecebidoAReceber(BigDecimal recebido, BigDecimal aReceber) {
    }

    private RecebidoAReceber buscarRecebidoAReceber(long idVenda) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN cr.data_recebimento IS NOT NULL THEN cr.valor_recebido ELSE 0 END), 0) AS recebido,
                               COALESCE(SUM(CASE WHEN cr.data_recebimento IS NULL THEN cr.valor_receber ELSE 0 END), 0) AS a_receber
                        FROM contas_receber cr
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_venda = ?
                        """)
                .param(idVenda)
                .query((rs, n) -> new RecebidoAReceber(rs.getBigDecimal("recebido"), rs.getBigDecimal("a_receber")))
                .single();
    }

    private List<ParcelaPesquisaResponse> buscarParcelas(long idVenda) {
        record ParcelaBruta(int numeroParcela, OffsetDateTime dataVencimento, BigDecimal valor,
                             OffsetDateTime dataRecebimento, BigDecimal valorRecebido, BigDecimal valorJuros) {
        }
        List<ParcelaBruta> brutas = jdbc.sql("""
                        SELECT cr.numero_parcela, cr.data_vencimento, cr.valor_receber, cr.data_recebimento,
                               cr.valor_recebido, cr.valor_juros
                        FROM contas_receber cr
                        JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_venda = ? AND tc.categoria_carteira = 'CREDIARIO'
                        ORDER BY cr.numero_parcela
                        """)
                .param(idVenda)
                .query((rs, n) -> new ParcelaBruta(
                        rs.getInt("numero_parcela"), rs.getObject("data_vencimento", OffsetDateTime.class),
                        rs.getBigDecimal("valor_receber"), rs.getObject("data_recebimento", OffsetDateTime.class),
                        rs.getBigDecimal("valor_recebido"), rs.getBigDecimal("valor_juros")))
                .list();

        int totalParcelas = brutas.size();
        List<ParcelaPesquisaResponse> parcelas = new ArrayList<>();
        for (ParcelaBruta p : brutas) {
            parcelas.add(new ParcelaPesquisaResponse(
                    p.numeroParcela(), totalParcelas, p.dataVencimento(), p.valor(),
                    situacaoParcela(p.dataRecebimento(), p.dataVencimento()),
                    p.dataRecebimento(), p.valorRecebido(), p.valorJuros()));
        }
        return parcelas;
    }

    private static String situacaoParcela(OffsetDateTime dataRecebimento, OffsetDateTime dataVencimento) {
        if (dataRecebimento != null) return "PAGA";
        if (dataVencimento.isBefore(OffsetDateTime.now())) return "VENCIDA";
        return "ABERTA";
    }

    private record LinhaPagamento(String nomeCarteira, String categoria) {
    }

    /** "PIX + Cartão Débito" / "Crediário (3x)" — junta as formas de pagamento distintas usadas
     *  na venda; crediário ganha a contagem de parcelas entre parênteses. */
    private String condicaoPagamento(long idVenda) {
        List<LinhaPagamento> linhas = jdbc.sql("""
                        SELECT DISTINCT tc.nome_carteira, tc.categoria_carteira::text AS categoria
                        FROM contas_receber cr
                        JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_venda = ?
                        ORDER BY tc.nome_carteira
                        """)
                .param(idVenda)
                .query((rs, n) -> new LinhaPagamento(rs.getString("nome_carteira"), rs.getString("categoria")))
                .list();
        if (linhas.isEmpty()) return "—";

        List<String> partes = new ArrayList<>();
        for (LinhaPagamento l : linhas) {
            if ("CREDIARIO".equals(l.categoria())) {
                long parcelas = jdbc.sql("""
                                SELECT count(*) FROM contas_receber cr
                                JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                                WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_venda = ?
                                      AND tc.categoria_carteira = 'CREDIARIO'
                                """)
                        .param(idVenda).query(Long.class).single();
                partes.add(l.nomeCarteira() + " (" + parcelas + "x)");
            } else {
                partes.add(l.nomeCarteira());
            }
        }
        return String.join(" + ", partes);
    }

    /** {@code rs.getObject(coluna, Long.class)} não funciona pra colunas {@code integer}
     *  (driver do Postgres só converte pro tipo exato) — {@code getLong}+{@code wasNull} é o
     *  jeito seguro de ler uma coluna {@code integer} nullable como {@code Long}. */
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
