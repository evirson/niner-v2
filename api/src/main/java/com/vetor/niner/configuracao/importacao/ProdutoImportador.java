package com.vetor.niner.configuracao.importacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.vetor.niner.catalogo.GradeDtos.GradeResponse;
import com.vetor.niner.catalogo.GradeService;
import com.vetor.niner.catalogo.ProdutoDtos.ProdutoRequest;
import com.vetor.niner.catalogo.ProdutoService;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.LinhaErro;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;
import com.vetor.niner.configuracao.importacao.ImportacaoPlanilha.LinhaPlanilha;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Importação de {@code produto} (docs/telas/importacao-dados.md, seção "3. produto"). Layout
 * (2026-08-10, mudança de estrutura da planilha de origem — substitui o anterior "achatado" de
 * uma variação por linha): <b>uma linha do CSV é um produto inteiro</b>, sem cor/EAN/estoque —
 * só os campos do produto mais até {@value #MAX_TAMANHOS_GRADE} colunas {@code TAMANHO_1..20}
 * formando, em ordem, a grade de tamanhos daquele produto. Por linha: acha ou cria o produto
 * (dedup por DESCRICAO+MARCA+REFERENCIA, mesmo padrão do resto da importação) e, se o tenant usa
 * cor/grade, acha ou cria a grade correspondente à sequência de tamanhos e atribui a
 * {@code produto.id_grade}.
 *
 * <p>Variação (cor/tamanho), EAN e estoque inicial deixaram de fazer parte desta importação —
 * ficam para a Entrada de Produtos (ainda não construída, decisão de 2026-08-08 já registrada em
 * docs/telas/produto.md): lá é onde a geração em lote de combinações cor×grade nasce naturalmente,
 * junto da compra.
 *
 * <p><b>Grade "já cadastrada" (2026-08-10):</b> esta planilha não traz nome de grade nenhum — o
 * critério de "já existe" é o CONTEÚDO (a sequência ordenada de tamanhos), não um nome, via
 * {@link GradeService#obterOuCriarPorTamanhos}. Quando precisa criar, o nome é gerado a partir do
 * primeiro e do último tamanho da sequência (ex.: "GRADE 33-47"), com sufixo numérico se colidir
 * com uma grade existente de conteúdo diferente.
 */
@Service
public class ProdutoImportador implements ImportadorDeTabela {

    private static final int MAX_TAMANHOS_GRADE = 20;
    private static final String[] COLUNAS = colunasComTamanhos();

    private static String[] colunasComTamanhos() {
        List<String> colunas = new ArrayList<>(List.of(
                "CODIGO_PRODUTO", "MARCA", "REFERENCIA", "DESCRICAO", "PRECO_CUSTO", "PERCENTUAL_VENDA", "PRECO_VENDA",
                "DATA_INICIO_OFERTA", "DATA_FINAL_OFERTA", "PRECO_OFERTA", "CODIGO_NCM", "PESO_BRUTO", "PESO_LIQUIDO"));
        for (int i = 1; i <= MAX_TAMANHOS_GRADE; i++) {
            colunas.add("TAMANHO_" + i);
        }
        return colunas.toArray(new String[0]);
    }

    private final JdbcClient jdbc;
    private final ProdutoService produtoService;
    private final ConfiguracaoGeralService configuracaoGeralService;
    private final GradeService gradeService;
    private final ImportacaoSavepointExecutor savepoints;

    public ProdutoImportador(JdbcClient jdbc, ProdutoService produtoService,
                              ConfiguracaoGeralService configuracaoGeralService, GradeService gradeService,
                              ImportacaoSavepointExecutor savepoints) {
        this.jdbc = jdbc;
        this.produtoService = produtoService;
        this.configuracaoGeralService = configuracaoGeralService;
        this.gradeService = gradeService;
        this.savepoints = savepoints;
    }

    @Override
    public String chave() {
        return "produto";
    }

    @Override
    public String titulo() {
        return "Produtos (com grade de tamanhos)";
    }

    @Override
    public String descricao() {
        return "Produtos e a grade de tamanhos de cada um (TAMANHO_1..20). Sem variação/EAN/estoque nesta importação — isso fica para a Entrada de Produtos.";
    }

    @Override
    public byte[] modeloPlanilha() {
        List<String> linha = new ArrayList<>(List.of(
                "1", "BEIRA MAR", "SAND-001", "SANDALIA RASTEIRA VERAO", "35,00", "100", "70,00",
                "", "", "", "6402.99.90", "0,350", "0,300", "36", "37", "38", "39", "40"));
        while (linha.size() < COLUNAS.length) {
            linha.add("");
        }
        return ImportacaoPlanilha.gerarModelo(COLUNAS, linha.toArray(new String[0]));
    }

    @Override
    @Transactional
    public RelatorioImportacao processar(List<LinhaPlanilha> linhas, JsonNode escolhas, boolean confirmar, Jwt jwt) {
        boolean usaCorGrade = configuracaoGeralService.usaCorGrade();

        List<LinhaErro> erros = new ArrayList<>();
        int importadas = 0, ignoradas = 0;
        // Cache de tamanho por chamada (2026-08-10) — variável LOCAL, não campo da classe (o
        // importador é singleton Spring). O mesmo TAMANHO (ex. "36", "37"...) se repete em
        // praticamente todo produto do arquivo — sem cache seria um SELECT/INSERT em
        // cfg_tamanho por TAMANHO_N por linha, achado real de performance em ContasReceberImportador/
        // EstoqueImportador (mesmo padrão).
        Map<String, Long> tamanhoCache = new HashMap<>();

        for (LinhaPlanilha linha : linhas) {
            try {
                // SAVEPOINT por linha (2026-08-10): sem isto, uma exceção de banco (não só de
                // validação Java) numa linha deixa a transação do arquivo inteiro "abortada" e
                // toda linha seguinte falha em cascata com "current transaction is aborted"
                // (Postgres 25P02) — achado real ao validar uma planilha de Produtos.
                if (savepoints.executar(() -> processarLinha(linha, usaCorGrade, tamanhoCache))) {
                    importadas++;
                } else {
                    ignoradas++;
                }
            } catch (RuntimeException e) {
                erros.add(LinhaErro.de(linha.numeroLinha(), e));
            } finally {
                ImportacaoProgressoContext.avancar();
            }
        }

        RelatorioImportacao relatorio =
                RelatorioImportacao.concluir(confirmar, linhas.size(), importadas, ignoradas, erros, List.of());
        if (!relatorio.confirmado()) {
            throw new SimulacaoConcluidaException(relatorio);
        }
        return relatorio;
    }

    /** {@code true} se um produto novo foi criado; {@code false} se já existia (ignorada — mesma
     *  convenção de dedup do resto da importação: reaproveita, nunca duplica). */
    private boolean processarLinha(LinhaPlanilha linha, boolean usaCorGrade, Map<String, Long> tamanhoCache) {
        String descricao = exigir(linha, "DESCRICAO");
        String marca = linha.valor("MARCA");
        String referencia = linha.valor("REFERENCIA");
        String codigoProduto = linha.valor("CODIGO_PRODUTO");

        if (produtoJaExiste(descricao, marca, referencia, codigoProduto)) {
            return false;
        }

        Long idGrade = usaCorGrade ? resolverGrade(linha, tamanhoCache) : null;

        BigDecimal precoCusto = ImportacaoPlanilha.decimal("PRECO_CUSTO", linha.valor("PRECO_CUSTO"));
        BigDecimal percentualVenda = ImportacaoPlanilha.decimal("PERCENTUAL_VENDA", linha.valor("PERCENTUAL_VENDA"));
        BigDecimal precoVenda = ImportacaoPlanilha.decimal("PRECO_VENDA", linha.valor("PRECO_VENDA"));
        if (precoCusto == null || percentualVenda == null || precoVenda == null) {
            throw new IllegalArgumentException("PRECO_CUSTO, PERCENTUAL_VENDA e PRECO_VENDA são obrigatórios.");
        }

        // Início/final/preço de oferta são opcionais em conjunto (regra "tudo ou nada" —
        // preencheu um, os três viram obrigatórios). PRECO_OFERTA = "0" é tratado como "não
        // preenchido" (não `0,00`): planilha exportada de outro sistema costuma trazer zero em
        // vez de célula vazia numa coluna numérica (2026-08-06, achado real de teste).
        BigDecimal precoOferta = ImportacaoPlanilha.decimal("PRECO_OFERTA", linha.valor("PRECO_OFERTA"));
        if (precoOferta != null && precoOferta.signum() == 0) {
            precoOferta = null;
        }

        ProdutoRequest req = new ProdutoRequest(
                descricao, marca, referencia, precoCusto, percentualVenda, precoVenda,
                inicioDoDiaOuNulo(ImportacaoPlanilha.data("DATA_INICIO_OFERTA", linha.valor("DATA_INICIO_OFERTA"))),
                inicioDoDiaOuNulo(ImportacaoPlanilha.data("DATA_FINAL_OFERTA", linha.valor("DATA_FINAL_OFERTA"))),
                precoOferta,
                ncmExistenteOuNulo(linha.valor("CODIGO_NCM")),
                ImportacaoPlanilha.decimal("PESO_BRUTO", linha.valor("PESO_BRUTO")),
                ImportacaoPlanilha.decimal("PESO_LIQUIDO", linha.valor("PESO_LIQUIDO")),
                idGrade, true, List.of());
        long idProduto = produtoService.criar(req).idProduto();
        gravarCodigoImportacaoSeInformado(idProduto, codigoProduto);
        return true;
    }

    /** {@code CODIGO_PRODUTO} não é campo de {@link ProdutoRequest} (o cadastro manual não tem
     *  esse conceito) — grava direto, fora do service, só quando presente. É o que a Importação
     *  de Estoque (2026-08-10) usa depois para achar o produto certo em {@code ESTOQUES.csv},
     *  que sempre é importada DEPOIS de {@code PRODUTOS.csv}. */
    private void gravarCodigoImportacaoSeInformado(long idProduto, String codigoProduto) {
        if (codigoProduto == null || codigoProduto.isBlank()) {
            return;
        }
        jdbc.sql("UPDATE produto SET codigo_importacao = ? WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?")
                .params(codigoProduto.trim(), idProduto)
                .update();
    }

    /**
     * Dedup por DESCRICAO+MARCA+REFERENCIA **e** CODIGO_PRODUTO (2026-08-10, achado real testando
     * com a planilha de verdade do dono do produto): duas linhas com a mesma
     * DESCRICAO+MARCA+REFERENCIA mas {@code CODIGO_PRODUTO} diferente são produtos DIFERENTES no
     * sistema de origem (coincidência de texto, não duplicidade) — cada uma vira seu próprio
     * `produto`, com seu próprio `codigo_importacao`. Só conta como "já existe" quando
     * DESCRICAO+MARCA+REFERENCIA batem **e** o `codigo_importacao` também bate (comparação
     * NULL-safe via {@code IS NOT DISTINCT FROM} — cobre o caso comum de nenhum dos dois ter
     * código, que continua sendo tratado como duplicata pela descrição sozinha).
     */
    private boolean produtoJaExiste(String descricao, String marca, String referencia, String codigoProduto) {
        String descricaoN = descricao.trim().toUpperCase(Locale.ROOT);
        String marcaN = marca == null ? "" : marca.trim().toUpperCase(Locale.ROOT);
        String referenciaN = referencia == null ? "" : referencia.trim().toUpperCase(Locale.ROOT);
        String codigoProdutoN = (codigoProduto == null || codigoProduto.isBlank()) ? null : codigoProduto.trim();
        Optional<Long> existente = jdbc.sql("""
                        SELECT id_produto FROM produto
                        WHERE id_tenant = plataforma.tenant_atual()
                              AND UPPER(descricao) = ? AND UPPER(COALESCE(marca, '')) = ?
                              AND UPPER(COALESCE(referencia, '')) = ?
                              AND codigo_importacao IS NOT DISTINCT FROM ?
                        """)
                .params(descricaoN, marcaN, referenciaN, codigoProdutoN)
                .query(Long.class).optional();
        return existente.isPresent();
    }

    /** Lê {@code TAMANHO_1..20} (em ordem, ignorando colunas vazias) e acha/cria a grade com
     *  exatamente essa sequência — ver {@link GradeService#obterOuCriarPorTamanhos}. Exigido:
     *  tenant usa cor/grade, então todo produto precisa de uma (mesma regra de
     *  {@code ProdutoService}). */
    private Long resolverGrade(LinhaPlanilha linha, Map<String, Long> tamanhoCache) {
        List<String> tamanhos = new ArrayList<>();
        for (int i = 1; i <= MAX_TAMANHOS_GRADE; i++) {
            String v = linha.valor("TAMANHO_" + i);
            if (v != null && !v.isBlank()) {
                tamanhos.add(v.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (tamanhos.isEmpty()) {
            throw new IllegalArgumentException(
                    "Informe ao menos um TAMANHO_N — este tenant usa cor/grade (Parâmetros do Sistema).");
        }
        List<Long> idsTamanho = tamanhos.stream().map(t -> idTamanhoOuCriar(t, tamanhoCache)).toList();
        String nomeSugerido = "GRADE " + tamanhos.get(0) + "-" + tamanhos.get(tamanhos.size() - 1);
        GradeResponse grade = gradeService.obterOuCriarPorTamanhos(idsTamanho, nomeSugerido);
        return grade.idGrade();
    }

    /** Acha (ou cria) a linha de {@code cfg_tamanho} com essa descrição — mesmo princípio de
     *  find-or-create usado no resto da importação (fornecedor/categoria por nome). Cacheado por
     *  chamada (2026-08-10): o mesmo tamanho se repete em quase todo produto do arquivo. */
    private long idTamanhoOuCriar(String descricao, Map<String, Long> tamanhoCache) {
        Long cacheado = tamanhoCache.get(descricao);
        if (cacheado != null) {
            return cacheado;
        }
        long id = idTamanhoOuCriar(descricao);
        tamanhoCache.put(descricao, id);
        return id;
    }

    /** {@code id_tamanho} não é mais IDENTITY (V017, 2026-08-13): calculado por tenant, mesmo
     *  padrão de {@code TamanhoService.criar}. */
    private long idTamanhoOuCriar(String descricao) {
        Optional<Long> existente = jdbc.sql("""
                        SELECT id_tamanho FROM cfg_tamanho
                        WHERE id_tenant = plataforma.tenant_atual() AND descricao = ?
                        """)
                .param(descricao).query(Long.class).optional();
        if (existente.isPresent()) {
            return existente.get();
        }
        return jdbc.sql("""
                        INSERT INTO cfg_tamanho (id_tenant, id_tamanho, descricao)
                        VALUES (plataforma.tenant_atual(),
                            COALESCE((SELECT MAX(id_tamanho) FROM cfg_tamanho WHERE id_tenant = plataforma.tenant_atual()), 0) + 1,
                            ?)
                        RETURNING id_tamanho
                        """)
                .param(descricao).query(Long.class).single();
    }

    /**
     * NCM é opcional e é referência (FK) para {@code cfg_produto_ncm} — código que não existe
     * na tabela (ou vazio, ou em formato inválido) entra como {@code null} em vez de rejeitar a
     * linha (pedido do dono do produto, 2026-08-06): a violação de FK não é motivo para barrar
     * a importação inteira. Remove pontuação antes de conferir (planilha comum traz NCM como
     * "6402.99.90"; a tabela de referência guarda só os 8 dígitos, ex. "64029990") — se depois
     * de limpo ainda não bater com nenhum código cadastrado, é tratado como inválido.
     */
    private String ncmExistenteOuNulo(String codigoNcm) {
        if (codigoNcm == null || codigoNcm.isBlank()) {
            return null;
        }
        String codigoN = codigoNcm.replaceAll("[^0-9]", "");
        if (codigoN.isEmpty()) {
            return null;
        }
        Boolean existe = jdbc.sql("SELECT EXISTS (SELECT 1 FROM cfg_produto_ncm WHERE codigo_ncm = ?)")
                .param(codigoN)
                .query(Boolean.class).single();
        return Boolean.TRUE.equals(existe) ? codigoN : null;
    }

    private static String exigir(LinhaPlanilha l, String coluna) {
        String v = l.valor(coluna);
        if (v == null) {
            throw new IllegalArgumentException(coluna + " é obrigatório.");
        }
        return v;
    }

    private static OffsetDateTime inicioDoDiaOuNulo(LocalDate data) {
        return data == null ? null : data.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
