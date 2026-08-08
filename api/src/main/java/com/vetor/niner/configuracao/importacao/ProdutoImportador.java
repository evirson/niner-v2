package com.vetor.niner.configuracao.importacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.vetor.niner.catalogo.GradeDtos.GradeResponse;
import com.vetor.niner.catalogo.GradeService;
import com.vetor.niner.catalogo.ProdutoBarraDtos.ProdutoBarraResponse;
import com.vetor.niner.catalogo.ProdutoBarraService;
import com.vetor.niner.catalogo.ProdutoDtos.ProdutoRequest;
import com.vetor.niner.catalogo.ProdutoService;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.configuracao.importacao.ImportacaoCsv.LinhaCsv;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.LinhaErro;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Importação de {@code produto} (docs/telas/importacao-dados.md, seção "3. produto") — a mais
 * complexa das quatro. Formato achatado: cada linha do CSV é uma variação (cor/tamanho) de um
 * produto maior. Ver {@link #analisar} para o passo prévio (mapeamento de colunas de estoque →
 * empresa), consumido por um endpoint dedicado ({@code POST /api/v1/importacao/produto/analise})
 * — fora da interface genérica {@link ImportadorDeTabela} porque nenhuma outra tabela precisa
 * desse tipo de reconhecimento prévio estruturado.
 *
 * <p><b>Cor/grade (2026-08-08):</b> {@code DESCRICAO_VARIANTE_LINHA}/{@code
 * DESCRICAO_VARIANTE_COLUNA} viraram {@code DESCRICAO_COR}/{@code DESCRICAO_TAMANHO}, e o antigo
 * "rótulo de variante" escolhido interativamente após o upload deixou de existir — cor/tamanho
 * têm nome fixo no sistema todo, não mais configurável por produto. No lugar, quando o tenant usa
 * cor/grade ({@code cfg_geral.cfg_usa_cor_grade}), toda linha com {@code DESCRICAO_TAMANHO}
 * precisa também trazer {@code NOME_GRADE} — o nome da grade daquele produto (achada por nome, ou
 * criada na hora com os tamanhos distintos encontrados nas linhas do grupo, na ordem de primeira
 * aparição). Quando o tenant não usa cor/grade, essas três colunas são ignoradas (mesmo princípio
 * de "campo oculto ⇒ servidor ignora" usado no resto do sistema) — produto nasce com 1 SKU só.
 */
@Service
public class ProdutoImportador implements ImportadorDeTabela {

    private static final int QTD_COLUNAS_ESTOQUE = 9;

    private final JdbcClient jdbc;
    private final ProdutoService produtoService;
    private final ProdutoBarraService produtoBarraService;
    private final ConfiguracaoGeralService configuracaoGeralService;
    private final GradeService gradeService;

    public ProdutoImportador(JdbcClient jdbc, ProdutoService produtoService, ProdutoBarraService produtoBarraService,
                              ConfiguracaoGeralService configuracaoGeralService, GradeService gradeService) {
        this.jdbc = jdbc;
        this.produtoService = produtoService;
        this.produtoBarraService = produtoBarraService;
        this.configuracaoGeralService = configuracaoGeralService;
        this.gradeService = gradeService;
    }

    @Override
    public String chave() {
        return "produto";
    }

    @Override
    public String titulo() {
        return "Produtos (com variação e estoque inicial)";
    }

    @Override
    public String descricao() {
        return "Produtos, variações (cor/tamanho, quando o tenant usa grade) e saldo inicial de estoque por empresa. Pede análise prévia do arquivo.";
    }

