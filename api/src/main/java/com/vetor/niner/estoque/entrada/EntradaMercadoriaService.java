package com.vetor.niner.estoque.entrada;

import com.vetor.niner.comum.tempo.FusoDaLoja;
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
import java.nio.charset.StandardCharsets;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EntradaMercadoriaService.class);

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
    private final FusoDaLoja fusoDaLoja;

    public EntradaMercadoriaService(JdbcClient jdbc, ConfiguracaoGeralService configuracaoGeralService,
                                     ContasPagarService contasPagarService, EmpresaService empresaService,
                                     FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.configuracaoGeralService = configuracaoGeralService;
        this.contasPagarService = contasPagarService;
        this.empresaService = empresaService;
        this.fusoDaLoja = fusoDaLoja;
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
            // "AND cancelado = false" (2026-08-12, Cancelamento de Entrada) — mesmo critério do
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

        // Meia-noite de Brasília, não UTC (2026-08-12) — meia-noite UTC de "15/06" é 21h de
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
        // Sem o rateio de frete/IPI/ICMS-ST — é o "total dos produtos" que a tela exibe e a base
        // da consistência com as duplicatas (ver a checagem depois do laço).
        BigDecimal valorTotalProdutos = BigDecimal.ZERO;
        // ⚠️ O RESÍDUO DA DÍZIMA VAI NO ÚLTIMO ITEM (auditoria 2026-08-29, rodada 4). Arredondando
        // item a item, R$ 10,00 rateados entre 3 itens iguais viravam 3 × R$ 3,33 = R$ 9,99: um
        // centavo de frete sumia do custo a cada entrada, em silêncio, e ninguém comparava a soma
        // com o valor digitado. Não descasa dinheiro pago (as duplicatas são conferidas contra o
        // total SEM rateio), mas contamina custo e margem — e é a mesma regra de dízima que o
        // projeto já aplica no desconto: arredonda uma vez, o resto vai para a última linha.
        BigDecimal rateioDistribuido = BigDecimal.ZERO;
        int indiceItem = 0;
        for (ItemResolvido item : itens) {
            indiceItem++;
            BigDecimal valorAcrescimo = BigDecimal.ZERO;
            if (rateia && baseRateio.compareTo(BigDecimal.ZERO) > 0) {
                if (indiceItem == itens.size()) {
                    valorAcrescimo = req.valorRateio().setScale(2, RoundingMode.HALF_UP)
                            .subtract(rateioDistribuido);
                } else {
                    BigDecimal proporcao = item.precoCusto().multiply(item.qtd())
                            .divide(baseRateio, 10, RoundingMode.HALF_UP);
                    valorAcrescimo = req.valorRateio().multiply(proporcao).setScale(2, RoundingMode.HALF_UP);
                    rateioDistribuido = rateioDistribuido.add(valorAcrescimo);
                }
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
            // (2026-08-12, fluxo Individual com grade — o campo calculado/editável na tela)
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
                // Preço de venda nunca pode ficar abaixo do preço de custo (2026-08-12, regra do
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

            // Aprendizado do fluxo XML (Fase 3, 2026-08-12): grava/atualiza o vínculo código-do-
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

            // NCM do XML sempre vale (2026-08-13, pedido do dono do produto): se o item trouxe
            // um NCM (já validado contra cfg_produto_ncm em EntradaXmlService) e ele é diferente
            // do que o produto já tem cadastrado, SUBSTITUI — não é sugestão, é correção. Só
            // grava quando de fato muda, pra não bater atualizado_em à toa em toda entrada.
            if (item.ncm() != null && !item.ncm().equals(item.codigoNcmAtual())) {
                jdbc.sql("""
                                UPDATE produto SET codigo_ncm = ?, atualizado_em = now()
                                WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?
                                """)
                        .params(item.ncm(), item.idProduto())
                        .update();
            }

            BigDecimal valorTotalItem = item.precoCusto().multiply(item.qtd()).add(valorAcrescimo);
            valorTotalNota = valorTotalNota.add(valorTotalItem);
            valorTotalProdutos = valorTotalProdutos.add(item.precoCusto().multiply(item.qtd()));
            itensResponse.add(new ItemEntradaResponse(item.idVariacao(), item.sku(), item.descricaoProduto(),
                    item.variacaoCor(), item.variacaoTamanho(), item.qtd(), item.precoCusto(), valorTotalItem));
        }

        if (req.xmlBruto() != null && !req.xmlBruto().isBlank()) {
            // ⚠️ O XML nasce no BANCO e só depois migra para o bucket (V051). A ordem é deliberada:
            // gravar no MinIO dentro desta transação seria I/O de rede segurando a transação que
            // move estoque (F2), e um rollback depois do upload deixaria objeto órfão numa área
            // IMUTÁVEL, que não aceita apagar. Assim o XML nunca se perde: se o bucket estiver
            // fora, ele fica aqui e o job de arquivamento leva depois.
            jdbc.sql("""
                            INSERT INTO entrada_xml (id_tenant, id_movimento, xml_bruto)
                            VALUES (plataforma.tenant_atual(), ?, ?)
                            """)
                    .params(idMovimento, req.xmlBruto()).update();
            gravarTributacaoDosItens(idMovimento, req.xmlBruto(), req.itens());
        }

        // Consistência entre o total dos produtos e a soma das duplicatas (2026-08-11 na tela;
        // virou o parâmetro `cfg_consiste_valor_contas_pagar` em 2026-08-14, ligado por padrão).
        // Defesa em profundidade: a tela já bloqueia Confirmar, mas a API não confiava só no
        // frontend em nenhuma outra regra desta feature e não deve começar aqui.
        // A base de comparação é o total dos produtos SEM o rateio de frete/IPI/ICMS-ST — a
        // mesma que a tela mostra em "Total dos produtos" —, senão uma nota com rateio ligado
        // seria rejeitada por uma diferença que o operador não tem como enxergar.
        if (req.contasPagar() != null && !req.contasPagar().isEmpty()
                && configuracaoGeralService.consisteValorContasPagar()) {
            BigDecimal somaDuplicatas = req.contasPagar().stream()
                    .map(ContaPagarEntradaRequest::valor)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (somaDuplicatas.compareTo(valorTotalProdutos.setScale(2, RoundingMode.HALF_UP)) != 0) {
                throw new IllegalArgumentException(
                        "A soma das duplicatas (%s) precisa ser igual ao total dos produtos (%s)."
                                .formatted(somaDuplicatas.setScale(2, RoundingMode.HALF_UP),
                                        valorTotalProdutos.setScale(2, RoundingMode.HALF_UP)));
            }
        }

        if (req.contasPagar() != null) {
            // Plano de contas de CUSTO do próprio tenant (Parâmetros do Sistema,
            // "Compra de Mercadoria para Revenda" por padrão) — não o plano do fornecedor
            // (correção 2026-08-11: `fornecedor.id_plano_contas` é a conta contábil do
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
     * Pesquisa de produto do fluxo Individual (2026-08-12) — nível produto (não variação), sem
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
                        LEFT JOIN cfg_cor co ON co.id_tenant = pb.id_tenant AND co.id_cor = pb.id_cor AND co.id_cor <> 1
                        LEFT JOIN cfg_tamanho ta ON ta.id_tenant = pb.id_tenant AND ta.id_tamanho = pb.id_tamanho AND ta.id_tamanho <> 1
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
     * Cancelamento de Entrada (2026-08-12) — mesmo padrão de {@code CancelamentoVendaService}/
     * {@code CancelamentoDevolucaoService}: quem governa é o `@Acao(EXCLUIR)` do RBAC (não mais
     * ADMIN por papel — a afirmação era falsa desde a V073), o {@code produto_movimento_mestre}
     * original NUNCA é apagado nem tem os itens tocados (P3), só marcado {@code cancelado=true}
     * (quem/quando/motivo). O estorno de estoque é um novo movimento (tipo CANCELAMENTO,
     * {@code credito_debito='D'} — inverso do 'C' da COMPRA original) e as duplicatas geradas em
     * {@code contas_pagar} são apagadas (mesmo princípio de {@code caixa_detalhe}/
     * {@code contas_receber} no cancelamento de venda — nada a auditar ali além do já registrado
     * no ledger de estoque e no próprio {@code produto_movimento_mestre} cancelado).
     */
    @Transactional
    public CancelamentoEntradaEfetivadoResponse cancelar(Jwt jwt, long idMovimento, CancelarEntradaRequest req) {
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
                    "A entrada nº " + idMovimento + " já foi cancelada em "
                            + fusoDaLoja.formatar(c.dataCancelamento(), c.idEmpresa(), FMT_DATA)
                            + " por " + c.nomeUsuarioCancelamento() + ".");
        }

        // Bloqueio análogo ao "crediário já recebido" da Venda.
        //
        // ⚠️ A marca de baixa é `data_pagamento`, NÃO `documento_pago`. Este guard checava
        // `documento_pago = true` e o comentário antigo dizia que "não existe tela de baixa de
        // contas_pagar, então nunca fica true" — obsoleto desde 2026-08-12 (a tela Contas a Pagar
        // existe) e perigoso desde 2026-08-14 (a baixa passou a gerar caixa_detalhe /
        // conta_corrente_movimento). `documento_pago` é um checkbox independente que nasce false,
        // então uma conta baixada de verdade passava batido: o DELETE abaixo apagava a
        // contas_pagar e deixava o movimento de dinheiro ÓRFÃO para sempre — exatamente o bug
        // corrigido em ContaPagarService.excluir() em 08-14, reproduzido aqui.
        // Ver feedback_delete_sem_fk_deixa_orfao: vínculo sem FK não avisa quando o DELETE esquece
        // o outro lado. Bloquear é mais seguro que cascatear: quem quiser cancelar estorna a baixa
        // pela tela dona, que já sabe apagar o movimento e checar caixa fechado.
        boolean temContaPaga = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM contas_pagar
                            WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                              AND data_pagamento IS NOT NULL
                        )
                        """)
                .param(idMovimento).query(Boolean.class).single());
        if (temContaPaga) {
            throw new ConflitoDadosException(
                    "Esta entrada tem conta a pagar já baixada. Não é possível cancelá-la — "
                            + "desfaça o pagamento em Contas a Pagar antes de cancelar a entrada nº "
                            + idMovimento + ".");
        }

        // Terceiro bloqueio, mesma família dos dois acima (auditoria 2026-08-21, item 1).
        //
        // ⚠️ O estorno abaixo lança um 'D' da quantidade CHEIA da compra. Se parte dela já saiu
        // por uma Devolução ao Fornecedor, essas unidades são debitadas DUAS vezes: entrada de 10,
        // devolução de 4, cancelamento estorna 10 → estoque -4. E com
        // `cfg_permite_estoque_negativo` ligado (o padrão), a trigger não barra: passa em silêncio.
        // Pior ainda no lado fiscal — a devolução emite NF-e 55 autorizada, que ficaria válida na
        // SEFAZ referenciando uma entrada que não existe mais, e a entrada some da lista elegível,
        // então nem dá para desfazer pela tela de devolução.
        //
        // Bloquear em vez de cascatear, pelo mesmo motivo do guard da conta paga: quem quiser
        // cancelar desfaz primeiro pela tela dona, que sabe cancelar o evento na SEFAZ.
        boolean temDevolucao = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM produto_movimento_mestre dv
                            WHERE dv.id_tenant = plataforma.tenant_atual() AND dv.id_movimento_origem = ?
                              AND dv.tipo_movimento = 'DEVOLUCAO_COMPRA' AND dv.cancelado = false
                        )
                        """)
                .param(idMovimento).query(Boolean.class).single());
        if (temDevolucao) {
            throw new ConflitoDadosException(
                    "Esta entrada já teve mercadoria devolvida ao fornecedor. Não é possível "
                            + "cancelá-la — cancele a devolução em Estoque › Devolução de Produtos "
                            + "Comprados antes de cancelar a entrada nº " + idMovimento + ".");
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

    /**
     * ⛔ REMOVIDO em 2026-08-29 (pendência 59): havia aqui um `exigirAdmin(Jwt)` privado que
     * <b>nenhum caminho chamava</b> — código morto desde que o RBAC por tela e ação assumiu
     * (V073–V081). Quem governa este cancelamento hoje é o `@Acao(EXCLUIR)` do controller, que o
     * `PermissaoInterceptor` traduz na permissão de "excluir" desta tela.
     *
     * <p>⚠️ Isso muda quem pode desfazer: com o RBAC, o administrador <b>pode conceder</b> a ação
     * a um operador — antes era papel fixo. É comportamento intencional do RBAC, e o javadoc que
     * dizia "ADMIN-only" descrevia um mundo que não existia mais.
     */

    /** Correção pós-confirmação — edição direta (decisão do dono do produto, 2026-08-11), sem
     *  tabela de histórico por ora. A trigger de estoque já trata UPDATE (desfaz o delta antigo,
     *  aplica o novo), então não há nenhum ajuste manual de saldo aqui. */
    @Transactional
    public void atualizarItem(long idMovimento, long idMovimentoDetalhe, AtualizarItemEntradaRequest req) {
        // ⛔ Entrada CANCELADA não se edita (pendência 58.4, fechada em 2026-08-29). O UPDATE não
        // conferia `pmm.cancelado`, e a trigger de estoque trata UPDATE aplicando o delta: editar
        // um item de uma entrada já cancelada **mexia no saldo** de uma compra que, para o
        // sistema, nunca entrou — e o cancelamento já havia lançado o movimento inverso. Nenhuma
        // tela chama isso (a edição só aparece em entrada ativa); é alcançável por chamada direta
        // à API, que é ameaça que este repositório trata.
        // ⚠️ `FOR UPDATE` no mestre: sem ele, um cancelamento em curso e esta edição leriam o
        // mesmo `cancelado = false` e as duas passariam.
        Boolean cancelado = jdbc.sql("""
                        SELECT cancelado FROM produto_movimento_mestre
                         WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                           FOR UPDATE
                        """)
                .param(idMovimento).query(Boolean.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Entrada não encontrada."));
        if (Boolean.TRUE.equals(cancelado)) {
            throw new ConflitoDadosException(
                    "Esta entrada foi cancelada — o estoque e as contas a pagar dela já foram "
                            + "revertidos. Lance uma entrada nova em vez de corrigir esta.");
        }

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

    /** Resolve a empresa que recebe a mercadoria (2026-08-11): sem `idEmpresa` informado, cai
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
                                  /** `cProd` do XML (2026-08-12) — `null` fora do fluxo XML.
                                   *  Alimenta o aprendizado de {@code produto_fornecedor} em
                                   *  {@link #efetivar}, mesmo em item resolvido por EAN. */
                                  String codigoFornecedor,
                                  /** NCM do XML (2026-08-13, `ItemEntradaRequest.ncm`) — `null`
                                   *  fora do fluxo XML. Substitui {@code codigoNcmAtual} na
                                   *  confirmação quando os dois vierem diferentes (o NCM do XML
                                   *  sempre vale — ver {@link #efetivar}). */
                                  String ncm,
                                  /** {@code produto.codigo_ncm} já cadastrado — usado só pra
                                   *  decidir se {@code ncm} é de fato diferente, evitando um
                                   *  UPDATE à toa quando já bate. */
                                  String codigoNcmAtual) {
    }

    private record LinhaVariacao(long idProduto, String sku, String descricaoProduto, BigDecimal precoVenda,
                                  BigDecimal percentualVenda, String codigoNcm, String variacaoCor, String variacaoTamanho) {
    }

    /** Resolve descrição/variação/preço-de-venda-atual de cada item a partir do {@code
     *  idVariacao} — a tela nunca envia esses dados, só {@code idVariacao}/{@code qtd}/{@code
     *  precoCusto} (mesmo princípio do PDV/Devolução/Transferência). Não checa saldo, e aqui isso
     *  nunca importaria: entrada só SOMA. Débito é que passa pela regra de estoque negativo
     *  (trigger `fn_atualiza_estoque_movimento`, V054/V055). */
    /**
     * Grava a tributação que o fornecedor declarou, item a item, em {@code entrada_nfe_item} (V051).
     *
     * <p><b>Para que serve:</b> é a fonte que a <b>devolução de compra</b> vai espelhar. A NF-e de
     * devolução tem de repetir os valores e bases da nota de origem para o estorno bater, e
     * {@code produto_movimento_detalhe} guarda só quantidade e custo — nenhum dado fiscal. É o mesmo
     * buraco que o B9 encontrou do outro lado ({@code documento_fiscal_item} nunca preenchida) e a
     * mesma solução: gravar no ato, em vez de parsear o XML lá na frente.
     *
     * <p><b>Por que reparsear o XML aqui</b> em vez de o front devolver a tributação junto: são ~30
     * campos por item que o operador não vê nem edita: trafegá-los pela tela só criaria chance de
     * chegarem adulterados. O XML já vem no {@code EfetivarEntradaRequest} (é ele que a idempotência
     * usa), então o parse é local e barato.
     *
     * <p><b>Ligação com a variação</b> é por {@code cProd} + {@code cEAN}, a mesma dupla que o
     * preview usou para casar item com produto. Item que o operador não importou fica com
     * {@code id_variacao} nulo — a tributação dele continua registrada, porque a nota do fornecedor
     * declarou aquilo e a devolução pode precisar.
     */
    private void gravarTributacaoDosItens(long idMovimento, String xmlBruto,
                                          List<ItemEntradaRequest> itensDoRequest) {
        NfeXmlParser.NotaFiscalNfe nota;
        try {
            nota = NfeXmlParser.parse(new java.io.ByteArrayInputStream(xmlBruto.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            // O XML já foi aceito no preview; se falhar aqui, é defeito nosso e não do arquivo —
            // mas derrubar a entrada por causa disso seria pior que perder a tributação. Fica
            // registrado e a entrada segue; a devolução daquela nota é que não vai existir.
            log.warn("Não foi possível ler a tributação do XML da entrada {} — a entrada foi gravada, "
                    + "mas a devolução de compra dessa nota não terá de onde espelhar: {}", idMovimento, e.getMessage());
            return;
        }

        Map<String, Long> variacaoPorCodigo = variacoesDaEntrada(idMovimento, itensDoRequest);
        for (NfeXmlParser.ItemNfe item : nota.itens()) {
            NfeXmlParser.TributacaoItemNfe t = item.tributacao();
            jdbc.sql("""
                            INSERT INTO entrada_nfe_item (
                                id_tenant, id_movimento, numero_item, id_variacao,
                                codigo_produto, codigo_ean, descricao, codigo_ncm, cest, cfop,
                                unidade_comercial, quantidade, valor_unitario, valor_produto, valor_desconto,
                                valor_frete, valor_seguro, valor_outros, origem_mercadoria,
                                cst_icms, csosn, base_calculo_icms, perc_reducao_bc, aliquota_icms, valor_icms,
                                base_calculo_st, mva_st, aliquota_st, valor_icms_st, base_st_retido, icms_st_retido,
                                aliquota_fcp, valor_fcp,
                                cst_ipi, base_calculo_ipi, aliquota_ipi, valor_ipi,
                                cst_pis, base_calculo_pis, aliquota_pis, valor_pis,
                                cst_cofins, base_calculo_cofins, aliquota_cofins, valor_cofins)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                    ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (id_tenant, id_movimento, numero_item) DO NOTHING
                            """)
                    .params(idMovimento, item.nItem(),
                            variacaoPorCodigo.get(chaveDoItem(item)),
                            item.cProd(), item.cEan(), item.xProd(), item.ncm(), t.cest(), t.cfop(),
                            t.unidadeComercial(), item.qtd(), item.valorUnitario(), t.valorProduto(),
                            nz(item.valorDesconto()), t.valorFrete(), t.valorSeguro(), t.valorOutros(),
                            t.origemMercadoria(),
                            t.cstIcms(), t.csosn(), t.baseIcms(), t.percReducaoBc(), t.aliquotaIcms(), t.valorIcms(),
                            t.baseSt(), t.mvaSt(), t.aliquotaSt(), t.valorIcmsSt(), t.baseStRetido(), t.icmsStRetido(),
                            t.aliquotaFcp(), t.valorFcp(),
                            t.cstIpi(), t.baseIpi(), t.aliquotaIpi(), t.valorIpi(),
                            t.cstPis(), t.basePis(), t.aliquotaPis(), t.valorPis(),
                            t.cstCofins(), t.baseCofins(), t.aliquotaCofins(), t.valorCofins())
                    .update();
        }
    }

    /** Variações que ESTA entrada movimentou, indexadas por `sku` e por `ean` — as duas chaves que
     *  o preview usa para casar o item do fornecedor com o nosso produto. */
    private Map<String, Long> variacoesDaEntrada(long idMovimento, List<ItemEntradaRequest> itensDoRequest) {
        Map<String, Long> porCodigo = new java.util.HashMap<>();
        // ⛔ O CÓDIGO DO FORNECEDOR, que o OPERADOR já casou com a variação nesta tela (auditoria
        // 2026-08-29, rodada 4). O mapa era montado só com o NOSSO `sku` e o NOSSO `ean`, e a chave
        // do item é `cEAN ?? cProd` — mas `NfeXmlParser` converte `cEAN` ausente ou "SEM GTIN" em
        // null, e aí a chave vira o `cProd`, o código DO FORNECEDOR, que por definição não é nem um
        // nem outro. Resultado: em toda nota SEM GTIN (confecção, fornecedor pequeno) o
        // `entrada_nfe_item.id_variacao` nascia NULL, e ninguém via nada — o estoque entrava certo.
        // ⛔ A conta só chegava na DEVOLUÇÃO ao fornecedor, semanas depois: os itens APARECEM na
        // tela (a view não depende do vínculo), a devolução é gravada, o estoque BAIXA, e só então
        // a montagem da NF-e 55 não acha o item e recusa com "quantidade acima do que a nota
        // trouxe — cancele e refaça a seleção". Mercadoria baixada, sem nota, e a mensagem culpando
        // o operador por um erro de seleção que ele não cometeu.
        // ⚠️ Este par (código do fornecedor → variação) é exatamente o que a tela resolveu na aba
        // "Não Localizados" e o que `produto_fornecedor` aprende; reinferir pelo XML joga fora o
        // trabalho que o operador já fez.
        for (ItemEntradaRequest item : itensDoRequest) {
            if (item.codigoFornecedor() != null && !item.codigoFornecedor().isBlank()) {
                porCodigo.put(item.codigoFornecedor().trim(), item.idVariacao());
            }
        }
        jdbc.sql("""
                        SELECT DISTINCT pb.id_variacao, pb.sku, pb.ean
                          FROM produto_movimento_detalhe pmd
                          JOIN produto_barra pb ON pb.id_tenant = pmd.id_tenant AND pb.id_variacao = pmd.id_variacao
                         WHERE pmd.id_tenant = plataforma.tenant_atual() AND pmd.id_movimento = ?
                        """)
                .param(idMovimento)
                .query((rs, n) -> {
                    long idVariacao = rs.getLong("id_variacao");
                    porCodigo.put(rs.getString("sku"), idVariacao);
                    if (rs.getString("ean") != null) {
                        porCodigo.put(rs.getString("ean"), idVariacao);
                    }
                    return idVariacao;
                })
                .list();
        return porCodigo;
    }

    private static String chaveDoItem(NfeXmlParser.ItemNfe item) {
        return item.cEan() != null ? item.cEan() : item.cProd();
    }

    private static BigDecimal nz(BigDecimal valor) {
        return valor == null ? BigDecimal.ZERO : valor;
    }

    private List<ItemResolvido> resolverItens(List<ItemEntradaRequest> itens) {
        List<ItemResolvido> resolvidos = new ArrayList<>();
        for (ItemEntradaRequest item : itens) {
            LinhaVariacao linha = jdbc.sql("""
                            SELECT p.id_produto, pb.sku, p.descricao AS descricao_produto, p.preco_venda,
                                   p.percentual_venda, p.codigo_ncm, co.descricao AS variacao_cor,
                                   ta.descricao AS variacao_tamanho
                            FROM produto_barra pb
                            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                            LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
                            LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
                            WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ? AND p.ativo = true
                            """)
                    .param(item.idVariacao())
                    .query((rs, n) -> new LinhaVariacao(rs.getLong("id_produto"), rs.getString("sku"),
                            rs.getString("descricao_produto"), rs.getBigDecimal("preco_venda"),
                            rs.getBigDecimal("percentual_venda"), rs.getString("codigo_ncm"), rs.getString("variacao_cor"),
                            rs.getString("variacao_tamanho")))
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Produto informado não existe ou está inativo."));

            BigDecimal precoVendaAtual = item.precoVenda() != null ? item.precoVenda() : linha.precoVenda();
            resolvidos.add(new ItemResolvido(item.idVariacao(), linha.idProduto(), item.qtd(), item.precoCusto(),
                    precoVendaAtual, linha.percentualVenda(), linha.sku(), linha.descricaoProduto(),
                    linha.variacaoCor(), linha.variacaoTamanho(), item.precoVenda(), item.codigoFornecedor(),
                    item.ncm(), linha.codigoNcm()));
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
