package com.vetor.niner.catalogo;

import com.vetor.niner.comum.tempo.FusoDaUf;
import com.vetor.niner.catalogo.ProdutoBarraDtos.CriarVariacaoRequest;
import com.vetor.niner.catalogo.ProdutoBarraDtos.ProdutoBarraResponse;
import com.vetor.niner.catalogo.ProdutoDtos.CategoriaSelecionada;
import com.vetor.niner.catalogo.ProdutoDtos.ExclusaoProdutoResponse;
import com.vetor.niner.catalogo.ProdutoDtos.PaginaProdutos;
import com.vetor.niner.catalogo.ProdutoDtos.ProdutoRequest;
import com.vetor.niner.catalogo.ProdutoDtos.ProdutoResponse;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaDtos.ConfiguracaoCampoResponse;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaService;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de produtos (docs/telas/produto.md). Tabela {@code produto} sob RLS de tenant
 * (V017/V024) — toda leitura já é restrita ao tenant do contexto atual (P8); o INSERT usa
 * {@code plataforma.tenant_atual()} explicitamente porque a política WITH CHECK exige o
 * valor (RLS não o preenche sozinho). Categorias (N:N com ordenação, {@code produto_categoria})
 * são substituídas por inteiro a cada criação/atualização — apaga tudo e reinsere na ordem
 * enviada, o {@code indice} vem da posição na lista (o cliente não escolhe números).
 */
