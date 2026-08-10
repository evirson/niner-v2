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
import java.util.stream.Collectors;

/**
 * Importação de estoque inicial (docs/telas/importacao-dados.md, "5. estoque") — tabela irmã da
 * de produto (2026-08-09), SEMPRE importada depois: acha o produto pelo {@code CODIGO_PRODUTO}
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

        List<LinhaErro> erros = new ArrayList<>();
        // Agrupa por (produto, cor, tamanho) — mesma variação em mais de uma linha soma estoque.
        Map<String, List<LinhaResolvida>> grupos = new LinkedHashMap<>();
        // Cache de produto/cor/tamanho por chamada (2026-08-11) — variável LOCAL, não campo da
        // classe (o importador é singleton Spring). Mesmo achado real de ContasReceberImportador:
        // planilha de estoque repete o mesmo CODIGO_PRODUTO/cor/tamanho em muitas linhas (cada
        // combinação vira uma variação), então sem cache é um SELECT/INSERT por linha à toa.
        Map<String, Optional<Long>> produtoCache = new HashMap<>();
        Map<String, Long> corCache = new HashMap<>();
        Map<String, Long> tamanhoCache = new HashMap<>();

        for (LinhaPlanilha linha : linhas) {
            try {
                String codigoProduto = exigir(linha, "CODIGO_PRODUTO").trim();
                long idProduto = produtoCache.computeIfAbsent(codigoProduto, this::buscarIdProduto)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Nenhum produto importado com CODIGO_PRODUTO \"" + codigoProduto
                                        + "\" — importe a planilha de Produtos antes desta."));
                // SAVEPOINT só quando não está em cache (2026-08-11) — idCorOuCriar/idTamanhoOuCriar
                // podem INSERT (mesmo bug/fix de ProdutoImportador, ver ImportacaoSavepointExecutor);
                // num acerto de cache não há nada pra isolar, então não vale abrir savepoint à toa.
                Long idCor = corCache.containsKey(linha.valor("NOME_COR"))
                        ? corCache.get(linha.valor("NOME_COR"))
                        : registrarNoCache(corCache, linha.valor("NOME_COR"), () -> idCorOuCriar(linha.valor("NOME_COR")));
                Long idTamanho = tamanhoCache.containsKey(linha.valor("NOME_TAMANHO"))
                        ? tamanhoCache.get(linha.valor("NOME_TAMANHO"))
                        : registrarNoCache(tamanhoCache, linha.valor("NOME_TAMANHO"), () -> idTamanhoOuCriar(linha.valor("NOME_TAMANHO")));
                String chaveGrupo = idProduto + "|" + (idCor == null ? "" : idCor) + "|" + (idTamanho == null ? "" : idTamanho);
                grupos.computeIfAbsent(chaveGrupo, k -> new ArrayList<>())
                        .add(new LinhaResolvida(linha, idProduto, idCor, idTamanho, linha.valor("EAN_CODIGO_BARRAS")));
            } catch (RuntimeException e) {
                erros.add(LinhaErro.de(linha.numeroLinha(), e));
            } finally {
                ImportacaoProgressoContext.avancar();
            }
        }

        List<String> avisos = new ArrayList<>();
        int importadas = 0;
        // Um produto_movimento_mestre (AJUSTE) por empresa, reaproveitado por todo o arquivo —
        // mesmo padrão do restante da importação.
        Map<Long, Long> movimentoPorEmpresa = new LinkedHashMap<>();

        // Pré-fetch em lote (2026-08-11) — achado real de performance: um arquivo de 22 mil
        // linhas levava quase 8 minutos, dominado por 1 SELECT de grade + 1 SELECT de variação
        // + 1 SELECT de EAN por GRUPO (não por linha — cada produto/cor/tamanho já é um grupo
        // distinto, então não tinha cache possível ali). Trocado por 2 consultas em lote (grade
        // e variação existente) pros produtos tocados por este arquivo, uma vez só, em vez de
        // milhares de idas e voltas ao banco — ver ImportacaoPlanilha e o resto da importação
        // pra outros achados do mesmo tipo (N+1 query) nesta mesma sessão.
        Set<Long> idsProduto = grupos.values().stream().map(g -> g.get(0).idProduto()).collect(Collectors.toSet());
        Map<Long, Long> gradesPorProduto = produtoBarraService.buscarGradesEmLote(idsProduto);
        Map<String, VariacaoResumo> variacoesExistentes = produtoBarraService.buscarVariacoesEmLote(idsProduto);

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
            // Não valida que o tamanho pertence à grade (2026-08-11): planilha migrada pode
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
        // Já tem EAN gravado (ex.: reimportação) — não sobrescreve. Antes (até 2026-08-11) isso
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
     *  {@code chave} e devolve — usado só quando a chave ainda NÃO está no cache (2026-08-11);
     *  ver comentário na chamada em {@link #processar}. */
    private Long registrarNoCache(Map<String, Long> cache, String chave, Supplier<Long> acao) {
        Long valor = savepoints.executar(acao::get);
        cache.put(chave, valor);
        return valor;
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
