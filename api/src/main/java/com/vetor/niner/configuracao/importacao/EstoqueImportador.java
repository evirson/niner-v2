package com.vetor.niner.configuracao.importacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.vetor.niner.catalogo.ProdutoBarraService;
import com.vetor.niner.catalogo.ProdutoBarraService.VariacaoResumo;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.LinhaErro;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;
import com.vetor.niner.configuracao.importacao.ImportacaoPlanilha.LinhaPlanilha;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Importação de estoque inicial (docs/telas/importacao-dados.md, "5. estoque") — tabela irmã da
 * de produto (2026-08-10), SEMPRE importada depois: acha o produto pelo {@code CODIGO_PRODUTO}
 * que a Importação de Produtos gravou em {@code produto.codigo_importacao} (não é o
 * {@code id_produto} — é só o código do sistema de origem, usado pra ligar as duas planilhas).
 * Por linha: acha/cria a variação (cor/tamanho, {@link ProdutoBarraService#obterOuCriar}, mesma
 * regra de obrigatoriedade — produto sem grade força cor/tamanho a {@code null}), atualiza o EAN
 * se ainda não tiver, e lança quantidade em {@code produto_estoque} via
 * {@code produto_movimento_detalhe} (tipo AJUSTE) para cada {@code QUANTIDADE_ESTOQUE_1..5}
 * mapeada a uma empresa (escolha do usuário, igual ao antigo passo de Produto).
 *
 * <p>Linhas com o mesmo produto/cor/tamanho são agrupadas e as quantidades somadas antes de
 * lançar o movimento — evita duplicar estoque se a mesma variação aparecer em mais de uma linha
 * do arquivo (mesmo cuidado do antigo formato "achatado" de Produto).
 */
@Service
public class EstoqueImportador implements ImportadorDeTabela {

    private static final int QTD_COLUNAS_ESTOQUE = 5;

    private final JdbcClient jdbc;
    private final ProdutoBarraService produtoBarraService;
    private final ImportacaoSavepointExecutor savepoints;

    public EstoqueImportador(JdbcClient jdbc, ProdutoBarraService produtoBarraService, ImportacaoSavepointExecutor savepoints) {
        this.jdbc = jdbc;
        this.produtoBarraService = produtoBarraService;
        this.savepoints = savepoints;
    }

    @Override
    public String chave() {
        return "estoque";
    }

    @Override
    public String titulo() {
        return "Estoque inicial (produtos já importados)";
    }

    @Override
    public String descricao() {
        return "Quantidade em estoque por empresa das variações (cor/tamanho) de produtos já importados pela planilha de Produtos. Pede a qual empresa cada coluna de quantidade corresponde.";
    }

    private static final String[] COLUNAS = colunasComQuantidades();

    private static String[] colunasComQuantidades() {
        List<String> colunas = new ArrayList<>(List.of("CODIGO_PRODUTO", "EAN_CODIGO_BARRAS", "NOME_COR", "NOME_TAMANHO"));
        for (int i = 1; i <= QTD_COLUNAS_ESTOQUE; i++) {
            colunas.add("QUANTIDADE_ESTOQUE_" + i);
        }
        return colunas.toArray(new String[0]);
    }

    @Override
    public byte[] modeloPlanilha() {
        String[] linha1 = {"1", "7891234567895", "AZUL", "36", "10", "", "", "", ""};
        String[] linha2 = {"1", "", "AZUL", "37", "5", "", "", "", ""};
        return ImportacaoPlanilha.gerarModelo(COLUNAS, linha1, linha2);
    }

    private record LinhaResolvida(LinhaPlanilha origem, long idProduto, Long idCor, Long idTamanho, String ean) {
    }

    @Override
    @Transactional
    public RelatorioImportacao processar(List<LinhaPlanilha> linhas, JsonNode escolhas, boolean confirmar, Jwt jwt) {
        Map<Integer, Long> mapeamentoEmpresas = lerMapeamentoEmpresas(escolhas);
        if (mapeamentoEmpresas.isEmpty()) {
            throw new IllegalArgumentException(
                    "Informe para qual empresa vai cada coluna QUANTIDADE_ESTOQUE_N antes de importar.");
        }

        recusarCodigosNaFaixaInterna(linhas);

        List<LinhaErro> erros = new ArrayList<>();
        // Cache de produto por chamada (2026-08-10) — variável LOCAL, não campo da classe (o
        // importador é singleton Spring). Mesmo achado real de ContasReceberImportador: planilha
        // de estoque repete o mesmo CODIGO_PRODUTO em muitas linhas, então sem cache é um SELECT
        // por linha à toa.
        Map<String, Optional<Long>> produtoCache = new HashMap<>();

        // 1ª passada: só resolve CODIGO_PRODUTO → idProduto, pra poder pré-buscar em lote o
        // id_grade de cada produto ANTES de resolver cor/tamanho (2026-08-19, correção de bug —
        // ver comentário na 2ª passada abaixo, é o motivo de precisar de duas passadas em vez de
        // uma só como antes).
        record LinhaComProduto(LinhaPlanilha origem, long idProduto) {
        }
        List<LinhaComProduto> comProduto = new ArrayList<>();
        Set<Long> idsProduto = new LinkedHashSet<>();
        for (LinhaPlanilha linha : linhas) {
            try {
                String codigoProduto = exigir(linha, "CODIGO_PRODUTO").trim();
                long idProduto = produtoCache.computeIfAbsent(codigoProduto, this::buscarIdProduto)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Nenhum produto importado com CODIGO_PRODUTO \"" + codigoProduto
                                        + "\" — importe a planilha de Produtos antes desta."));
                comProduto.add(new LinhaComProduto(linha, idProduto));
                idsProduto.add(idProduto);
            } catch (RuntimeException e) {
                erros.add(LinhaErro.de(linha.numeroLinha(), e));
                ImportacaoProgressoContext.avancar();
            }
        }

        // Pré-fetch em lote (2026-08-10) — achado real de performance: um arquivo de 22 mil
        // linhas levava quase 8 minutos, dominado por 1 SELECT de grade + 1 SELECT de variação
        // + 1 SELECT de EAN por GRUPO (não por linha — cada produto/cor/tamanho já é um grupo
        // distinto, então não tinha cache possível ali). Trocado por 2 consultas em lote (grade
        // e variação existente) pros produtos tocados por este arquivo, uma vez só, em vez de
        // milhares de idas e voltas ao banco — ver ImportacaoPlanilha e o resto da importação
        // pra outros achados do mesmo tipo (N+1 query) nesta mesma sessão.
        Map<Long, Long> gradesPorProduto = produtoBarraService.buscarGradesEmLote(idsProduto);
        Map<String, VariacaoResumo> variacoesExistentes = produtoBarraService.buscarVariacoesEmLote(idsProduto);

        // Agrupa por (produto, cor, tamanho) — mesma variação em mais de uma linha soma estoque.
        Map<String, List<LinhaResolvida>> grupos = new LinkedHashMap<>();
        Map<String, Long> corCache = new HashMap<>();
        Map<String, Long> tamanhoCache = new HashMap<>();

        // 2ª passada: resolve cor/tamanho e monta os grupos. Produto SEM grade real
        // (gradesPorProduto.get(idProduto) == null, convenção de buscarGradesEmLote) força
        // cor/tamanho pra null AQUI, antes de montar a chave do grupo — não só dentro de
        // criarParaImportacaoEmMassa, como era até 2026-08-19. Bug real: sem essa força aqui,
        // duas linhas do MESMO produto sem grade com NOME_COR/NOME_TAMANHO textualmente
        // diferentes (ex.: uma em branco, outra "ÚNICO" — comum em planilha migrada de sistema
        // legado) viravam DOIS grupos diferentes; ambos colapsam pra variação (id_cor=1,
        // id_tamanho=1) dentro de criarParaImportacaoEmMassa, então o 2º grupo tentava criar uma
        // variação que o 1º já tinha criado — "duplicate key value violates unique constraint
        // produto_barra_variacao_uk". Produto COM grade real continua exigindo cor/tamanho da
        // planilha, sem mudança de comportamento.
        for (LinhaComProduto lp : comProduto) {
            try {
                Long idGrade = gradesPorProduto.get(lp.idProduto());
                Long idCor;
                Long idTamanho;
                if (idGrade == null) {
                    idCor = null;
                    idTamanho = null;
                } else {
                    // SAVEPOINT só quando não está em cache (2026-08-10) — idCorOuCriar/
                    // idTamanhoOuCriar podem INSERT (mesmo bug/fix de ProdutoImportador, ver
                    // ImportacaoSavepointExecutor); num acerto de cache não há nada pra isolar,
                    // então não vale abrir savepoint à toa.
                    String nomeCor = lp.origem().valor("NOME_COR");
                    idCor = corCache.containsKey(nomeCor)
                            ? corCache.get(nomeCor)
                            : registrarNoCache(corCache, nomeCor, () -> idCorOuCriar(nomeCor));
                    String nomeTamanho = lp.origem().valor("NOME_TAMANHO");
                    idTamanho = tamanhoCache.containsKey(nomeTamanho)
                            ? tamanhoCache.get(nomeTamanho)
                            : registrarNoCache(tamanhoCache, nomeTamanho, () -> idTamanhoOuCriar(nomeTamanho));
                }
                String chaveGrupo = lp.idProduto() + "|" + (idCor == null ? "" : idCor) + "|" + (idTamanho == null ? "" : idTamanho);
                grupos.computeIfAbsent(chaveGrupo, k -> new ArrayList<>())
                        .add(new LinhaResolvida(lp.origem(), lp.idProduto(), idCor, idTamanho, lp.origem().valor("EAN_CODIGO_BARRAS")));
            } catch (RuntimeException e) {
                erros.add(LinhaErro.de(lp.origem().numeroLinha(), e));
            } finally {
                ImportacaoProgressoContext.avancar();
            }
        }

        List<String> avisos = new ArrayList<>();
        int importadas = 0;
        // Um produto_movimento_mestre (AJUSTE) por empresa, reaproveitado por todo o arquivo —
        // mesmo padrão do restante da importação.
        Map<Long, Long> movimentoPorEmpresa = new LinkedHashMap<>();

        for (List<LinhaResolvida> grupo : grupos.values()) {
            try {
                importadas += savepoints.executar(
                        () -> processarGrupo(grupo, mapeamentoEmpresas, movimentoPorEmpresa, avisos, gradesPorProduto, variacoesExistentes));
            } catch (RuntimeException e) {
                for (LinhaResolvida r : grupo) {
                    erros.add(LinhaErro.de(r.origem().numeroLinha(), e));
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

    private int processarGrupo(List<LinhaResolvida> grupo, Map<Integer, Long> mapeamentoEmpresas,
                                Map<Long, Long> movimentoPorEmpresa, List<String> avisos,
                                Map<Long, Long> gradesPorProduto, Map<String, VariacaoResumo> variacoesExistentes) {
        LinhaResolvida primeira = grupo.get(0);
        String chaveGrupo = primeira.idProduto() + "|" + (primeira.idCor() == null ? "" : primeira.idCor())
                + "|" + (primeira.idTamanho() == null ? "" : primeira.idTamanho());
        VariacaoResumo variacao = variacoesExistentes.get(chaveGrupo);
        if (variacao == null) {
            // Não valida que o tamanho pertence à grade (2026-08-10): planilha migrada pode
            // trazer um NOME_TAMANHO fora da grade do produto (ex. "UN1" numa grade que só tem
            // "UN") sem que isso seja erro de verdade — pedido do dono do produto. Emissão de
            // Etiqueta (cadastro manual) continua validando, via ProdutoBarraService.obterOuCriar.
            variacao = produtoBarraService.criarParaImportacaoEmMassa(
                    primeira.idProduto(), gradesPorProduto.get(primeira.idProduto()), primeira.idCor(), primeira.idTamanho());
            variacoesExistentes.put(chaveGrupo, variacao);
        }
        atualizarEanSeNecessario(variacao, grupo, avisos);

        for (Map.Entry<Integer, Long> mapeamento : mapeamentoEmpresas.entrySet()) {
            String coluna = "QUANTIDADE_ESTOQUE_" + mapeamento.getKey();
            BigDecimal total = BigDecimal.ZERO;
            for (LinhaResolvida r : grupo) {
                BigDecimal v = ImportacaoPlanilha.decimal(coluna, r.origem().valor(coluna));
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
                        .params(idMovimento, mapeamento.getValue(), variacao.idVariacao(), total, primeira.idProduto())
                        .update();
            }
        }
        return grupo.size();
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

    private void atualizarEanSeNecessario(VariacaoResumo variacao, List<LinhaResolvida> grupo, List<String> avisos) {
        Set<String> eansDistintos = new LinkedHashSet<>();
        for (LinhaResolvida r : grupo) {
            if (r.ean() != null && !r.ean().isBlank()) {
                eansDistintos.add(r.ean().trim());
            }
        }
        if (eansDistintos.isEmpty()) {
            return;
        }
        if (eansDistintos.size() > 1) {
            avisos.add("Variação \"" + variacao.sku() + "\" tem EANs diferentes no arquivo (" + eansDistintos
                    + ") — usado o primeiro, os demais foram ignorados.");
        }
        // Já tem EAN gravado (ex.: reimportação) — não sobrescreve. Antes (até 2026-08-10) isso
        // era 1 SELECT por grupo; agora `variacao.ean()` já vem do pré-fetch em lote (ou é
        // sabidamente null pra uma variação recém-criada nesta mesma chamada) — 0 SELECT extra.
        if (variacao.ean() != null) {
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

    /** Executa {@code acao} sob SAVEPOINT (pode INSERT), guarda o resultado no cache sob
     *  {@code chave} e devolve — usado só quando a chave ainda NÃO está no cache (2026-08-10);
     *  ver comentário na chamada em {@link #processar}. */
    private Long registrarNoCache(Map<String, Long> cache, String chave, Supplier<Long> acao) {
        Long valor = savepoints.executar(acao::get);
        cache.put(chave, valor);
        return valor;
    }

    /** Quantas linhas o erro cita antes de resumir — a lista serve para achar o problema na
     *  planilha, não para reproduzi-la inteira numa mensagem. */
    private static final int MAX_LINHAS_CITADAS = 10;

    /**
     * Recusa a planilha INTEIRA quando algum {@code EAN_CODIGO_BARRAS} começa por um prefixo
     * reservado ao código interno do Nainer.
     *
     * <p><b>Por que barrar em vez de ignorar o código.</b> O lojista que migra de outro sistema
     * traz os códigos <b>já impressos nas etiquetas</b> da mercadoria. Se um deles cair na nossa
     * faixa, ele colide com um SKU que {@code gerar_ean13_interno()} <b>ainda vai emitir</b> — o
     * sequencial cresce, então um código que hoje não conflita passa a conflitar no dia em que o
     * contador alcançar aquele número. Importar e avisar depois não resolveria: o estrago
     * apareceria meses adiante, numa bipada que traz o produto errado no caixa.
     *
     * <p><b>Por que a planilha toda, e não a linha.</b> Decisão do dono do produto (2026-08-20):
     * importar parcialmente deixaria o lojista com metade do estoque dentro e metade fora, sem
     * saber qual metade — "para não gerar mal-entendidos". Ele corrige o arquivo e reimporta.
     * Sai barato porque a importação é uma transação só, e a tela tem o passo <b>Validar</b>
     * antes do Importar: o erro aparece lá, antes de qualquer gravação.
     *
     * <p>⚠️ O prefixo <b>não é literal aqui</b>: vem de {@code prefixos_ean_reservados()} (V050),
     * a mesma linha que {@code gerar_ean13_interno()} lê para montar o SKU. Se um dia o prefixo
     * mudar, esta validação acompanha sozinha — uma segunda cópia do {@code '9'} neste arquivo
     * seria a forma garantida de as duas regras divergirem em silêncio.
     */
    private void recusarCodigosNaFaixaInterna(List<LinhaPlanilha> linhas) {
        // ⚠️ `query(String[].class)` NÃO funciona para array do Postgres — o driver devolve
        // `PgArray` e o Spring não converte ("Value [{9}] is of type PgArray"). Tem de passar pelo
        // ResultSet. Descoberto pelo teste antes de virar bug em produção.
        String[] reservados = jdbc.sql("SELECT prefixos_ean_reservados() AS prefixos")
                .query((rs, n) -> (String[]) rs.getArray("prefixos").getArray())
                .optional().orElse(new String[0]);
        if (reservados.length == 0) {
            return;
        }

        List<String> ofensoras = new ArrayList<>();
        for (LinhaPlanilha linha : linhas) {
            String codigo = linha.valor("EAN_CODIGO_BARRAS");
            if (codigo == null || codigo.isBlank()) {
                continue;
            }
            String limpo = codigo.trim();
            for (String prefixo : reservados) {
                if (limpo.startsWith(prefixo)) {
                    ofensoras.add("linha " + linha.numeroLinha() + " (" + limpo + ")");
                    break;
                }
            }
        }
        if (ofensoras.isEmpty()) {
            return;
        }

        String citadas = String.join(", ", ofensoras.subList(0, Math.min(ofensoras.size(), MAX_LINHAS_CITADAS)));
        String resto = ofensoras.size() > MAX_LINHAS_CITADAS
                ? " e mais " + (ofensoras.size() - MAX_LINHAS_CITADAS) : "";
        throw new IllegalArgumentException(
                ("Nada foi importado. %d %s com EAN_CODIGO_BARRAS começando por %s — essa faixa é "
                        + "reservada ao código de barras que o próprio Nainer gera, e usá-la faria o "
                        + "código do sistema antigo colidir com um código nosso mais adiante. Remova ou "
                        + "corrija %s e importe de novo: %s%s.")
                        .formatted(ofensoras.size(),
                                ofensoras.size() == 1 ? "linha está" : "linhas estão",
                                String.join("/", reservados),
                                ofensoras.size() == 1 ? "essa linha" : "essas linhas",
                                citadas, resto));
    }

    private Optional<Long> buscarIdProduto(String codigoImportacao) {
        return jdbc.sql("""
                        SELECT id_produto FROM produto
                        WHERE id_tenant = plataforma.tenant_atual() AND codigo_importacao = ?
                        """)
                .param(codigoImportacao)
                .query(Long.class).optional();
    }

    private Long idCorOuCriar(String descricao) {
        return idOuNulo("cfg_cor", "id_cor", descricao);
    }

    private Long idTamanhoOuCriar(String descricao) {
        return idOuNulo("cfg_tamanho", "id_tamanho", descricao);
    }

    /** Acha (ou cria) a linha de {@code cfg_cor}/{@code cfg_tamanho} com essa descrição — mesmo
     *  princípio de find-or-create usado no resto da importação (fornecedor/categoria por nome).
     *  {@code tabela}/{@code coluna} são sempre literais internos (nunca vêm do arquivo
     *  importado), então a concatenação dinâmica não é risco de injeção. {@code id_cor}/
     *  {@code id_tamanho} não são mais IDENTITY (V017, 2026-08-13): calculados por tenant, mesmo
     *  padrão de {@code CorService}/{@code TamanhoService}. */
    private Long idOuNulo(String tabela, String coluna, String descricao) {
        if (descricao == null || descricao.isBlank()) {
            return null;
        }
        String d = descricao.trim().toUpperCase(Locale.ROOT);
        // id <> 1 exclui a cor/tamanho PADRÃO (sentinela reservado) — mesma colisão corrigida em
        // ProdutoImportador.idTamanhoOuCriar: sem isso, um produto com cor/tamanho "PADRÃO" ou
        // "UN" digitado de verdade acharia o sentinela em vez de criar seu próprio registro, e
        // depois de o Produto import já ter criado um "UN" real (id<>1), esta busca sem exclusão
        // passaria a achar DOIS registros com a mesma descrição e quebrar (.optional() exige 0/1).
        Optional<Long> existente = jdbc.sql(
                        "SELECT " + coluna + " FROM " + tabela + " WHERE id_tenant = plataforma.tenant_atual() AND "
                                + coluna + " <> 1 AND descricao = ?")
                .param(d).query(Long.class).optional();
        if (existente.isPresent()) {
            return existente.get();
        }
        return jdbc.sql(
                        "INSERT INTO " + tabela + " (id_tenant, " + coluna + ", descricao) VALUES (plataforma.tenant_atual(), "
                                + "COALESCE((SELECT MAX(" + coluna + ") FROM " + tabela + " WHERE id_tenant = plataforma.tenant_atual()), 0) + 1, "
                                + "?) RETURNING " + coluna)
                .param(d).query(Long.class).single();
    }

    /** {@code escolhas.mapeamentoEmpresas}: {@code {"QUANTIDADE_ESTOQUE_1": 10, "QUANTIDADE_ESTOQUE_3": 12}}
     *  — chave = nome da coluna, valor = id da empresa. */
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

    private static String exigir(LinhaPlanilha l, String coluna) {
        String v = l.valor(coluna);
        if (v == null) {
            throw new IllegalArgumentException(coluna + " é obrigatório.");
        }
        return v;
    }
}