    @Override
    public byte[] modeloCsv() {
        StringBuilder cabecalho = new StringBuilder(
                "MARCA;REFERENCIA;DESCRICAO;PRECO_CUSTO;PERCENTUAL_VENDA;PRECO_VENDA;DATA_INICIO_OFERTA;"
                        + "DATA_FINAL_OFERTA;PRECO_OFERTA;CODIGO_NCM;PESO_BRUTO;PESO_LIQUIDO;NOME_GRADE;DESCRICAO_COR;"
                        + "DESCRICAO_TAMANHO;EAN_CODIGO_BARRAS");
        for (int i = 1; i <= QTD_COLUNAS_ESTOQUE; i++) {
            cabecalho.append(";QUANTIDADE_ESTOQUE_").append(i);
        }
        String linha1 = "BEIRA MAR;SAND-001;SANDALIA RASTEIRA VERAO;35,00;100;70,00;;;;6402.99.90;0,350;0,300;GRADE 35-40;AZUL;36;7891234567895;10;;;;;;;;";
        String linha2 = "BEIRA MAR;SAND-001;SANDALIA RASTEIRA VERAO;35,00;100;70,00;;;;6402.99.90;0,350;0,300;GRADE 35-40;AZUL;37;;5;;;;;;;;";
        return (cabecalho + "\r\n" + linha1 + "\r\n" + linha2 + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------
    // Passo prévio: análise do arquivo (colunas de estoque com dado)
    // ---------------------------------------------------------------------------------------

    public record AnaliseProduto(List<String> colunasEstoqueComDado) {
    }

    @Transactional(readOnly = true)
    public AnaliseProduto analisar(MultipartFile arquivo) {
        List<LinhaCsv> linhas = ImportacaoCsv.ler(arquivo);

        List<String> colunas = new ArrayList<>();
        for (int i = 1; i <= QTD_COLUNAS_ESTOQUE; i++) {
            String coluna = "QUANTIDADE_ESTOQUE_" + i;
            boolean temDado = linhas.stream().anyMatch(l -> {
                BigDecimal v = decimalSeguro(l.valor(coluna));
                return v != null && v.signum() > 0;
            });
            if (temDado) {
                colunas.add(coluna);
            }
        }
        return new AnaliseProduto(colunas);
    }

    // ---------------------------------------------------------------------------------------
    // Processamento
    // ---------------------------------------------------------------------------------------

    @Override
    @Transactional
    public RelatorioImportacao processar(List<LinhaCsv> linhas, JsonNode escolhas, boolean confirmar, Jwt jwt) {
        Map<Integer, Long> mapeamentoEmpresas = lerMapeamentoEmpresas(escolhas);
        boolean usaCorGrade = configuracaoGeralService.usaCorGrade();

        Map<String, List<LinhaCsv>> grupos = agrupar(linhas);
        List<LinhaErro> erros = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        int importadas = 0;
        // Um produto_movimento_mestre (AJUSTE) por empresa, reaproveitado por todo o arquivo —
        // mesmo padrão de BalancoEstoqueService.efetivar.
        Map<Long, Long> movimentoPorEmpresa = new LinkedHashMap<>();

        for (Map.Entry<String, List<LinhaCsv>> entradaGrupo : grupos.entrySet()) {
            List<LinhaCsv> linhasGrupo = entradaGrupo.getValue();
            try {
                importadas += processarGrupo(linhasGrupo, usaCorGrade, mapeamentoEmpresas, movimentoPorEmpresa, avisos);
            } catch (RuntimeException e) {
                for (LinhaCsv l : linhasGrupo) {
                    erros.add(LinhaErro.de(l.numeroLinha(), e));
                }
            }
        }

        RelatorioImportacao relatorio =
                RelatorioImportacao.concluir(confirmar, linhas.size(), importadas, 0, erros, avisos);
        if (!relatorio.confirmado()) {
            throw new SimulacaoConcluidaException(relatorio);
        }
        return relatorio;
    }

    /** Processa um grupo (= um produto) inteiro; retorna quantas linhas do CSV foram cobertas
     *  por variações criadas/atualizadas com sucesso. Lança se o produto em si não puder ser
     *  criado — nesse caso NENHUMA linha do grupo é considerada importada (ver chamador). */
    private int processarGrupo(List<LinhaCsv> linhasGrupo, boolean usaCorGrade, Map<Integer, Long> mapeamentoEmpresas,
                                Map<Long, Long> movimentoPorEmpresa, List<String> avisos) {
        LinhaCsv vencedora = escolherVencedora(linhasGrupo);
        String descricao = exigir(vencedora, "DESCRICAO");
        String marca = vencedora.valor("MARCA");
        String referencia = vencedora.valor("REFERENCIA");

        Long idGrade = usaCorGrade ? resolverGrade(linhasGrupo) : null;

        long idProduto = obterOuCriarProduto(vencedora, descricao, marca, referencia, idGrade);

        // Une linhas do grupo pela combinação (cor, tamanho) — mesma variação física, quantidades
        // de estoque somadas.
        Map<String, List<LinhaCsv>> subgrupos = new LinkedHashMap<>();
        for (LinhaCsv l : linhasGrupo) {
            String cor = l.valor("DESCRICAO_COR");
            String tamanho = l.valor("DESCRICAO_TAMANHO");
            String chaveSub = (cor == null ? "" : cor.toUpperCase(Locale.ROOT)) + "||"
                    + (tamanho == null ? "" : tamanho.toUpperCase(Locale.ROOT));
            subgrupos.computeIfAbsent(chaveSub, k -> new ArrayList<>()).add(l);
        }

        int cobertas = 0;
        for (List<LinhaCsv> subgrupo : subgrupos.values()) {
            cobertas += processarVariacao(idProduto, subgrupo, mapeamentoEmpresas, movimentoPorEmpresa, avisos);
        }
        return cobertas;
    }

    /** Acha (ou cria) a grade deste grupo a partir de {@code NOME_GRADE} + os tamanhos distintos
     *  em {@code DESCRICAO_TAMANHO} (ordem de primeira aparição no arquivo). Exigido — tenant usa
     *  cor/grade, então todo produto precisa de uma (mesma regra de {@code ProdutoService}). */
    private Long resolverGrade(List<LinhaCsv> linhasGrupo) {
        Set<String> nomesGrade = new LinkedHashSet<>();
        LinkedHashSet<String> tamanhos = new LinkedHashSet<>();
        for (LinhaCsv l : linhasGrupo) {
            String nomeGrade = l.valor("NOME_GRADE");
            if (nomeGrade != null && !nomeGrade.isBlank()) {
                nomesGrade.add(nomeGrade.trim().toUpperCase(Locale.ROOT));
            }
            String tamanho = l.valor("DESCRICAO_TAMANHO");
            if (tamanho != null && !tamanho.isBlank()) {
                tamanhos.add(tamanho.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (nomesGrade.isEmpty()) {
            throw new IllegalArgumentException("NOME_GRADE é obrigatório — este tenant usa cor/grade (Parâmetros do Sistema).");
        }
        if (nomesGrade.size() > 1) {
            throw new IllegalArgumentException("NOME_GRADE divergente entre linhas do mesmo produto: " + nomesGrade + ".");
        }
        List<Long> idsTamanho = tamanhos.stream().map(this::idTamanhoOuCriar).toList();
        GradeResponse grade = gradeService.obterOuCriarPorNome(nomesGrade.iterator().next(), idsTamanho);
        return grade.idGrade();
    }

    private long obterOuCriarProduto(LinhaCsv vencedora, String descricao, String marca, String referencia, Long idGrade) {
        String descricaoN = descricao.trim().toUpperCase(Locale.ROOT);
        String marcaN = marca == null ? "" : marca.trim().toUpperCase(Locale.ROOT);
        String referenciaN = referencia == null ? "" : referencia.trim().toUpperCase(Locale.ROOT);

        Optional<Long> existente = jdbc.sql("""
                        SELECT id_produto FROM produto
                        WHERE id_tenant = plataforma.tenant_atual()
                              AND UPPER(descricao) = ? AND UPPER(COALESCE(marca, '')) = ?
                              AND UPPER(COALESCE(referencia, '')) = ?
                        """)
                .params(descricaoN, marcaN, referenciaN)
                .query(Long.class).optional();
        if (existente.isPresent()) {
            return existente.get();
        }

        BigDecimal precoCusto = ImportacaoCsv.decimal("PRECO_CUSTO", vencedora.valor("PRECO_CUSTO"));
        BigDecimal percentualVenda = ImportacaoCsv.decimal("PERCENTUAL_VENDA", vencedora.valor("PERCENTUAL_VENDA"));
        BigDecimal precoVenda = ImportacaoCsv.decimal("PRECO_VENDA", vencedora.valor("PRECO_VENDA"));
        if (precoCusto == null || percentualVenda == null || precoVenda == null) {
            throw new IllegalArgumentException("PRECO_CUSTO, PERCENTUAL_VENDA e PRECO_VENDA são obrigatórios.");
        }

        // Início/final/preço de oferta são opcionais em conjunto (regra "tudo ou nada" —
        // preencheu um, os três viram obrigatórios; nenhum preenchido, produto nasce sem
        // oferta). PRECO_OFERTA = "0" é tratado como "não preenchido" (não `0,00`): planilha
        // exportada de outro sistema costuma trazer zero em vez de célula vazia em coluna
        // numérica, e uma oferta de R$ 0,00 não é um caso de negócio real — sem essa
        // normalização, "0" sozinho na coluna disparava a exigência das duas datas por engano
        // (2026-08-06, achado real de teste).
        BigDecimal precoOferta = ImportacaoCsv.decimal("PRECO_OFERTA", vencedora.valor("PRECO_OFERTA"));
        if (precoOferta != null && precoOferta.signum() == 0) {
            precoOferta = null;
        }

        ProdutoRequest req = new ProdutoRequest(
                descricao, marca, referencia, precoCusto, percentualVenda, precoVenda,
                inicioDoDiaOuNulo(ImportacaoCsv.data("DATA_INICIO_OFERTA", vencedora.valor("DATA_INICIO_OFERTA"))),
                inicioDoDiaOuNulo(ImportacaoCsv.data("DATA_FINAL_OFERTA", vencedora.valor("DATA_FINAL_OFERTA"))),
                precoOferta,
                ncmExistenteOuNulo(vencedora.valor("CODIGO_NCM")),
                ImportacaoCsv.decimal("PESO_BRUTO", vencedora.valor("PESO_BRUTO")),
                ImportacaoCsv.decimal("PESO_LIQUIDO", vencedora.valor("PESO_LIQUIDO")),
                idGrade, true, List.of());
        return produtoService.criar(req).idProduto();
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

    private int processarVariacao(long idProduto, List<LinhaCsv> subgrupo, Map<Integer, Long> mapeamentoEmpresas,
                                   Map<Long, Long> movimentoPorEmpresa, List<String> avisos) {
        LinhaCsv primeira = subgrupo.get(0);
        Long idCor = idCorOuNulo(primeira.valor("DESCRICAO_COR"));
        Long idTamanho = idTamanhoOuNulo(primeira.valor("DESCRICAO_TAMANHO"));

        ProdutoBarraResponse variacao = produtoBarraService.obterOuCriar(idProduto, idCor, idTamanho);
        atualizarEanSeNecessario(variacao, subgrupo, avisos);

        // Soma quantidade por coluna de estoque mapeada, através de todas as linhas do subgrupo
        // (linhas "duplicadas" da mesma variação, ex.: duas entradas separadas no arquivo antigo).
        for (Map.Entry<Integer, Long> mapeamento : mapeamentoEmpresas.entrySet()) {
            String coluna = "QUANTIDADE_ESTOQUE_" + mapeamento.getKey();
            BigDecimal total = BigDecimal.ZERO;
            for (LinhaCsv l : subgrupo) {
                BigDecimal v = ImportacaoCsv.decimal(coluna, l.valor(coluna));
                if (v != null) {
                    total = total.add(v);
                }
            }
            if (total.signum() > 0) {
                long idMovimento = movimentoPorEmpresa.computeIfAbsent(mapeamento.getValue(), this::criarMovimentoAjuste);
                jdbc.sql("""
                                INSERT INTO produto_movimento_detalhe
                                    (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto,
                                     preco_custo, origem)
                                SELECT plataforma.tenant_atual(), ?, ?, ?, 'C'::credito_debito, ?, p.preco_custo,
                                       'importação de dados'
                                FROM produto p WHERE p.id_tenant = plataforma.tenant_atual() AND p.id_produto = ?
                                """)
                        .params(idMovimento, mapeamento.getValue(), variacao.idVariacao(), total, idProduto)
                        .update();
            }
        }
        return subgrupo.size();
    }

    private long criarMovimentoAjuste(long idEmpresa) {
        return jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento)
                        VALUES (plataforma.tenant_atual(), ?, 'AJUSTE')
                        RETURNING id_movimento
                        """)
                .param(idEmpresa)
                .query(Long.class).single();
    }

    private void atualizarEanSeNecessario(ProdutoBarraResponse variacao, List<LinhaCsv> subgrupo, List<String> avisos) {
        Set<String> eansDistintos = new LinkedHashSet<>();
        for (LinhaCsv l : subgrupo) {
            String ean = l.valor("EAN_CODIGO_BARRAS");
            if (ean != null) {
                eansDistintos.add(ean.trim());
            }
        }
        if (eansDistintos.isEmpty()) {
            return;
        }
        if (eansDistintos.size() > 1) {
            avisos.add("Variação \"" + variacao.sku() + "\" tem EANs diferentes no arquivo (" + eansDistintos
                    + ") — usado o primeiro, os demais foram ignorados.");
        }
        // Já tem EAN gravado (ex.: reimportação) — não sobrescreve.
        Boolean jaTemEan = jdbc.sql("SELECT ean IS NOT NULL FROM produto_barra WHERE id_tenant = plataforma.tenant_atual() AND id_variacao = ?")
                .param(variacao.idVariacao()).query(Boolean.class).single();
        if (Boolean.TRUE.equals(jaTemEan)) {
            return;
        }
        try {
            jdbc.sql("UPDATE produto_barra SET ean = ? WHERE id_tenant = plataforma.tenant_atual() AND id_variacao = ?")
                    .params(eansDistintos.iterator().next(), variacao.idVariacao())
                    .update();
        } catch (DataIntegrityViolationException e) {
            avisos.add("EAN \"" + eansDistintos.iterator().next() + "\" já está em uso por outra variação — ignorado para \""
                    + variacao.sku() + "\".");
        }
    }

    private Long idCorOuNulo(String descricao) {
        return idOuNulo("cfg_cor", "id_cor", descricao);
    }

    private Long idTamanhoOuNulo(String descricao) {
        return idOuNulo("cfg_tamanho", "id_tamanho", descricao);
    }

    private long idTamanhoOuCriar(String descricao) {
        return idOuNulo("cfg_tamanho", "id_tamanho", descricao);
    }

    /** Acha (ou cria) a linha de {@code cfg_cor}/{@code cfg_tamanho} com essa descrição — mesmo
     *  princípio de find-or-create usado no resto da importação (fornecedor/categoria por nome). */
    private Long idOuNulo(String tabela, String coluna, String descricao) {
        if (descricao == null || descricao.isBlank()) {
            return null;
        }
        String d = descricao.trim().toUpperCase(Locale.ROOT);
        Optional<Long> existente = jdbc.sql(
                        "SELECT " + coluna + " FROM " + tabela + " WHERE id_tenant = plataforma.tenant_atual() AND descricao = ?")
                .param(d).query(Long.class).optional();
        if (existente.isPresent()) {
            return existente.get();
        }
        return jdbc.sql("INSERT INTO " + tabela + " (id_tenant, descricao) VALUES (plataforma.tenant_atual(), ?) RETURNING " + coluna)
                .param(d).query(Long.class).single();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private Map<String, List<LinhaCsv>> agrupar(List<LinhaCsv> linhas) {
        Map<String, List<LinhaCsv>> grupos = new LinkedHashMap<>();
        for (LinhaCsv l : linhas) {
            grupos.computeIfAbsent(chaveGrupo(l), k -> new ArrayList<>()).add(l);
        }
        return grupos;
    }

    private static String chaveGrupo(LinhaCsv l) {
        return norm(l.valor("DESCRICAO")) + "||" + norm(l.valor("MARCA")) + "||" + norm(l.valor("REFERENCIA"));
    }

    private static String norm(String v) {
        return v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
    }

    /** Linha com maior PRECO_VENDA (empate: maior PRECO_CUSTO) fornece os campos de nível-produto
     *  do grupo inteiro — regra combinada com o dono do produto para dados divergentes. */
    private static LinhaCsv escolherVencedora(List<LinhaCsv> linhasGrupo) {
        LinhaCsv vencedora = null;
        BigDecimal melhorVenda = null;
        BigDecimal melhorCusto = null;
        for (LinhaCsv l : linhasGrupo) {
            BigDecimal venda = decimalSeguro(l.valor("PRECO_VENDA"));
            BigDecimal custo = decimalSeguro(l.valor("PRECO_CUSTO"));
            if (venda == null) {
                continue;
            }
            if (vencedora == null || venda.compareTo(melhorVenda) > 0
                    || (venda.compareTo(melhorVenda) == 0 && custo != null && (melhorCusto == null || custo.compareTo(melhorCusto) > 0))) {
                vencedora = l;
                melhorVenda = venda;
                melhorCusto = custo;
            }
        }
        return vencedora == null ? linhasGrupo.get(0) : vencedora;
    }

    private static BigDecimal decimalSeguro(String valor) {
        try {
            return ImportacaoCsv.decimal("valor", valor);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code escolhas.mapeamentoEmpresas}: {@code {"QUANTIDADE_ESTOQUE_1": 10, "QUANTIDADE_ESTOQUE_3": 12}}
     *  — chave = nome da coluna, valor = id da empresa. Colunas do arquivo sem dado nenhum não
     *  precisam estar aqui (o front só pergunta pelas que {@link #analisar} detectou com dado). */
    private static Map<Integer, Long> lerMapeamentoEmpresas(JsonNode escolhas) {
        Map<Integer, Long> mapa = new LinkedHashMap<>();
        JsonNode nodeMapa = escolhas == null ? null : escolhas.get("mapeamentoEmpresas");
        if (nodeMapa == null) {
            return mapa;
        }
        for (var campo : nodeMapa.properties()) {
            String coluna = campo.getKey();
            if (!coluna.startsWith("QUANTIDADE_ESTOQUE_")) {
                continue;
            }
            int numero = Integer.parseInt(coluna.substring("QUANTIDADE_ESTOQUE_".length()));
            mapa.put(numero, campo.getValue().asLong());
        }
        return mapa;
    }

    private static String exigir(LinhaCsv l, String coluna) {
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