@Service
public class ProdutoService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;
    private static final String CHAVE_TELA_FORM = "catalogo.produto.form";

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "descricao", "p.descricao",
            "marca", "p.marca",
            "referencia", "p.referencia",
            "precoCusto", "p.preco_custo",
            "precoVenda", "p.preco_venda",
            "status", "p.ativo");

    private static final Map<String, String> ROTULOS_CAMPO = Map.of(
            "marca", "Marca", "referencia", "Referência", "codigoNcm", "NCM - Nomenclatura Comum do Mercosul",
            "pesoBruto", "Peso Bruto", "pesoLiquido", "Peso Líquido",
            "dataInicioOferta", "Início da oferta", "dataFinalOferta", "Final da oferta",
            "precoOferta", "Preço de oferta");

    private final JdbcClient jdbc;
    private final ConfiguracaoTelaService configuracaoTelaService;
    private final ConfiguracaoGeralService configuracaoGeralService;
    private final ProdutoImagemService produtoImagemService;
    private final ProdutoBarraService produtoBarraService;

    public ProdutoService(JdbcClient jdbc, ConfiguracaoTelaService configuracaoTelaService,
                          ConfiguracaoGeralService configuracaoGeralService,
                          ProdutoImagemService produtoImagemService,
                          ProdutoBarraService produtoBarraService) {
        this.jdbc = jdbc;
        this.configuracaoTelaService = configuracaoTelaService;
        this.configuracaoGeralService = configuracaoGeralService;
        this.produtoImagemService = produtoImagemService;
        this.produtoBarraService = produtoBarraService;
    }

    @Transactional(readOnly = true)
    public PaginaProdutos listar(String descricao, String marca, Long idCategoria, String status,
                                  Integer pagina, Integer limite, String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao = ordenarPor == null ? "p.descricao" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "p.descricao");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        // id_tenant explícito (defesa em profundidade) — sem isso, uma query com poucos/nenhum
        // parâmetro amarrado dependendo só de RLS pode devolver linha de outro tenant sob certas
        // condições de cache de plano do driver JDBC/Postgres (achado real de teste, 2026-08-08,
        // reproduzido em CorService/CategoriaProdutoService).
        StringBuilder filtro = new StringBuilder(" WHERE p.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();

        if (descricao != null && !descricao.isBlank()) {
            filtro.append(" AND p.descricao ILIKE ?");
            params.add("%" + descricao.trim() + "%");
        }
        if (marca != null && !marca.isBlank()) {
            filtro.append(" AND p.marca ILIKE ?");
            params.add("%" + marca.trim() + "%");
        }
        if (idCategoria != null) {
            filtro.append(" AND EXISTS (SELECT 1 FROM produto_categoria pc WHERE pc.id_tenant = plataforma.tenant_atual()"
                    + " AND pc.id_produto = p.id_produto AND pc.id_categoria = ?)");
            params.add(idCategoria);
        }
        switch (status == null ? "ATIVOS" : status.toUpperCase(Locale.ROOT)) {
            case "INATIVOS" -> filtro.append(" AND p.ativo = false");
            case "TODOS" -> { /* sem filtro de status */ }
            default -> filtro.append(" AND p.ativo = true");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM produto p" + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        // Colunas fixas no whitelist (COLUNAS_ORDENAVEIS) — nunca vem do cliente sem passar por
        // esse mapa, então não há risco de injeção mesmo concatenando direto na SQL.
        String ordenacao = " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                + ", p.id_produto " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<ProdutoResponse> itens = jdbc.sql(SELECT_BASE + filtro + ordenacao)
                .params(paramsPagina)
                .query(this::mapear)
                .list();

        return new PaginaProdutos(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    /** Marcas distintas cadastradas no tenant (não-vazias), pra popular o filtro de Marca do
     *  Relatório de Estoque — RLS já restringe a leitura de {@code produto} ao tenant atual. */
    @Transactional(readOnly = true)
    public List<String> listarMarcas() {
        return jdbc.sql("""
                        SELECT DISTINCT marca FROM produto
                        WHERE id_tenant = plataforma.tenant_atual() AND marca IS NOT NULL AND marca <> ''
                        ORDER BY marca
                        """)
                .query(String.class)
                .list();
    }

    @Transactional(readOnly = true)
    public ProdutoResponse buscar(long id) {
        return jdbc.sql(SELECT_BASE + " WHERE p.id_tenant = plataforma.tenant_atual() AND p.id_produto = ?")
                .param(id)
                .query(this::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Produto não encontrado."));
    }

    @Transactional
    public ProdutoResponse criar(ProdutoRequest req) {
        boolean usaCorGrade = configuracaoGeralService.usaCorGrade();
        validar(req, usaCorGrade);
        List<Object> params = new ArrayList<>();
        adicionarCamposComuns(params, req, usaCorGrade);
        // tipo_item entra só no INSERT: é imutável (V085), então o UPDATE não o toca — e é por isso
        // que ele não está em adicionarCamposComuns, que os dois caminhos compartilham.
        params.add(tipoItemValidado(req));

        try {
            long id = jdbc.sql("""
                            INSERT INTO produto (id_tenant, ativo, marca, referencia, descricao, preco_custo,
                                percentual_venda, preco_venda, data_inicio_oferta, data_final_oferta, preco_oferta,
                                codigo_ncm, peso_bruto, peso_liquido, id_grade, id_perfil_fiscal, tipo_item)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                    CAST(? AS tipo_item))
                            RETURNING id_produto
                            """)
                    .params(params)
                    .query(Long.class).single();
            salvarCategorias(id, req.categorias());
            salvarServico(id, req);
            return buscar(id);
        } catch (DataIntegrityViolationException e) {
            throw erroDeVinculo(e);
        }
    }

    /**
     * Cria o produto <b>e</b> a primeira variação na <b>mesma transação</b> (auditoria 2026-08-21,
     * item 28).
     *
     * <p><b>O problema que resolve.</b> O cadastro rápido do PDV/Entrada fazia dois POSTs
     * independentes: {@code criar} e depois {@code /variacoes}. Quando o segundo falhava — e o caso
     * real é EAN repetido vindo de planilha ou XML de terceiro — sobrava um produto <b>sem
     * variação</b>: sem SKU, sem código de barras, invisível no PDV. E a tela dizia "não foi
     * possível criar o produto", então clicar de novo criava um <b>segundo</b> produto órfão.
     *
     * <p>O front foi mitigado em 2026-08-21 (guarda o id e repete só a variação), mas a mitigação
     * depende de o operador clicar de novo na mesma sessão: fechando a tela, o órfão fica. Aqui a
     * falha da variação desfaz o produto junto, que é o comportamento que o usuário já supunha.
     *
     * <p>⚠️ {@code this.criar(req)} <b>não</b> passa pelo proxy do Spring (auto-invocação), e isso
     * está certo aqui: a transação já foi aberta por <b>este</b> método, e o de dentro apenas a
     * herda. O defeito clássico da auto-invocação acontece quando <b>não há</b> transação no
     * chamador — não é o caso.
     */
    @Transactional
    public ProdutoBarraResponse criarComVariacao(ProdutoRequest req, CriarVariacaoRequest variacao) {
        ProdutoResponse produto = criar(req);
        // Devolve a VARIAÇÃO, não o produto: ela já carrega descrição, marca, referência e preço
        // do produto, e é o que o chamador (PDV, Entrada) precisa para seguir — lançar o item.
        // Devolver o produto obrigaria a uma segunda chamada só para descobrir o SKU recém-gerado.
        return produtoBarraService.obterOuCriar(produto.idProduto(), variacao.idCor(), variacao.idTamanho(),
                true, variacao.ean());
    }

    @Transactional
    public ProdutoResponse atualizar(long id, ProdutoRequest req) {
        boolean usaCorGrade = configuracaoGeralService.usaCorGrade();
        validar(req, usaCorGrade);
        List<Object> params = new ArrayList<>();
        adicionarCamposComuns(params, req, usaCorGrade);
        params.add(id);

        try {
            int linhas = jdbc.sql("""
                            UPDATE produto SET
                                ativo = ?, marca = ?, referencia = ?, descricao = ?, preco_custo = ?,
                                percentual_venda = ?, preco_venda = ?, data_inicio_oferta = ?, data_final_oferta = ?,
                                preco_oferta = ?, codigo_ncm = ?, peso_bruto = ?, peso_liquido = ?,
                                id_grade = ?, id_perfil_fiscal = ?, atualizado_em = now()
                            WHERE id_produto = ? AND id_tenant = plataforma.tenant_atual()
                            """)
                    .params(params)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Produto não encontrado.");
            }
            salvarCategorias(id, req.categorias());
            salvarServico(id, req);
            return buscar(id);
        } catch (DataIntegrityViolationException e) {
            throw erroDeVinculo(e);
        }
    }

    /**
     * Exclui, ou inativa em vez de excluir se houver variação ou imagem vinculada (mesmo
     * princípio do fallback de Cliente/Funcionário/Fornecedor) — nenhuma dessas telas existe
     * ainda, mas as tabelas ({@code produto_barra}/{@code produto_imagem}, V017) já têm FK sem
     * {@code ON DELETE CASCADE} para {@code produto}, então checar antes evita um 500 por
     * violação de FK. Categorias são sempre apagadas junto (relação só existe por causa do
     * produto).
     */
    @Transactional
    public ExclusaoProdutoResponse excluir(long id) {
        boolean temDependente = Boolean.TRUE.equals(
                jdbc.sql("""
                                SELECT EXISTS (SELECT 1 FROM produto_barra
                                               WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?)
                                    OR EXISTS (SELECT 1 FROM produto_imagem
                                               WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?)
                                """)
                        .params(id, id).query(Boolean.class).single());

        if (temDependente) {
            int linhas = jdbc.sql("""
                            UPDATE produto SET ativo = false, atualizado_em = now()
                            WHERE id_produto = ? AND id_tenant = plataforma.tenant_atual()
                            """)
                    .param(id).update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Produto não encontrado.");
            }
            return new ExclusaoProdutoResponse("inativado", "Produto possui variações ou imagens associadas.");
        }

        jdbc.sql("DELETE FROM produto_categoria WHERE id_produto = ? AND id_tenant = plataforma.tenant_atual()")
                .param(id).update();
        // ⚠️ `produto_servico` é filha EXCLUSIVA do produto (extensão 1:1 da V085), como a
        // categoria — e a FK não tem cascade. Sem este DELETE, excluir um serviço recém-cadastrado
        // violava a FK e o handler global respondia 409 "Registro em uso por outro cadastro",
        // falando de um vínculo que o usuário não encontra em tela nenhuma; o produto não era nem
        // excluído nem inativado. Achado de auditoria, 2026-08-29.
        jdbc.sql("DELETE FROM produto_servico WHERE id_produto = ? AND id_tenant = plataforma.tenant_atual()")
                .param(id).update();
        int linhas = jdbc.sql("DELETE FROM produto WHERE id_produto = ? AND id_tenant = plataforma.tenant_atual()")
                .param(id).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Produto não encontrado.");
        }
        return new ExclusaoProdutoResponse("excluido", null);
    }

    /**
     * Apaga todas as categorias do produto e reinsere na ordem recebida — {@code indice} é a
     * posição na lista (0, 1, 2…), não um valor escolhido pelo cliente da API.
     */
    private void salvarCategorias(long idProduto, List<Long> categorias) {
        jdbc.sql("DELETE FROM produto_categoria WHERE id_produto = ? AND id_tenant = plataforma.tenant_atual()")
                .param(idProduto).update();
        if (categorias == null) {
            return;
        }
        int indice = 0;
        for (Long idCategoria : categorias) {
            jdbc.sql("""
                            INSERT INTO produto_categoria (id_tenant, id_produto, id_categoria, indice)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?)
                            """)
                    .params(idProduto, idCategoria, indice)
                    .update();
            indice++;
        }
    }

    /**
     * Validação de servidor (defesa em profundidade, mesmo padrão de {@code ClienteService}):
     * intervalo de oferta e obrigatoriedade configurável por tenant. Categoria duplicada na
     * lista é rejeitada aqui porque viraria uma dupla violação de PK só detectável depois de
     * já ter apagado as categorias antigas.
     */
    private void validar(ProdutoRequest req, boolean usaCorGrade) {
        validarPrecos(req);
        validarOferta(req);
        BigDecimal pesoBruto = req.pesoBruto() == null ? BigDecimal.ZERO : req.pesoBruto();
        BigDecimal pesoLiquido = req.pesoLiquido() == null ? BigDecimal.ZERO : req.pesoLiquido();
        if (pesoLiquido.compareTo(pesoBruto) > 0) {
            throw new IllegalArgumentException("Peso líquido deve ser menor ou igual ao peso bruto.");
        }
        // ⚠️ Serviço não tem grade — nem cor, nem tamanho. Achado testando ao vivo em 2026-08-28:
        // num tenant com `cfg_usa_cor_grade` ligado (o caso da petshop que também vende ração em
        // tamanhos), cadastrar "BANHO E TOSA" era recusado com *"Grade é obrigatória"*, mensagem
        // que manda o operador procurar uma curva de tamanhos para um banho de cachorro.
        // A exigência vale para MERCADORIA, que é de onde ela veio.
        if (usaCorGrade && req.idGrade() == null && !ehServico(req)) {
            throw new IllegalArgumentException("Grade é obrigatória para este tenant.");
        }
        if (req.categorias() != null) {
            long distintas = req.categorias().stream().distinct().count();
            if (distintas != req.categorias().size()) {
                throw new IllegalArgumentException("Categoria duplicada na lista.");
            }
        }

        Map<String, ConfiguracaoCampoResponse> config = configuracaoTelaService.listar(CHAVE_TELA_FORM).stream()
                .collect(Collectors.toMap(ConfiguracaoCampoResponse::campo, c -> c));
        exigirSeObrigatorio(config, "marca", req.marca());
        exigirSeObrigatorio(config, "referencia", req.referencia());
        exigirSeObrigatorio(config, "codigoNcm", req.codigoNcm());
        exigirSeObrigatorioValor(config, "pesoBruto", req.pesoBruto());
        exigirSeObrigatorioValor(config, "pesoLiquido", req.pesoLiquido());
        exigirSeObrigatorioValor(config, "precoOferta", req.precoOferta());
        exigirSeObrigatorioValor(config, "dataInicioOferta", req.dataInicioOferta());
        exigirSeObrigatorioValor(config, "dataFinalOferta", req.dataFinalOferta());
    }

    /**
     * Preço de venda nunca pode ficar abaixo do preço de custo (2026-08-12, regra do projeto
     * inteiro — mesma checagem replicada no cliente em {@code ProdutoForm.tsx}/
     * {@code ProdutoQuickCreateModal.tsx}, reforçada aqui como defesa em profundidade).
     */
    private static void validarPrecos(ProdutoRequest req) {
        if (req.precoVenda().compareTo(req.precoCusto()) < 0) {
            throw new IllegalArgumentException("Preço de venda não pode ser menor que o preço de custo.");
        }
    }

    /**
     * Regra da oferta (itens 4-7, pedido do dono do produto — mesma regra do frontend,
     * {@code ProdutoForm.tsx#errosOferta}, reforçada aqui como defesa em profundidade): início,
     * final e preço de oferta só valem em conjunto — preencheu um, os três viram obrigatórios;
     * início não pode ser no passado; final não pode ser antes do início; preço de oferta tem
     * que ser menor que o preço de venda.
     */
    private static void validarOferta(ProdutoRequest req) {
        boolean temInicio = req.dataInicioOferta() != null;
        boolean temFinal = req.dataFinalOferta() != null;
        boolean temPreco = req.precoOferta() != null;
        if (!temInicio && !temFinal && !temPreco) {
            return;
        }
        if (!temInicio || !temFinal || !temPreco) {
            throw new IllegalArgumentException(
                    "Para a oferta ser válida, informe início, final e preço de oferta juntos.");
        }
        // Ver ClienteService: fuso explícito em vez do da JVM (indefinido em dev).
        if (req.dataInicioOferta().toLocalDate().isBefore(LocalDate.now(FusoDaUf.PADRAO))) {
            throw new IllegalArgumentException("Data de início da oferta não pode ser no passado.");
        }
        if (req.dataFinalOferta().isBefore(req.dataInicioOferta())) {
            throw new IllegalArgumentException("Data final da oferta não pode ser anterior à data de início.");
        }
        if (req.precoOferta().compareTo(req.precoVenda()) >= 0) {
            throw new IllegalArgumentException("Preço de oferta deve ser menor que o preço de venda.");
        }
    }

    /**
     * Traduz a violação de FK crua (produto→NCM ou produto_categoria→categoria) numa mensagem
     * amigável (400, não 500) — mesmo princípio de {@code ClienteService.duplicidade}, que
     * também inspeciona o nome da constraint na causa raiz para diferenciar qual vínculo falhou.
     */
    private static IllegalArgumentException erroDeVinculo(DataIntegrityViolationException e) {
        String causa = String.valueOf(e.getRootCause());
        if (causa.contains("produto_codigo_ncm_fkey")) {
            return new IllegalArgumentException("NCM informado não existe.");
        }
        if (causa.contains("produto_perfil_fiscal_fk")) {
            return new IllegalArgumentException("Perfil fiscal informado não existe.");
        }
        return new IllegalArgumentException("Categoria informada não existe.");
    }

/**
     * Mesma regra para campo NUMÉRICO/DATA (auditoria 2026-08-21, item 24).
     *
     * <p>Até 2026-08-22 só existia a versão para {@code String}, então <b>todo campo configurável
     * que é número ou data ficava sem revalidação no servidor</b>, por construção — apesar de o
     * {@code CLAUDE.md} afirmar que a bandeira é aplicada "de novo no servidor". O formulário
     * cobria, mas uma gravação pela API passava sem.
     *
     * <p>⚠️ Ausente é {@code null}. <b>Zero é valor legítimo</b> (decisão do dono do produto,
     * 2026-08-22: "se não informados, marcar como zero"), então esta validação não recusa zero.
     * Quem quer o campo fora do cadastro usa {@code visivel = false}, que é a dimensão certa —
     * a tabela tem as duas, com CHECK garantindo que obrigatório implica visível.
     */
    private static void exigirSeObrigatorioValor(Map<String, ConfiguracaoCampoResponse> config, String campo,
                                                  Object valor) {
        ConfiguracaoCampoResponse c = config.get(campo);
        if (c != null && c.obrigatorio() && valor == null) {
            throw new IllegalArgumentException(
                    ROTULOS_CAMPO.getOrDefault(campo, campo) + " é obrigatório.");
        }
    }

    private static void exigirSeObrigatorio(Map<String, ConfiguracaoCampoResponse> config, String campo, String valor) {
        ConfiguracaoCampoResponse c = config.get(campo);
        if (c != null && c.obrigatorio() && (valor == null || valor.isBlank())) {
            throw new IllegalArgumentException(
                    ROTULOS_CAMPO.getOrDefault(campo, campo) + " é obrigatório.");
        }
    }

    /**
     * Campos comuns a INSERT/UPDATE, na mesma ordem em que aparecem nas duas SQLs acima. Texto
     * livre em MAIÚSCULAS (convenção do projeto). {@code idGrade} grava 1 (grade PADRÃO,
     * reservada/invisível — 2026-08-13, ver {@code SignupService}) quando o tenant não usa
     * cor/grade ({@code cfg_geral.cfg_usa_cor_grade}) ou o campo não foi enviado — o campo fica
     * oculto no formulário, então qualquer valor enviado nesse caso é ignorado, não rejeitado;
     * quando o tenant usa, a obrigatoriedade já foi checada em {@code validar}.
     */
    private static void adicionarCamposComuns(List<Object> params, ProdutoRequest r, boolean usaCorGrade) {
        params.add(r.ativo() == null || r.ativo());
        params.add(trimMaiusculoOuNulo(r.marca()));
        params.add(trimMaiusculoOuNulo(r.referencia()));
        params.add(r.descricao().trim().toUpperCase(Locale.ROOT));
        params.add(r.precoCusto());
        params.add(r.percentualVenda());
        params.add(r.precoVenda());
        params.add(r.dataInicioOferta());
        params.add(r.dataFinalOferta());
        params.add(r.precoOferta());
        params.add(trimMaiusculoOuNulo(r.codigoNcm()));
        params.add(r.pesoBruto() == null ? BigDecimal.ZERO : r.pesoBruto());
        params.add(r.pesoLiquido() == null ? BigDecimal.ZERO : r.pesoLiquido());
        params.add(usaCorGrade && r.idGrade() != null ? r.idGrade() : 1L);
        params.add(r.idPerfilFiscal());
    }

    private static String trimMaiusculoOuNulo(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * {@code MERCADORIA} (padrão) ou {@code SERVICO}, validado aqui e não só pelo ENUM do banco —
     * um valor inválido chegaria como {@code DataIntegrityViolationException} e sairia como
     * <i>"registro em uso por outro cadastro"</i>, mensagem sobre exclusão para um cadastro
     * (mesma armadilha do {@code GlobalExceptionHandler} registrada em 2026-08-20).
     *
     * <p>⚠️ Nulo = {@code MERCADORIA}: cliente antigo que não manda o campo continua funcionando
     * exatamente como antes (F12).
     *
     * <p>⚠️ Cadastrar serviço exige o módulo ligado ({@code cfg_usa_servicos}, desligado por
     * padrão — decisão do dono do produto em 2026-08-28). Sem isso, a API aceitaria criar serviço
     * enquanto a tela não oferece o campo, e o item viraria uma linha que nenhuma tela explica.
     */
    /** Só a leitura do campo — sem validar nem consultar nada, para poder ser usada na validação. */
    private static boolean ehServico(ProdutoRequest req) {
        return req.tipoItem() != null && "SERVICO".equalsIgnoreCase(req.tipoItem().trim());
    }

    private String tipoItemValidado(ProdutoRequest req) {
        String tipo = req.tipoItem() == null || req.tipoItem().isBlank()
                ? "MERCADORIA"
                : req.tipoItem().trim().toUpperCase(Locale.ROOT);
        if (!tipo.equals("MERCADORIA") && !tipo.equals("SERVICO")) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Tipo de item inválido: use MERCADORIA ou SERVICO.");
        }
        if (tipo.equals("SERVICO") && !configuracaoGeralService.usaServicos()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "O módulo de serviços está desligado. Ligue \"Usa serviços\" em "
                    + "Parâmetros do Sistema para cadastrar serviços.");
        }
        return tipo;
    }

    /**
     * Grava (ou apaga) a extensão 1:1 de serviço. Padrão "apaga e regrava" já usado em
     * {@code salvarCategorias} — mais simples que diferenciar insert de update, e o volume é 1.
     *
     * <p>⚠️ Lê o tipo <b>do banco</b>, não do request: no {@code atualizar} o tipo é imutável e o
     * cliente pode nem tê-lo enviado. Confiar no request faria a edição de um serviço apagar a
     * linha de {@code produto_servico} sempre que o campo viesse vazio.
     */
    private void salvarServico(long idProduto, ProdutoRequest req) {
        boolean ehServico = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT tipo_item = 'SERVICO' FROM produto
                         WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?
                        """)
                .param(idProduto).query(Boolean.class).optional().orElse(false));

        jdbc.sql("DELETE FROM produto_servico WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?")
                .param(idProduto).update();
        if (!ehServico) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO produto_servico (
                            id_tenant, id_produto, duracao_minutos, perc_comissao,
                            codigo_tributacao_nacional, codigo_tributacao_municipal,
                            aliquota_iss, iss_retido_padrao)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(idProduto, req.duracaoMinutos(), req.percComissaoServico(),
                        vazioParaNulo(req.codigoTributacaoNacional()),
                        vazioParaNulo(req.codigoTributacaoMunicipal()),
                        req.aliquotaIss(),
                        Boolean.TRUE.equals(req.issRetidoPadrao()))
                .update();
    }

    /**
     * String vazia vira NULL antes do INSERT.
     *
     * <p>⚠️ Não é cosmético: {@code codigo_tributacao_nacional} tem FK para
     * {@code cfg_servico_lc116}, e "" não é código nenhum — passaria pela FK como valor de
     * verdade e estouraria com erro de integridade que o handler global traduz para "registro em
     * uso por outro cadastro", mensagem sobre exclusão para quem estava salvando um cadastro. É a
     * mesma armadilha registrada na V066 do marketplace.
     */
    private static String vazioParaNulo(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** {@code getInt} + {@code wasNull} para {@code int} anulável — idioma do projeto.
     *  ⚠️ Este javadoc afirmava que o driver "recusa {@code getObject(col, Integer.class)} sobre
     *  {@code int4}"; é FALSO (corrigido em 2026-08-30). O que o driver recusa é
     *  {@code Long.class} sobre {@code int4} — esse sim medido em 2026-08-20, e esse sim sai
     *  disfarçado de 409. */
    private static Integer duracaoOuNulo(ResultSet rs) throws SQLException {
        int v = rs.getInt("duracao_minutos");
        return rs.wasNull() ? null : v;
    }

    private List<CategoriaSelecionada> buscarCategorias(long idProduto) {
        return jdbc.sql("""
                        SELECT pc.id_categoria, cc.nome_categoria, pc.indice
                        FROM produto_categoria pc
                        JOIN cfg_categoria_produto cc ON cc.id_categoria = pc.id_categoria AND cc.id_tenant = pc.id_tenant
                        WHERE pc.id_tenant = plataforma.tenant_atual() AND pc.id_produto = ?
                        ORDER BY pc.indice
                        """)
                .param(idProduto)
                .query((rs, n) -> new CategoriaSelecionada(
                        rs.getLong("id_categoria"), rs.getString("nome_categoria"), rs.getInt("indice")))
                .list();
    }

    private static final String SELECT_BASE = """
            SELECT p.id_produto, p.descricao, p.marca, p.referencia, p.preco_custo, p.percentual_venda,
                   p.preco_venda, p.data_inicio_oferta, p.data_final_oferta, p.preco_oferta, p.codigo_ncm,
                   p.peso_bruto, p.peso_liquido, p.id_grade, g.descricao AS descricao_grade, p.ativo,
                   p.id_perfil_fiscal, pf.nome AS nome_perfil_fiscal,
                   p.tipo_item, ps.duracao_minutos, ps.perc_comissao AS perc_comissao_servico,
                   ps.codigo_tributacao_nacional, ps.codigo_tributacao_municipal,
                   ps.aliquota_iss, COALESCE(ps.iss_retido_padrao, false) AS iss_retido_padrao,
                   lc.descricao AS descricao_servico_lc116,
                   lc.local_incidencia::text AS local_incidencia,
                   p.criado_em, p.atualizado_em, p.reajustado_em
            FROM produto p
            LEFT JOIN cfg_grade g ON g.id_grade = p.id_grade AND g.id_tenant = p.id_tenant AND g.id_grade <> 1
            LEFT JOIN cfg_perfil_fiscal pf ON pf.id_perfil_fiscal = p.id_perfil_fiscal AND pf.id_tenant = p.id_tenant
            LEFT JOIN produto_servico ps ON ps.id_produto = p.id_produto AND ps.id_tenant = p.id_tenant
            -- Tabela GLOBAL (V099): sem id_tenant no ON, de propósito — é a lista da União.
            LEFT JOIN cfg_servico_lc116 lc ON lc.codigo = ps.codigo_tributacao_nacional
            """;

    /** id_grade=1 é a grade PADRÃO (2026-08-13, reservada/invisível, ver {@code SignupService})
     *  — a API nunca expõe esse valor: um produto sem grade de verdade devolve {@code idGrade
     *  null} pro cliente, exatamente como antes de {@code id_grade} virar {@code NOT NULL}. */
    private ProdutoResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id_produto");
        long idGrade = rs.getLong("id_grade");
        // pgjdbc não converte int4 -> java.lang.Long via getObject(coluna, Long.class) (PSQLException
        // "conversion to class java.lang.Long from int4 not supported") — precisa ler como long
        // primitivo e checar wasNull() à parte, mesmo idioma já usado em outros módulos do domínio.
        long idPerfilFiscalRaw = rs.getLong("id_perfil_fiscal");
        Long idPerfilFiscal = rs.wasNull() ? null : idPerfilFiscalRaw;
        return new ProdutoResponse(
                id,
                rs.getString("descricao"),
                rs.getString("marca"),
                rs.getString("referencia"),
                rs.getBigDecimal("preco_custo"),
                rs.getBigDecimal("percentual_venda"),
                rs.getBigDecimal("preco_venda"),
                rs.getObject("data_inicio_oferta", OffsetDateTime.class),
                rs.getObject("data_final_oferta", OffsetDateTime.class),
                rs.getBigDecimal("preco_oferta"),
                rs.getString("codigo_ncm"),
                rs.getBigDecimal("peso_bruto"),
                rs.getBigDecimal("peso_liquido"),
                idGrade == 1 ? null : idGrade,
                rs.getString("descricao_grade"),
                rs.getString("tipo_item"),
                // getInt + wasNull para ler int anulável. ⚠️ O comentário anterior afirmava que o
                // driver "recusa getObject(..., Integer.class) a partir de int4" — FALSO, corrigido
                // em 2026-08-30: o defeito medido naquele dia foi `Long.class` sobre int4.
                // `Integer.class` funciona; `getInt`+`wasNull` continua sendo o idioma do projeto
                // por clareza, não por impossibilidade.
                duracaoOuNulo(rs),
                rs.getBigDecimal("perc_comissao_servico"),
                rs.getString("codigo_tributacao_nacional"),
                rs.getString("codigo_tributacao_municipal"),
                rs.getBigDecimal("aliquota_iss"),
                rs.getBoolean("iss_retido_padrao"),
                rs.getString("descricao_servico_lc116"),
                rs.getString("local_incidencia"),
                rs.getBoolean("ativo"),
                buscarCategorias(id),
                produtoImagemService.listar(id),
                idPerfilFiscal,
                rs.getString("nome_perfil_fiscal"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class),
                rs.getObject("reajustado_em", OffsetDateTime.class));
    }
}
