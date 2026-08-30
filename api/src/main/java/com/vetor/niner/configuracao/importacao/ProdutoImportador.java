package com.vetor.niner.configuracao.importacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.vetor.niner.comum.tempo.FusoDaLoja;
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

    /** Nomes dos 3 perfis fiscais padrão (docs/telas/fiscal-perfil.md) que
     *  {@code TRIBUTACAO} resolve — os dois primeiros semeados no signup com regras completas
     *  (CRT 1/2/4); o terceiro, semeado **sem regra nenhuma** de propósito: é o sentinela de
     *  "tributação não informada" — qualquer produto apontando pra ele faz a Conformidade Fiscal
     *  acusar "perfil sem regra para o CRT" (mecanismo que já existia, nenhuma mudança lá) e o
     *  motor recusa a emissão (F11) em vez de chutar um CFOP/CSOSN. */
    private static final String NOME_PERFIL_NORMAL = "REVENDA TRIBUTADA NORMAL";
    private static final String NOME_PERFIL_SUBSTITUICAO = "REVENDA COM SUBSTITUIÇÃO TRIBUTÁRIA (ST)";
    private static final String NOME_PERFIL_NAO_INFORMADO = "NÃO INFORMADO";
    private static final int LIMITE_LINHAS_NO_AVISO = 20;

    private static String[] colunasComTamanhos() {
        List<String> colunas = new ArrayList<>(List.of(
                "CODIGO_PRODUTO", "MARCA", "REFERENCIA", "DESCRICAO", "PRECO_CUSTO", "PERCENTUAL_VENDA", "PRECO_VENDA",
                "DATA_INICIO_OFERTA", "DATA_FINAL_OFERTA", "PRECO_OFERTA", "CODIGO_NCM", "TRIBUTACAO",
                "PESO_BRUTO", "PESO_LIQUIDO"));
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
    private final FusoDaLoja fusoDaLoja;

    public ProdutoImportador(JdbcClient jdbc, ProdutoService produtoService,
                              ConfiguracaoGeralService configuracaoGeralService, GradeService gradeService,
                              ImportacaoSavepointExecutor savepoints, FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.produtoService = produtoService;
        this.configuracaoGeralService = configuracaoGeralService;
        this.gradeService = gradeService;
        this.savepoints = savepoints;
        this.fusoDaLoja = fusoDaLoja;
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
        return "Produtos e a grade de tamanhos de cada um (TAMANHO_1..20). Sem variação/EAN/estoque nesta importação — isso fica para a Entrada de Produtos. TRIBUTACAO define o perfil fiscal (NORMAL, SUBSTITUICAO ou vazio=Não Informado).";
    }

    @Override
    public byte[] modeloPlanilha() {
        List<String> linha = new ArrayList<>(List.of(
                "1", "BEIRA MAR", "SAND-001", "SANDALIA RASTEIRA VERAO", "35,00", "100", "70,00",
                "", "", "", "6402.99.90", "NORMAL", "0,350", "0,300", "36", "37", "38", "39", "40"));
        while (linha.size() < COLUNAS.length) {
            linha.add("");
        }
        return ImportacaoPlanilha.gerarModelo(COLUNAS, linha.toArray(new String[0]));
    }

    @Override
    @Transactional
    public RelatorioImportacao processar(List<LinhaPlanilha> linhas, JsonNode escolhas, boolean confirmar, Jwt jwt) {
        // Uma consulta ao banco por importação, não por linha (a planilha pode ter milhares).
        ZoneId fuso = fusoDaLoja.daSessao(jwt);
        boolean usaCorGrade = configuracaoGeralService.usaCorGrade();

        List<LinhaErro> erros = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        int importadas = 0, ignoradas = 0;
        // Cache de tamanho por chamada (2026-08-10) — variável LOCAL, não campo da classe (o
        // importador é singleton Spring). O mesmo TAMANHO (ex. "36", "37"...) se repete em
        // praticamente todo produto do arquivo — sem cache seria um SELECT/INSERT em
        // cfg_tamanho por TAMANHO_N por linha, achado real de performance em ContasReceberImportador/
        // EstoqueImportador (mesmo padrão).
        Map<String, Long> tamanhoCache = new HashMap<>();

        // Resolvidos UMA VEZ por arquivo (não por linha) — os 3 nomes são fixos, e cfg_perfil_fiscal
        // já é indexado por (id_tenant, nome). Se um deles não existir (tenant editou/apagou o
        // padrão semeado no signup), o `null` propaga silenciosamente pro produto (mesmo
        // comportamento de "sem perfil fiscal" de antes desta feature) e um único aviso avisa —
        // não trava o arquivo inteiro por causa de um cadastro de referência ausente.
        Long idPerfilNormal = idPerfilFiscalPorNome(NOME_PERFIL_NORMAL);
        Long idPerfilSubstituicao = idPerfilFiscalPorNome(NOME_PERFIL_SUBSTITUICAO);
        Long idPerfilNaoInformado = idPerfilFiscalPorNome(NOME_PERFIL_NAO_INFORMADO);
        avisarSePerfilPadraoAusente(avisos, NOME_PERFIL_NORMAL, idPerfilNormal);
        avisarSePerfilPadraoAusente(avisos, NOME_PERFIL_SUBSTITUICAO, idPerfilSubstituicao);
        avisarSePerfilPadraoAusente(avisos, NOME_PERFIL_NAO_INFORMADO, idPerfilNaoInformado);

        List<Integer> linhasSemTributacao = new ArrayList<>();

        for (LinhaPlanilha linha : linhas) {
            try {
                // SAVEPOINT por linha (2026-08-10): sem isto, uma exceção de banco (não só de
                // validação Java) numa linha deixa a transação do arquivo inteiro "abortada" e
                // toda linha seguinte falha em cascata com "current transaction is aborted"
                // (Postgres 25P02) — achado real ao validar uma planilha de Produtos.
                if (savepoints.executar(() -> processarLinha(linha, fuso, usaCorGrade, tamanhoCache,
                        idPerfilNormal, idPerfilSubstituicao, idPerfilNaoInformado, linhasSemTributacao))) {
                    importadas++;
                } else {
                    ignoradas++;
                }
            } catch (RuntimeException e) {
                // ⛔ MESMO defeito que o `EstoqueImportador` teve: `idTamanhoOuCriar` faz
                // `INSERT INTO cfg_tamanho` e cacheia o id — **dentro** do savepoint da linha. Se a
                // linha falhar DEPOIS disso (preço de venda menor que o custo, NCM inexistente,
                // código duplicado…), o savepoint desfaz o INSERT e o `tamanhoCache` fica com um id
                // que não existe mais. Da linha seguinte em diante, toda linha com aquele tamanho
                // violava a FK de `cfg_grade` e rolava de volta — o relatório acusava erro em quase
                // toda a planilha e ESCONDIA que só uma linha estava ruim, que é exatamente o
                // efeito-cascata que o savepoint por linha existe para eliminar.
                //
                // ⚠️ Limpar o cache inteiro (e não só a chave criada) é de propósito: aqui uma linha
                // pode criar VÁRIOS tamanhos (a grade vem em `TAMANHO_1..N`) e não há como saber
                // quais sem mudar quatro assinaturas. O custo é só um SELECT a mais por tamanho
                // depois de uma linha com erro — `idTamanhoOuCriar` consulta antes de inserir.
                tamanhoCache.clear();
                erros.add(LinhaErro.de(linha.numeroLinha(), e));
            } finally {
                ImportacaoProgressoContext.avancar();
            }
        }

        if (!linhasSemTributacao.isEmpty()) {
            avisos.add(mensagemTributacaoAusente(linhasSemTributacao));
        }

        RelatorioImportacao relatorio =
                RelatorioImportacao.concluir(confirmar, linhas.size(), importadas, ignoradas, erros, avisos);
        if (!relatorio.confirmado()) {
            throw new SimulacaoConcluidaException(relatorio);
        }
        return relatorio;
    }

    private static void avisarSePerfilPadraoAusente(List<String> avisos, String nome, Long id) {
        if (id == null) {
            avisos.add("Perfil fiscal padrão \"" + nome + "\" não encontrado neste tenant — produtos que "
                    + "deveriam recebê-lo automaticamente pela coluna TRIBUTACAO ficarão sem perfil fiscal. "
                    + "Cadastre um perfil com esse nome exato em Perfis Fiscais, ou reimporte depois de corrigir.");
        }
    }

    private static String mensagemTributacaoAusente(List<Integer> linhasSemTributacao) {
        int total = linhasSemTributacao.size();
        String amostra = linhasSemTributacao.stream().limit(LIMITE_LINHAS_NO_AVISO)
                .map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
        String sufixo = total > LIMITE_LINHAS_NO_AVISO ? " e mais " + (total - LIMITE_LINHAS_NO_AVISO) + " linha(s)" : "";
        return total + " produto(s) sem a coluna TRIBUTACAO preenchida (linha" + (total > 1 ? "s " : " ") + amostra
                + sufixo + ") — receberam o perfil fiscal \"" + NOME_PERFIL_NAO_INFORMADO + "\" e não poderão "
                + "emitir documento fiscal até você definir a tributação correta (NORMAL ou "
                + "SUBSTITUICAO).";
    }

    /** {@code true} se um produto novo foi criado; {@code false} se já existia (ignorada — mesma
     *  convenção de dedup do resto da importação: reaproveita, nunca duplica). */
    private boolean processarLinha(LinhaPlanilha linha, ZoneId fuso, boolean usaCorGrade, Map<String, Long> tamanhoCache,
                                   Long idPerfilNormal, Long idPerfilSubstituicao, Long idPerfilNaoInformado,
                                   List<Integer> linhasSemTributacao) {
        String descricao = exigir(linha, "DESCRICAO");
        String marca = linha.valor("MARCA");
        String referencia = linha.valor("REFERENCIA");
        String codigoProduto = linha.valor("CODIGO_PRODUTO");

        if (produtoJaExiste(descricao, marca, referencia, codigoProduto)) {
            return false;
        }

        Long idGrade = usaCorGrade ? resolverGrade(linha, tamanhoCache) : null;
        Long idPerfilFiscal = resolverPerfilFiscal(linha, idPerfilNormal, idPerfilSubstituicao, idPerfilNaoInformado,
                linhasSemTributacao);

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
                inicioDoDiaOuNulo(ImportacaoPlanilha.data("DATA_INICIO_OFERTA", linha.valor("DATA_INICIO_OFERTA")), fuso),
                inicioDoDiaOuNulo(ImportacaoPlanilha.data("DATA_FINAL_OFERTA", linha.valor("DATA_FINAL_OFERTA")), fuso),
                precoOferta,
                ncmExistenteOuNulo(linha.valor("CODIGO_NCM")),
                ImportacaoPlanilha.decimal("PESO_BRUTO", linha.valor("PESO_BRUTO")),
                ImportacaoPlanilha.decimal("PESO_LIQUIDO", linha.valor("PESO_LIQUIDO")),
                idGrade, true, List.of(), idPerfilFiscal,
                // A planilha de importação é de MERCADORIA — serviço não tem estoque inicial nem
                // código de barras, que é o que esta carga existe para trazer. Quando o bloco S5
                // criar a planilha de serviços, ela terá colunas próprias (LC 116, ISS), não uma
                // coluna a mais aqui. Nulo resolve para MERCADORIA em tipoItemValidado().
                null, null, null);
        long idProduto = produtoService.criar(req).idProduto();
        gravarCodigoImportacaoSeInformado(idProduto, codigoProduto);
        return true;
    }

    /**
     * {@code TRIBUTACAO} (2026-08-19, ajustado no mesmo dia: a planilha real do usuário traz texto,
     * não código numérico): {@code NORMAL} (ou {@code 1}) = perfil "Revenda Tributada Normal",
     * {@code SUBSTITUICAO}/{@code SUBSTITUIÇÃO}/{@code ST} (ou {@code 2}) = perfil "Revenda com
     * Substituição Tributária (ST)", vazio = perfil "Não Informado" (sentinela sem regra — a
     * Conformidade Fiscal aponta e o motor recusa emitir, em vez de o produto ficar "quieto" sem
     * perfil nenhum). Comparação sem acento/maiúscula-minúscula. Qualquer outro valor é erro de
     * planilha, não tributação desconhecida.
     */
    private Long resolverPerfilFiscal(LinhaPlanilha linha, Long idPerfilNormal, Long idPerfilSubstituicao,
                                      Long idPerfilNaoInformado, List<Integer> linhasSemTributacao) {
        String valor = linha.valor("TRIBUTACAO");
        if (valor == null || valor.isBlank()) {
            linhasSemTributacao.add(linha.numeroLinha());
            return idPerfilNaoInformado;
        }
        String normalizado = valor.trim().toUpperCase(Locale.ROOT)
                .replace("Ã", "A").replace("Ç", "C");
        return switch (normalizado) {
            case "1", "NORMAL" -> idPerfilNormal;
            case "2", "SUBSTITUICAO", "ST" -> idPerfilSubstituicao;
            default -> throw new IllegalArgumentException(
                    "TRIBUTACAO deve ser NORMAL, SUBSTITUICAO ou vazio — veio \"" + valor + "\".");
        };
    }

    /** Perfis fiscais são identificados por nome, único por tenant (`cfg_perfil_fiscal_nome_uk`) —
     *  não há coluna de "código fixo" pra referenciar de fora, então a busca é pelo nome exato dos
     *  3 perfis padrão semeados no signup (docs/telas/fiscal-perfil.md). */
    private Long idPerfilFiscalPorNome(String nome) {
        return jdbc.sql("""
                        SELECT id_perfil_fiscal FROM cfg_perfil_fiscal
                        WHERE id_tenant = plataforma.tenant_atual() AND nome = ?
                        """)
                .param(nome).query(Long.class).optional().orElse(null);
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
        // id_tamanho <> 1 exclui o tamanho PADRÃO (sentinela reservado, "UN") — um produto real
        // importado com um único tamanho chamado "UN" precisa de um id PRÓPRIO, não do sentinela
        // (GradeService.buscar recusa devolver a grade que contém id=1, ver comentário lá).
        Optional<Long> existente = jdbc.sql("""
                        SELECT id_tamanho FROM cfg_tamanho
                        WHERE id_tenant = plataforma.tenant_atual() AND id_tamanho <> 1 AND descricao = ?
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

    /**
     * Meia-noite da data digitada na planilha, <b>no fuso da loja</b>.
     *
     * <p>⚠️ Era {@code ZoneId.systemDefault()} até 2026-08-29 (guarda de fuso ampliado numa
     * auditoria). O padrão da JVM é o {@code TZ} do container, que <b>só existe em produção</b> —
     * em dev e na suíte é UTC, então o defeito não reproduzia na máquina de quem escreveu. Numa
     * oferta importada, "vale de 01/09 a 30/09" virava vigência começando <b>31/08 às 21h</b>: o
     * preço promocional entrava um dia antes e saía um dia antes do que a loja combinou.
     */
    private static OffsetDateTime inicioDoDiaOuNulo(LocalDate data, ZoneId fusoDaLoja) {
        return data == null ? null : data.atStartOfDay(fusoDaLoja).toOffsetDateTime();
    }
}
