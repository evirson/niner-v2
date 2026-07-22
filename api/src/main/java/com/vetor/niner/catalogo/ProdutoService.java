package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.ProdutoDtos.CategoriaSelecionada;
import com.vetor.niner.catalogo.ProdutoDtos.ExclusaoProdutoResponse;
import com.vetor.niner.catalogo.ProdutoDtos.PaginaProdutos;
import com.vetor.niner.catalogo.ProdutoDtos.ProdutoRequest;
import com.vetor.niner.catalogo.ProdutoDtos.ProdutoResponse;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaDtos.ConfiguracaoCampoResponse;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaService;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService.FlagsVariante;
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

    public ProdutoService(JdbcClient jdbc, ConfiguracaoTelaService configuracaoTelaService,
                          ConfiguracaoGeralService configuracaoGeralService) {
        this.jdbc = jdbc;
        this.configuracaoTelaService = configuracaoTelaService;
        this.configuracaoGeralService = configuracaoGeralService;
    }

    @Transactional(readOnly = true)
    public PaginaProdutos listar(String descricao, String marca, Long idCategoria, String status,
                                  Integer pagina, Integer limite, String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao = ordenarPor == null ? "p.descricao" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "p.descricao");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE 1 = 1");
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
            filtro.append(" AND EXISTS (SELECT 1 FROM produto_categoria pc WHERE pc.id_produto = p.id_produto AND pc.id_categoria = ?)");
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

    @Transactional(readOnly = true)
    public ProdutoResponse buscar(long id) {
        return jdbc.sql(SELECT_BASE + " WHERE p.id_produto = ?")
                .param(id)
                .query(this::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Produto não encontrado."));
    }

    @Transactional
    public ProdutoResponse criar(ProdutoRequest req) {
        validar(req);
        FlagsVariante flags = configuracaoGeralService.flagsVariante();
        List<Object> params = new ArrayList<>();
        adicionarCamposComuns(params, req, flags);

        try {
            long id = jdbc.sql("""
                            INSERT INTO produto (id_tenant, ativo, marca, referencia, descricao, preco_custo,
                                percentual_venda, preco_venda, data_inicio_oferta, data_final_oferta, preco_oferta,
                                codigo_ncm, peso_bruto, peso_liquido, nome_variante_linha, nome_variante_coluna)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            RETURNING id_produto
                            """)
                    .params(params)
                    .query(Long.class).single();
            salvarCategorias(id, req.categorias());
            return buscar(id);
        } catch (DataIntegrityViolationException e) {
            throw erroDeVinculo(e);
        }
    }

    @Transactional
    public ProdutoResponse atualizar(long id, ProdutoRequest req) {
        validar(req);
        FlagsVariante flags = configuracaoGeralService.flagsVariante();
        List<Object> params = new ArrayList<>();
        adicionarCamposComuns(params, req, flags);
        params.add(id);

        try {
            int linhas = jdbc.sql("""
                            UPDATE produto SET
                                ativo = ?, marca = ?, referencia = ?, descricao = ?, preco_custo = ?,
                                percentual_venda = ?, preco_venda = ?, data_inicio_oferta = ?, data_final_oferta = ?,
                                preco_oferta = ?, codigo_ncm = ?, peso_bruto = ?, peso_liquido = ?,
                                nome_variante_linha = ?, nome_variante_coluna = ?, atualizado_em = now()
                            WHERE id_produto = ?
                            """)
                    .params(params)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Produto não encontrado.");
            }
            salvarCategorias(id, req.categorias());
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
                                SELECT EXISTS (SELECT 1 FROM produto_barra WHERE id_produto = ?)
                                    OR EXISTS (SELECT 1 FROM produto_imagem WHERE id_produto = ?)
                                """)
                        .params(id, id).query(Boolean.class).single());

        if (temDependente) {
            int linhas = jdbc.sql("UPDATE produto SET ativo = false, atualizado_em = now() WHERE id_produto = ?")
                    .param(id).update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Produto não encontrado.");
            }
            return new ExclusaoProdutoResponse("inativado", "Produto possui variações ou imagens associadas.");
        }

        jdbc.sql("DELETE FROM produto_categoria WHERE id_produto = ?").param(id).update();
        int linhas = jdbc.sql("DELETE FROM produto WHERE id_produto = ?").param(id).update();
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
        jdbc.sql("DELETE FROM produto_categoria WHERE id_produto = ?").param(idProduto).update();
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
    private void validar(ProdutoRequest req) {
        validarOferta(req);
        BigDecimal pesoBruto = req.pesoBruto() == null ? BigDecimal.ZERO : req.pesoBruto();
        BigDecimal pesoLiquido = req.pesoLiquido() == null ? BigDecimal.ZERO : req.pesoLiquido();
        if (pesoLiquido.compareTo(pesoBruto) > 0) {
            throw new IllegalArgumentException("Peso líquido deve ser menor ou igual ao peso bruto.");
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
        if (req.dataInicioOferta().toLocalDate().isBefore(LocalDate.now())) {
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
        return new IllegalArgumentException("Categoria informada não existe.");
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
     * livre em MAIÚSCULAS (convenção do projeto). {@code nomeVarianteLinha}/{@code
     * nomeVarianteColuna} são forçados a {@code null} quando o tenant não usa a respectiva
     * variante ({@code cfg_geral.cfg_usa_variante_linha}/{@code cfg_usa_variante_coluna}) —
     * o campo fica oculto no formulário, então qualquer valor enviado é ignorado, não rejeitado.
     */
    private static void adicionarCamposComuns(List<Object> params, ProdutoRequest r, FlagsVariante flags) {
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
        params.add(flags.usaVarianteLinha() ? trimMaiusculoOuNulo(r.nomeVarianteLinha()) : null);
        params.add(flags.usaVarianteColuna() ? trimMaiusculoOuNulo(r.nomeVarianteColuna()) : null);
    }

    private static String trimMaiusculoOuNulo(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private List<CategoriaSelecionada> buscarCategorias(long idProduto) {
        return jdbc.sql("""
                        SELECT pc.id_categoria, cc.nome_categoria, pc.indice
                        FROM produto_categoria pc
                        JOIN cfg_categoria_produto cc ON cc.id_categoria = pc.id_categoria
                        WHERE pc.id_produto = ?
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
                   p.peso_bruto, p.peso_liquido, p.nome_variante_linha, p.nome_variante_coluna, p.ativo,
                   p.criado_em, p.atualizado_em
            FROM produto p
            """;

    private ProdutoResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id_produto");
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
                rs.getString("nome_variante_linha"),
                rs.getString("nome_variante_coluna"),
                rs.getBoolean("ativo"),
                buscarCategorias(id),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
