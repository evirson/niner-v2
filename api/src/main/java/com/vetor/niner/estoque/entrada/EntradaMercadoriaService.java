package com.vetor.niner.estoque.entrada;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.identidade.empresa.EmpresaService;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.AtualizarItemEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ContaPagarEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EfetivarEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaDetalheResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaEfetivadaResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaResumoResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ItemEntradaDetalheResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ItemEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ItemEntradaResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.PaginaEntradas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Entrada de Produtos por Compra (docs/telas/entrada-mercadoria.md) — primeiro service a gravar
 * `tipo_movimento = 'COMPRA'` no ledger (`produto_movimento_mestre`/`detalhe`, V019; o enum
 * existia desde V013, nunca usado até aqui). Comum aos 3 fluxos (Manual, XML — Fase 3, Planilha
 * — Fase 4): todos convergem neste único {@link #efetivar}, recebendo já resolvido
 * fornecedor+itens+(opcional) duplicatas — o parsing específico de cada fluxo (XML/Excel) vive
 * em services próprios que só preparam esse mesmo request, nunca gravam nada sozinhos.
 *
 * <p>ADMIN e OPERADOR têm acesso (mexe em estoque + eventualmente contas a pagar, não em caixa).
 */
@Service
public class EntradaMercadoriaService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;
    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "dataMovimento", "pmm.data_movimento",
            "fornecedor", "f.razao_social",
            "notaFiscal", "pmm.nota_fiscal");

    private final JdbcClient jdbc;
    private final ConfiguracaoGeralService configuracaoGeralService;
    private final ContasPagarService contasPagarService;
    private final EmpresaService empresaService;

    public EntradaMercadoriaService(JdbcClient jdbc, ConfiguracaoGeralService configuracaoGeralService,
                                     ContasPagarService contasPagarService, EmpresaService empresaService) {
        this.jdbc = jdbc;
        this.configuracaoGeralService = configuracaoGeralService;
        this.contasPagarService = contasPagarService;
        this.empresaService = empresaService;
    }

    /**
     * Confirma a entrada numa única transação: 1 {@code produto_movimento_mestre}
     * (`tipo_movimento='COMPRA'`) + N {@code produto_movimento_detalhe} (`credito_debito='C'`) —
     * a trigger {@code fn_atualiza_estoque_movimento} soma o estoque sozinha. Se
     * {@code chaveNfe} já foi importada pro tenant, 409 (idempotência, P2) antes de gravar
     * qualquer coisa. Se {@code cfg_geral.cfg_rateia_frete_entrada} está ligada e
     * {@code valorRateio} veio preenchido, distribui esse valor entre os itens proporcional ao
     * subtotal de cada um (gravado em {@code valor_acrescimo}). Se
     * {@code cfg_geral.cfg_reajusta_preco_entrada} está ligada, atualiza
     * {@code produto.preco_custo}/{@code preco_venda}/{@code reajustado_em} de cada produto
     * recebido a partir do custo desta entrada (mantendo o {@code percentual_venda} já
     * cadastrado). Se {@code xmlBruto} veio, grava 1 linha em {@code entrada_xml} (auditoria,
     * P3). Se {@code contasPagar} veio, grava N linhas em {@code contas_pagar}
     * ({@code id_plano_contas} = o do fornecedor).
     */
    @Transactional
    public EntradaEfetivadaResponse efetivar(Jwt jwt, EfetivarEntradaRequest req) {
        long idEmpresa = resolverIdEmpresa(jwt, req.idEmpresa());
        long idUsuario = Long.parseLong(jwt.getSubject());

        if (req.chaveNfe() != null && !req.chaveNfe().isBlank()) {
            boolean jaImportada = Boolean.TRUE.equals(jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM produto_movimento_mestre
                                WHERE id_tenant = plataforma.tenant_atual() AND chave_nfe = ?
                            )
                            """)
                    .param(req.chaveNfe()).query(Boolean.class).single());
            if (jaImportada) {
                throw new ConflitoDadosException("Esta nota fiscal (chave " + req.chaveNfe() + ") já foi importada.");
            }
        }

        Fornecedor fornecedor = buscarFornecedor(req.idFornecedor());
        List<ItemResolvido> itens = resolverItens(req.itens());

        boolean rateia = configuracaoGeralService.rateiaFreteEntrada()
                && req.valorRateio() != null && req.valorRateio().compareTo(BigDecimal.ZERO) > 0;
        BigDecimal baseRateio = itens.stream()
                .map(i -> i.precoCusto().multiply(i.qtd()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean reajusta = configuracaoGeralService.reajustaPrecoEntrada();
        String origem = req.xmlBruto() != null && !req.xmlBruto().isBlank() ? "entrada xml" : "entrada manual";

        OffsetDateTime dataMovimento = req.dataMovimento() != null
                ? req.dataMovimento().atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
                : OffsetDateTime.now();

        long idMovimento = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre
                            (id_tenant, id_empresa, tipo_movimento, id_fornecedor, nota_fiscal, id_usuario,
                             chave_nfe, serie_nota, data_movimento)
                        VALUES (plataforma.tenant_atual(), ?, 'COMPRA', ?, ?, ?, ?, ?, ?)
                        RETURNING id_movimento
                        """)
                .params(idEmpresa, req.idFornecedor(), req.notaFiscal(), idUsuario, req.chaveNfe(), req.serieNota(), dataMovimento)
                .query(Long.class).single();

        List<ItemEntradaResponse> itensResponse = new ArrayList<>();
        BigDecimal valorTotalNota = BigDecimal.ZERO;
        for (ItemResolvido item : itens) {
            BigDecimal valorAcrescimo = BigDecimal.ZERO;
            if (rateia && baseRateio.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal proporcao = item.precoCusto().multiply(item.qtd())
                        .divide(baseRateio, 10, RoundingMode.HALF_UP);
                valorAcrescimo = req.valorRateio().multiply(proporcao).setScale(2, RoundingMode.HALF_UP);
            }

            jdbc.sql("""
                            INSERT INTO produto_movimento_detalhe
                                (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto,
                                 preco_custo, preco_venda, valor_acrescimo, origem)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'C', ?, ?, ?, ?, ?)
                            """)
                    .params(idMovimento, idEmpresa, item.idVariacao(), item.qtd(), item.precoCusto(),
                            item.precoVendaAtual(), valorAcrescimo, origem)
                    .update();

            if (reajusta) {
                BigDecimal custoUnitarioComRateio = valorAcrescimo.compareTo(BigDecimal.ZERO) == 0
                        ? item.precoCusto()
                        : item.precoCusto().add(valorAcrescimo.divide(item.qtd(), 4, RoundingMode.HALF_UP));
                BigDecimal novoPrecoVenda = custoUnitarioComRateio
                        .multiply(BigDecimal.ONE.add(item.percentualVenda().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                        .setScale(2, RoundingMode.HALF_UP);
                jdbc.sql("""
                                UPDATE produto
                                SET preco_custo = ?, preco_venda = ?, reajustado_em = now(), atualizado_em = now()
                                WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?
                                """)
                        .params(custoUnitarioComRateio.setScale(2, RoundingMode.HALF_UP), novoPrecoVenda, item.idProduto())
                        .update();
            }

            BigDecimal valorTotalItem = item.precoCusto().multiply(item.qtd()).add(valorAcrescimo);
            valorTotalNota = valorTotalNota.add(valorTotalItem);
            itensResponse.add(new ItemEntradaResponse(item.idVariacao(), item.sku(), item.descricaoProduto(),
                    item.variacaoCor(), item.variacaoTamanho(), item.qtd(), item.precoCusto(), valorTotalItem));
        }

        if (req.xmlBruto() != null && !req.xmlBruto().isBlank()) {
            jdbc.sql("""
                            INSERT INTO entrada_xml (id_tenant, id_movimento, xml_bruto)
                            VALUES (plataforma.tenant_atual(), ?, ?)
                            """)
                    .params(idMovimento, req.xmlBruto()).update();
        }

        if (req.contasPagar() != null) {
            // Plano de contas de CUSTO do próprio tenant (Parâmetros do Sistema,
            // "Compra de Mercadoria para Revenda" por padrão) — não o plano do fornecedor
            // (correção 2026-08-12: `fornecedor.id_plano_contas` é a conta contábil do
            // fornecedor em si, não a conta de despesa da compra).
            String idPlanoContasCompra = configuracaoGeralService.idPlanoContasCompraMercadoria();
            for (ContaPagarEntradaRequest cp : req.contasPagar()) {
                contasPagarService.gravar(idEmpresa, req.idFornecedor(), idPlanoContasCompra, req.notaFiscal(),
                        cp.numeroDuplicata(), cp.dataVencimento(), cp.valor());
            }
        }

        return new EntradaEfetivadaResponse(idMovimento, idEmpresa, req.idFornecedor(), fornecedor.razaoSocial(),
                req.notaFiscal(), dataMovimento, valorTotalNota, itensResponse);
    }

    @Transactional(readOnly = true)
    public PaginaEntradas listar(Long idFornecedor, Integer notaFiscal, Integer pagina, Integer limite,
                                  String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao = ordenarPor == null
                ? "pmm.data_movimento" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "pmm.data_movimento");
        String direcaoOrdenacao = "ASC".equalsIgnoreCase(direcao) ? "ASC" : "DESC";

        StringBuilder filtro = new StringBuilder("""
                 WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.tipo_movimento = 'COMPRA'
                """);
        List<Object> params = new ArrayList<>();
        if (idFornecedor != null) {
            filtro.append(" AND pmm.id_fornecedor = ?");
            params.add(idFornecedor);
        }
        if (notaFiscal != null) {
            filtro.append(" AND pmm.nota_fiscal = ?");
            params.add(notaFiscal);
        }

        String base = """
                FROM produto_movimento_mestre pmm
                LEFT JOIN fornecedor f ON f.id_tenant = pmm.id_tenant AND f.id_fornecedor = pmm.id_fornecedor
                """;

        long totalItens = jdbc.sql("SELECT count(*) " + base + filtro).params(params).query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);

        List<EntradaResumoResponse> itens = jdbc.sql("""
                        SELECT pmm.id_movimento, pmm.data_movimento, pmm.id_fornecedor, f.razao_social,
                               pmm.nota_fiscal,
                               (SELECT count(*) FROM produto_movimento_detalhe d
                                 WHERE d.id_tenant = pmm.id_tenant AND d.id_movimento = pmm.id_movimento) AS qtd_itens,
                               (SELECT COALESCE(SUM(d.qtd_produto * d.preco_custo + d.valor_acrescimo), 0)
                                  FROM produto_movimento_detalhe d
                                 WHERE d.id_tenant = pmm.id_tenant AND d.id_movimento = pmm.id_movimento) AS valor_total,
                               (SELECT d.origem FROM produto_movimento_detalhe d
                                 WHERE d.id_tenant = pmm.id_tenant AND d.id_movimento = pmm.id_movimento LIMIT 1) AS origem
                        """ + base + filtro
                        + " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                        + ", pmm.id_movimento " + direcaoOrdenacao + " LIMIT ? OFFSET ?")
                .params(paramsPagina)
                .query(EntradaMercadoriaService::mapearResumo)
                .list();

        return new PaginaEntradas(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public EntradaDetalheResponse buscar(long idMovimento) {
        record Cabecalho(long idEmpresa, Long idFornecedor, String razaoSocial, Integer notaFiscal,
                          String chaveNfe, OffsetDateTime dataMovimento) {
        }
        Cabecalho c = jdbc.sql("""
                        SELECT pmm.id_empresa, pmm.id_fornecedor, f.razao_social, pmm.nota_fiscal,
                               pmm.chave_nfe, pmm.data_movimento
                        FROM produto_movimento_mestre pmm
                        LEFT JOIN fornecedor f ON f.id_tenant = pmm.id_tenant AND f.id_fornecedor = pmm.id_fornecedor
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_movimento = ?
                          AND pmm.tipo_movimento = 'COMPRA'
                        """)
                .param(idMovimento)
                .query((rs, n) -> new Cabecalho(rs.getLong("id_empresa"), getLongOuNulo(rs, "id_fornecedor"),
                        rs.getString("razao_social"), (Integer) rs.getObject("nota_fiscal"),
                        rs.getString("chave_nfe"), rs.getObject("data_movimento", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Entrada não encontrada."));

        List<ItemEntradaDetalheResponse> itens = jdbc.sql("""
                        SELECT d.id_movimento_detalhe, d.id_variacao, pb.sku, p.descricao AS descricao_produto,
                               co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho,
                               d.qtd_produto, d.preco_custo, d.valor_acrescimo
                        FROM produto_movimento_detalhe d
                        JOIN produto_barra pb ON pb.id_tenant = d.id_tenant AND pb.id_variacao = d.id_variacao
                        JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                        LEFT JOIN cfg_cor co ON co.id_tenant = pb.id_tenant AND co.id_cor = pb.id_cor
                        LEFT JOIN cfg_tamanho ta ON ta.id_tenant = pb.id_tenant AND ta.id_tamanho = pb.id_tamanho
                        WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_movimento = ?
                        ORDER BY d.id_movimento_detalhe
                        """)
                .param(idMovimento)
                .query((rs, n) -> {
                    BigDecimal qtd = rs.getBigDecimal("qtd_produto");
                    BigDecimal precoCusto = rs.getBigDecimal("preco_custo");
                    BigDecimal valorAcrescimo = rs.getBigDecimal("valor_acrescimo");
                    return new ItemEntradaDetalheResponse(rs.getLong("id_movimento_detalhe"), rs.getLong("id_variacao"),
                            rs.getString("sku"), rs.getString("descricao_produto"), rs.getString("variacao_cor"),
                            rs.getString("variacao_tamanho"), qtd, precoCusto, valorAcrescimo,
                            qtd.multiply(precoCusto).add(valorAcrescimo));
                })
                .list();

        BigDecimal valorTotal = itens.stream().map(ItemEntradaDetalheResponse::valorTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new EntradaDetalheResponse(idMovimento, c.idEmpresa(), c.idFornecedor(), c.razaoSocial(),
                c.notaFiscal(), c.chaveNfe(), c.dataMovimento(), valorTotal, itens);
    }

    /** Correção pós-confirmação — edição direta (decisão do dono do produto, 2026-08-11), sem
     *  tabela de histórico por ora. A trigger de estoque já trata UPDATE (desfaz o delta antigo,
     *  aplica o novo), então não há nenhum ajuste manual de saldo aqui. */
    @Transactional
    public void atualizarItem(long idMovimento, long idMovimentoDetalhe, AtualizarItemEntradaRequest req) {
        int linhas = jdbc.sql("""
                        UPDATE produto_movimento_detalhe
                        SET qtd_produto = ?, preco_custo = ?
                        WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ? AND id_movimento_detalhe = ?
                        """)
                .params(req.qtd(), req.precoCusto(), idMovimento, idMovimentoDetalhe)
                .update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Item da entrada não encontrado.");
        }
    }

    /** Resolve a empresa que recebe a mercadoria (2026-08-12): sem `idEmpresa` informado, cai
     *  no `eid` da sessão (comportamento de sempre — preserva o fluxo Individual). Informado,
     *  valida que o usuário pode operar essa empresa (ADMIN: qualquer uma do tenant; OPERADOR:
     *  só as ligadas a ele), reaproveitando {@link EmpresaService#listarPermitidas}. */
    private long resolverIdEmpresa(Jwt jwt, Long idEmpresaInformada) {
        if (idEmpresaInformada == null) {
            return ((Number) jwt.getClaim("eid")).longValue();
        }
        boolean permitida = empresaService.listarPermitidas(jwt).stream()
                .anyMatch(e -> e.idEmpresa() == idEmpresaInformada);
        if (!permitida) {
            throw new ResponseStatusException(NOT_FOUND, "Empresa não encontrada ou não liberada para este usuário.");
        }
        return idEmpresaInformada;
    }

    private record Fornecedor(String razaoSocial, String idPlanoContas) {
    }

    private Fornecedor buscarFornecedor(long idFornecedor) {
        return jdbc.sql("""
                        SELECT razao_social, id_plano_contas FROM fornecedor
                        WHERE id_tenant = plataforma.tenant_atual() AND id_fornecedor = ?
                        """)
                .param(idFornecedor)
                .query((rs, n) -> new Fornecedor(rs.getString("razao_social"), rs.getString("id_plano_contas")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Fornecedor não encontrado."));
    }

    private record ItemResolvido(long idVariacao, long idProduto, BigDecimal qtd, BigDecimal precoCusto,
                                  BigDecimal precoVendaAtual, BigDecimal percentualVenda,
                                  String sku, String descricaoProduto, String variacaoCor, String variacaoTamanho) {
    }

    private record LinhaVariacao(long idProduto, String sku, String descricaoProduto, BigDecimal precoVenda,
                                  BigDecimal percentualVenda, String variacaoCor, String variacaoTamanho) {
    }

    /** Resolve descrição/variação/preço-de-venda-atual de cada item a partir do {@code
     *  idVariacao} — a tela nunca envia esses dados, só {@code idVariacao}/{@code qtd}/{@code
     *  precoCusto} (mesmo princípio do PDV/Devolução/Transferência). Não checa saldo — saldo
     *  negativo é permitido de propósito em qualquer movimentação (2026-07-29); entrada nunca
     *  teria saldo negativo de qualquer forma (só soma). */
    private List<ItemResolvido> resolverItens(List<ItemEntradaRequest> itens) {
        List<ItemResolvido> resolvidos = new ArrayList<>();
        for (ItemEntradaRequest item : itens) {
            LinhaVariacao linha = jdbc.sql("""
                            SELECT p.id_produto, pb.sku, p.descricao AS descricao_produto, p.preco_venda,
                                   p.percentual_venda, co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho
                            FROM produto_barra pb
                            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                            LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant
                            LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant
                            WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ? AND p.ativo = true
                            """)
                    .param(item.idVariacao())
                    .query((rs, n) -> new LinhaVariacao(rs.getLong("id_produto"), rs.getString("sku"),
                            rs.getString("descricao_produto"), rs.getBigDecimal("preco_venda"),
                            rs.getBigDecimal("percentual_venda"), rs.getString("variacao_cor"),
                            rs.getString("variacao_tamanho")))
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Produto informado não existe ou está inativo."));

            resolvidos.add(new ItemResolvido(item.idVariacao(), linha.idProduto(), item.qtd(), item.precoCusto(),
                    linha.precoVenda(), linha.percentualVenda(), linha.sku(), linha.descricaoProduto(),
                    linha.variacaoCor(), linha.variacaoTamanho()));
        }
        return resolvidos;
    }

    private static EntradaResumoResponse mapearResumo(ResultSet rs, int n) throws SQLException {
        return new EntradaResumoResponse(rs.getLong("id_movimento"), rs.getObject("data_movimento", OffsetDateTime.class),
                getLongOuNulo(rs, "id_fornecedor"), rs.getString("razao_social"), (Integer) rs.getObject("nota_fiscal"),
                rs.getInt("qtd_itens"), rs.getBigDecimal("valor_total"), rs.getString("origem"));
    }

    /** {@code rs.getObject(coluna, Long.class)} não converte `integer` pra `Long` de forma
     *  confiável no driver — mesmo padrão já usado em `ProdutoBarraService`/`CrmService`. */
    private static Long getLongOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }
}
