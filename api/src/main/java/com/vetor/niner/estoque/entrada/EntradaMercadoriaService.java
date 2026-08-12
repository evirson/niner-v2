package com.vetor.niner.estoque.entrada;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.identidade.empresa.EmpresaService;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.AtualizarItemEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.CancelamentoEntradaEfetivadoResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.CancelarEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ContaPagarEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EfetivarEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaDetalheResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaEfetivadaResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaResumoResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ItemEntradaDetalheResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ItemEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ItemEntradaResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.PaginaEntradas;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ProdutoOpcaoEntradaResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private static final DateTimeFormatter FMT_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
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
            // "AND cancelado = false" (2026-08-19, Cancelamento de Entrada) — mesmo critério do
            // índice único parcial (produto_movimento_mestre_chave_nfe_uk, V019): uma entrada
            // cancelada libera a chave pra reimportar a mesma NF-e corrigida.
            boolean jaImportada = Boolean.TRUE.equals(jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM produto_movimento_mestre
                                WHERE id_tenant = plataforma.tenant_atual() AND chave_nfe = ? AND cancelado = false
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

        // Meia-noite de Brasília, não UTC (2026-08-19) — meia-noite UTC de "15/06" é 21h de
        // "14/06" em Brasília; gravar em UTC fazia a data digitada voltar da tela um dia atrasada
        // (mesma família de bug de fuso já documentada pro filtro de período desta listagem).
        OffsetDateTime dataMovimento = req.dataMovimento() != null
                ? req.dataMovimento().atStartOfDay(java.time.ZoneId.of("America/Sao_Paulo")).toOffsetDateTime()
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

            // Atualiza produto.preco_custo/preco_venda quando a flag automática está ligada OU
            // quando o operador informou explicitamente o preço de venda nesta entrada
            // (2026-08-15, fluxo Individual com grade — o campo calculado/editável na tela)
            // — a decisão deliberada do operador vale independente da flag.
            if (reajusta || item.precoVendaInformado() != null) {
                BigDecimal custoUnitarioComRateio = valorAcrescimo.compareTo(BigDecimal.ZERO) == 0
                        ? item.precoCusto()
                        : item.precoCusto().add(valorAcrescimo.divide(item.qtd(), 4, RoundingMode.HALF_UP));
                BigDecimal novoPrecoVenda = item.precoVendaInformado() != null
                        ? item.precoVendaInformado()
                        : custoUnitarioComRateio
                                .multiply(BigDecimal.ONE.add(item.percentualVenda().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                                .setScale(2, RoundingMode.HALF_UP);
                // Preço de venda nunca pode ficar abaixo do preço de custo (2026-08-17, regra do
                // projeto inteiro) — checado aqui contra o custo JÁ com rateio, porque é o que
                // de fato vai pro `produto.preco_custo` no UPDATE logo abaixo.
                if (novoPrecoVenda.compareTo(custoUnitarioComRateio.setScale(2, RoundingMode.HALF_UP)) < 0) {
                    throw new IllegalArgumentException("Preço de venda não pode ser menor que o preço de custo.");
                }
                jdbc.sql("""
                                UPDATE produto
                                SET preco_custo = ?, preco_venda = ?, reajustado_em = now(), atualizado_em = now()
                                WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?
                                """)
                        .params(custoUnitarioComRateio.setScale(2, RoundingMode.HALF_UP), novoPrecoVenda, item.idProduto())
                        .update();
            }

            // Aprendizado do fluxo XML (Fase 3, 2026-08-18): grava/atualiza o vínculo código-do-
            // fornecedor × variação — a próxima nota do MESMO fornecedor com este `cProd` já
            // resolve sozinha, sem precisar de EAN nem de heurística de texto (ver
            // EntradaXmlService). Upsert porque o operador pode reapontar manualmente uma
            // variação errada aprendida antes (ex.: resolveu pra cor errada por engano).
            if (item.codigoFornecedor() != null && !item.codigoFornecedor().isBlank()) {
                jdbc.sql("""
                                INSERT INTO produto_fornecedor (id_tenant, id_fornecedor, codigo_fornecedor, id_variacao)
                                VALUES (plataforma.tenant_atual(), ?, ?, ?)
                                ON CONFLICT (id_tenant, id_fornecedor, codigo_fornecedor)
                                DO UPDATE SET id_variacao = EXCLUDED.id_variacao
                                """)
                        .params(req.idFornecedor(), item.codigoFornecedor(), item.idVariacao())
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
                contasPagarService.gravar(idEmpresa, req.idFornecedor(), idPlanoContasCompra, idMovimento,
                        req.notaFiscal(), cp.numeroDuplicata(), cp.dataVencimento(), cp.valor());
            }
        }

        return new EntradaEfetivadaResponse(idMovimento, idEmpresa, req.idFornecedor(), fornecedor.razaoSocial(),
                req.notaFiscal(), dataMovimento, valorTotalNota, itensResponse);
    }

    @Transactional(readOnly = true)
    public PaginaEntradas listar(Long idFornecedor, Long idEmpresa, Integer notaFiscal, LocalDate dataInicial,
                                  LocalDate dataFinal, Integer pagina, Integer limite, String ordenarPor, String direcao) {
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
        if (idEmpresa != null) {
            filtro.append(" AND pmm.id_empresa = ?");
            params.add(idEmpresa);
        }
        if (notaFiscal != null) {
            filtro.append(" AND pmm.nota_fiscal = ?");
            params.add(notaFiscal);
        }
        // "AT TIME ZONE 'America/Sao_Paulo'" (não UTC, a sessão do banco) — sem isso, uma entrada
        // lançada às 22h de Brasília (já 01h UTC do dia seguinte) cai no dia ERRADO do filtro:
        // a tela mostra a data em horário local (`new Date(iso).toLocaleDateString`), então o
        // filtro precisa bucketizar pelo mesmo dia local, não pelo dia UTC.
        if (dataInicial != null) {
            filtro.append(" AND (pmm.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            params.add(dataInicial);
        }
        if (dataFinal != null) {
            filtro.append(" AND (pmm.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            params.add(dataFinal);
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
                                 WHERE d.id_tenant = pmm.id_tenant AND d.id_movimento = pmm.id_movimento LIMIT 1) AS origem,
                               pmm.cancelado
                        """ + base + filtro
                        + " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                        + ", pmm.id_movimento " + direcaoOrdenacao + " LIMIT ? OFFSET ?")
                .params(paramsPagina)
                .query(EntradaMercadoriaService::mapearResumo)
                .list();

        return new PaginaEntradas(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    /**
     * Pesquisa de produto do fluxo Individual (2026-08-15) — nível produto (não variação), sem
     * estoque nem cor/tamanho: só o que a tela precisa pra escolher o produto e, na sequência,
     * montar a grade de tamanhos + custo/%venda/preço de venda. Mesmo estilo enxuto de
     * {@code EtiquetaEmissaoService.buscarProdutos} (não reaproveita {@code ProdutoService.listar},
     * que traz categorias/imagens por linha — caro demais pra uma busca "digitar e listar").
     */
    @Transactional(readOnly = true)
    public List<ProdutoOpcaoEntradaResponse> buscarProdutos(String busca, String marca, String referencia) {
        StringBuilder filtro = new StringBuilder(" WHERE p.id_tenant = plataforma.tenant_atual() AND p.ativo = true");
        List<Object> params = new ArrayList<>();
        if (busca != null && !busca.isBlank()) {
            filtro.append(" AND p.descricao ILIKE ?");
            params.add("%" + busca.trim() + "%");
        }
        if (marca != null && !marca.isBlank()) {
            filtro.append(" AND p.marca ILIKE ?");
            params.add("%" + marca.trim() + "%");
        }
        if (referencia != null && !referencia.isBlank()) {
            filtro.append(" AND p.referencia ILIKE ?");
            params.add("%" + referencia.trim() + "%");
        }
        return jdbc.sql("""
                        SELECT p.id_produto, p.descricao, p.marca, p.referencia, p.id_grade,
                               p.preco_custo, p.percentual_venda
                        FROM produto p
                        """ + filtro + " ORDER BY p.descricao ASC LIMIT 20")
                .params(params)
                .query((rs, n) -> new ProdutoOpcaoEntradaResponse(
                        rs.getLong("id_produto"), rs.getString("descricao"), rs.getString("marca"),
                        rs.getString("referencia"), getLongOuNulo(rs, "id_grade"),
                        rs.getBigDecimal("preco_custo"), rs.getBigDecimal("percentual_venda")))
                .list();
    }

    @Transactional(readOnly = true)
    public EntradaDetalheResponse buscar(long idMovimento) {
        record Cabecalho(long idEmpresa, Long idFornecedor, String razaoSocial, Integer notaFiscal,
                          String chaveNfe, OffsetDateTime dataMovimento, boolean cancelado,
                          OffsetDateTime dataCancelamento, String motivoCancelamento) {
        }
        Cabecalho c = jdbc.sql("""
                        SELECT pmm.id_empresa, pmm.id_fornecedor, f.razao_social, pmm.nota_fiscal,
                               pmm.chave_nfe, pmm.data_movimento, pmm.cancelado, pmm.data_cancelamento,
                               pmm.motivo_cancelamento
                        FROM produto_movimento_mestre pmm
                        LEFT JOIN fornecedor f ON f.id_tenant = pmm.id_tenant AND f.id_fornecedor = pmm.id_fornecedor
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_movimento = ?
                          AND pmm.tipo_movimento = 'COMPRA'
                        """)
                .param(idMovimento)
                .query((rs, n) -> new Cabecalho(rs.getLong("id_empresa"), getLongOuNulo(rs, "id_fornecedor"),
                        rs.getString("razao_social"), (Integer) rs.getObject("nota_fiscal"),
                        rs.getString("chave_nfe"), rs.getObject("data_movimento", OffsetDateTime.class),
                        rs.getBoolean("cancelado"), rs.getObject("data_cancelamento", OffsetDateTime.class),
                        rs.getString("motivo_cancelamento")))
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
                c.notaFiscal(), c.chaveNfe(), c.dataMovimento(), valorTotal, itens, c.cancelado(),
                c.dataCancelamento(), c.motivoCancelamento());
    }

    /**
     * Cancelamento de Entrada (2026-08-19) — mesmo padrão de {@code CancelamentoVendaService}/
     * {@code CancelamentoDevolucaoService}: ADMIN-only, o {@code produto_movimento_mestre}
     * original NUNCA é apagado nem tem os itens tocados (P3), só marcado {@code cancelado=true}
     * (quem/quando/motivo). O estorno de estoque é um novo movimento (tipo CANCELAMENTO,
     * {@code credito_debito='D'} — inverso do 'C' da COMPRA original) e as duplicatas geradas em
     * {@code contas_pagar} são apagadas (mesmo princípio de {@code caixa_detalhe}/
     * {@code contas_receber} no cancelamento de venda — nada a auditar ali além do já registrado
     * no ledger de estoque e no próprio {@code produto_movimento_mestre} cancelado).
     */
    @Transactional
    public CancelamentoEntradaEfetivadoResponse cancelar(Jwt jwt, long idMovimento, CancelarEntradaRequest req) {
        exigirAdmin(jwt);
        long idUsuario = Long.parseLong(jwt.getSubject());

        record Cabecalho(long idEmpresa, Long idFornecedor, boolean cancelado, OffsetDateTime dataCancelamento,
                          String nomeUsuarioCancelamento) {
        }
        Cabecalho c = jdbc.sql("""
                        SELECT pmm.id_empresa, pmm.id_fornecedor, pmm.cancelado, pmm.data_cancelamento,
                               u.nome_usuario AS nome_usuario_cancelamento
                        FROM produto_movimento_mestre pmm
                        LEFT JOIN usuario u ON u.id_tenant = pmm.id_tenant AND u.id_usuario = pmm.id_usuario_cancelamento
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_movimento = ?
                          AND pmm.tipo_movimento = 'COMPRA'
                        FOR UPDATE OF pmm
                        """)
                .param(idMovimento)
                .query((rs, n) -> new Cabecalho(rs.getLong("id_empresa"), getLongOuNulo(rs, "id_fornecedor"),
                        rs.getBoolean("cancelado"), rs.getObject("data_cancelamento", OffsetDateTime.class),
                        rs.getString("nome_usuario_cancelamento")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Entrada não encontrada."));

        if (c.cancelado()) {
            throw new ConflitoDadosException(
                    "A entrada nº " + idMovimento + " já foi cancelada em " + FMT_DATA.format(c.dataCancelamento())
                            + " por " + c.nomeUsuarioCancelamento() + ".");
        }

        // Bloqueio análogo ao "crediário já recebido" da Venda — hoje não existe tela de baixa de
        // contas_pagar (ContasPagarService só grava), então documento_pago nunca fica true na
        // prática; mantido mesmo assim pra não ficar um buraco de segurança quando essa tela
        // existir.
        boolean temContaPaga = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM contas_pagar
                            WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ? AND documento_pago = true
                        )
                        """)
                .param(idMovimento).query(Boolean.class).single());
        if (temContaPaga) {
            throw new ConflitoDadosException(
                    "Esta entrada tem conta a pagar já quitada. Não é possível cancelá-la — "
                            + "estorne o pagamento antes de cancelar a entrada nº " + idMovimento + ".");
        }

        OffsetDateTime agora = OffsetDateTime.now();
        jdbc.sql("""
                        UPDATE produto_movimento_mestre
                        SET cancelado = true, data_cancelamento = ?, id_usuario_cancelamento = ?, motivo_cancelamento = ?
                        WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                        """)
                .params(agora, idUsuario, req.motivo(), idMovimento)
                .update();

        estornarEstoqueCompra(c.idEmpresa(), c.idFornecedor(), idMovimento);

        jdbc.sql("DELETE FROM contas_pagar WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?")
                .param(idMovimento)
                .update();

        return new CancelamentoEntradaEfetivadoResponse(idMovimento, agora);
    }

    /** Devolve ao estoque o que a compra tinha somado — novo {@code produto_movimento_mestre}
     *  (tipo CANCELAMENTO) + um {@code produto_movimento_detalhe} 'D' por item (a trigger já
     *  existente subtrai {@code produto_estoque} sozinha, mesmo mecanismo do
     *  {@code CancelamentoVendaService#estornarEstoque}, só que na direção oposta). */
    private void estornarEstoqueCompra(long idEmpresa, Long idFornecedor, long idMovimentoOriginal) {
        record ItemComprado(long idVariacao, BigDecimal qtd, BigDecimal precoCusto, BigDecimal precoVenda) {
        }
        List<ItemComprado> itensComprados = jdbc.sql("""
                        SELECT id_variacao, qtd_produto, preco_custo, preco_venda
                        FROM produto_movimento_detalhe
                        WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ? AND credito_debito = 'C'
                        """)
                .param(idMovimentoOriginal)
                .query((rs, n) -> new ItemComprado(rs.getLong("id_variacao"), rs.getBigDecimal("qtd_produto"),
                        rs.getBigDecimal("preco_custo"), rs.getBigDecimal("preco_venda")))
                .list();
        if (itensComprados.isEmpty()) return;

        long idMovimentoCancelamento = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_fornecedor)
                        VALUES (plataforma.tenant_atual(), ?, 'CANCELAMENTO', ?)
                        RETURNING id_movimento
                        """)
                .params(idEmpresa, idFornecedor).query(Long.class).single();

        for (ItemComprado item : itensComprados) {
            // preco_custo/preco_venda repetem o snapshot da COMPRA original (não uma nova
            // leitura de produto.*) — mesmo princípio do cancelamento de venda: o estorno é a
            // reversão exata daquele movimento, mesmo que o cadastro já tenha mudado desde então.
            jdbc.sql("""
                            INSERT INTO produto_movimento_detalhe
                                (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto,
                                 preco_custo, preco_venda)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'D', ?, ?, ?)
                            """)
                    .params(idMovimentoCancelamento, idEmpresa, item.idVariacao(), item.qtd(), item.precoCusto(),
                            item.precoVenda())
                    .update();
        }
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas administradores podem cancelar entradas.");
        }
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
                                  String sku, String descricaoProduto, String variacaoCor, String variacaoTamanho,
                                  /** Preço de venda que veio explícito no request ({@code
                                   *  ItemEntradaRequest.precoVenda}) — {@code null} quando o
                                   *  fluxo não informa (Planilha/XML, ou Individual sem grade
                                   *  ainda não migrado). Distinto de {@code precoVendaAtual}
                                   *  (sempre preenchido, usado no snapshot do detalhe): este
                                   *  campo é o que decide se o `produto.preco_venda` é
                                   *  atualizado na confirmação IGNORANDO {@code
                                   *  cfg_reajusta_preco_entrada} — ver {@link #efetivar}. */
                                  BigDecimal precoVendaInformado,
                                  /** `cProd` do XML (2026-08-18) — `null` fora do fluxo XML.
                                   *  Alimenta o aprendizado de {@code produto_fornecedor} em
                                   *  {@link #efetivar}, mesmo em item resolvido por EAN. */
                                  String codigoFornecedor) {
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

            BigDecimal precoVendaAtual = item.precoVenda() != null ? item.precoVenda() : linha.precoVenda();
            resolvidos.add(new ItemResolvido(item.idVariacao(), linha.idProduto(), item.qtd(), item.precoCusto(),
                    precoVendaAtual, linha.percentualVenda(), linha.sku(), linha.descricaoProduto(),
                    linha.variacaoCor(), linha.variacaoTamanho(), item.precoVenda(), item.codigoFornecedor()));
        }
        return resolvidos;
    }

    private static EntradaResumoResponse mapearResumo(ResultSet rs, int n) throws SQLException {
        return new EntradaResumoResponse(rs.getLong("id_movimento"), rs.getObject("data_movimento", OffsetDateTime.class),
                getLongOuNulo(rs, "id_fornecedor"), rs.getString("razao_social"), (Integer) rs.getObject("nota_fiscal"),
                rs.getInt("qtd_itens"), rs.getBigDecimal("valor_total"), rs.getString("origem"), rs.getBoolean("cancelado"));
    }

    /** {@code rs.getObject(coluna, Long.class)} não converte `integer` pra `Long` de forma
     *  confiável no driver — mesmo padrão já usado em `ProdutoBarraService`/`CrmService`. */
    private static Long getLongOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }
}
