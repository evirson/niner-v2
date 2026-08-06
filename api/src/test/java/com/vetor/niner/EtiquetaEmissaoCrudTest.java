package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Emissão de Etiqueta de Produtos (2026-08-05, docs/telas/etiqueta-emissao.md) — produto/
 * fornecedor/entradas/estoques, todos somente leitura. `produto_movimento_mestre` de tipo
 * COMPRA gravado direto via JDBC (nenhum service do sistema faz isso ainda — ver package-info.java).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EtiquetaEmissaoCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Emissao %s","email":"dono%s@lojaemissao.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    private long criarProduto(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"10","precoVenda":"11.00"}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    /** Fornecedor exige `idPlanoContas` de um plano já existente — sem seed nos testes (só o
     * script separado que carrega as 336 contas reais), cada teste cria o próprio antes. */
    private void criarPlanoContas(String token, String codigo) throws Exception {
        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"%s","descricao":"DESPESA FORNECEDORES","tipoMovimento":"DEBITO",
                                 "natureza":"ANALITICA","incluiDre":false,"incluiFluxoCaixa":false}
                                """.formatted(codigo)))
                .andExpect(status().isCreated());
    }

    private long criarFornecedor(String token, String razaoSocial) throws Exception {
        criarPlanoContas(token, "2.00.000.000");
        String resp = mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"" + razaoSocial + "\",\"idPlanoContas\":\"2.00.000.000\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFornecedor")).longValue();
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    private long buscarIdEmpresa(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long criarVariacao(Connection c, long idTenant, long idProduto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_barra (id_tenant, id_produto, sku) VALUES (?, ?, gerar_ean13_interno()) "
                        + "RETURNING id_variacao")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long criarProdutoComVariantes(String token, String descricao, String nomeVarianteLinha, String nomeVarianteColuna)
            throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"10","precoVenda":"11.00",
                                 "nomeVarianteLinha":%s,"nomeVarianteColuna":%s}
                                """.formatted(descricao,
                                nomeVarianteLinha == null ? "null" : "\"" + nomeVarianteLinha + "\"",
                                nomeVarianteColuna == null ? "null" : "\"" + nomeVarianteColuna + "\"")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarVarianteLinha(Connection c, long idTenant, String descricao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO cfg_variante_linha (id_tenant, descricao) VALUES (?, ?) RETURNING id_variante_linha")) {
            ps.setLong(1, idTenant);
            ps.setString(2, descricao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long criarVarianteColuna(Connection c, long idTenant, String descricao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO cfg_variante_coluna (id_tenant, descricao) VALUES (?, ?) RETURNING id_variante_coluna")) {
            ps.setLong(1, idTenant);
            ps.setString(2, descricao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void criarEstoque(Connection c, long idTenant, long idEmpresa, long idVariacao, BigDecimal qtd) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idVariacao);
            ps.setBigDecimal(4, qtd);
            ps.executeUpdate();
        }
    }

    private long criarMovimentoCompra(Connection c, long idTenant, long idEmpresa, long idVariacao, BigDecimal qtd,
                                       OffsetDateTime dataMovimento, Long idFornecedor, Integer notaFiscal) throws SQLException {
        long idMovimento;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, data_movimento, id_fornecedor, nota_fiscal) "
                        + "VALUES (?, ?, 'COMPRA', ?, ?, ?) RETURNING id_movimento")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setObject(3, dataMovimento);
            if (idFornecedor != null) ps.setLong(4, idFornecedor); else ps.setNull(4, java.sql.Types.INTEGER);
            if (notaFiscal != null) ps.setInt(5, notaFiscal); else ps.setNull(5, java.sql.Types.INTEGER);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                idMovimento = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_movimento_detalhe
                    (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto, preco_custo)
                VALUES (?, ?, ?, ?, 'C', ?, 5.00)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idMovimento);
            ps.setLong(3, idEmpresa);
            ps.setLong(4, idVariacao);
            ps.setBigDecimal(5, qtd);
            ps.executeUpdate();
        }
        return idMovimento;
    }

    @Test
    void buscarProdutosTrazTodosOsAtivosComOuSemVariacao() throws Exception {
        String token = assinarNovoTenant("produtos");
        long idTenant = extrairIdTenant(token);
        long idComVariacao = criarProduto(token, "Produto Com Sku");
        criarProduto(token, "Produto Sem Sku");

        try (Connection c = abrirConexao(idTenant)) {
            criarVariacao(c, idTenant, idComVariacao);
        }

        mvc.perform(get("/api/v1/etiqueta-emissao/produtos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void buscarProdutosTrazNomeDasVariantesQuandoOProdutoUsa() throws Exception {
        String token = assinarNovoTenant("produtos-nomes-variante");
        criarProdutoComVariantes(token, "Camiseta Com Variante", "COR", "TAMANHO");

        mvc.perform(get("/api/v1/etiqueta-emissao/produtos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nomeVarianteLinha").value("COR"))
                .andExpect(jsonPath("$[0].nomeVarianteColuna").value("TAMANHO"));
    }

    @Test
    void buscarOpcoesVarianteRetornaCatalogoDoTenant() throws Exception {
        String token = assinarNovoTenant("opcoes-variante");
        long idTenant = extrairIdTenant(token);
        try (Connection c = abrirConexao(idTenant)) {
            criarVarianteLinha(c, idTenant, "AZUL");
            criarVarianteColuna(c, idTenant, "38");
        }

        mvc.perform(get("/api/v1/etiqueta-emissao/variantes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantesLinha[0].descricao").value("AZUL"))
                .andExpect(jsonPath("$.variantesColuna[0].descricao").value("38"));
    }

    @Test
    void obterOuCriarVariacaoCriaSkuNovoQuandoProdutoNaoUsaVariante() throws Exception {
        String token = assinarNovoTenant("criar-sem-variante");
        long idProduto = criarProduto(token, "Produto Simples");

        String resp = mvc.perform(post("/api/v1/etiqueta-emissao/produtos/" + idProduto + "/variacao")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String sku = JsonPath.read(resp, "$.sku");
        assertThat(sku).hasSize(13).containsOnlyDigits();
    }

    @Test
    void obterOuCriarVariacaoExigeLinhaQuandoOProdutoUsaEssaDimensao() throws Exception {
        String token = assinarNovoTenant("exige-linha");
        long idProduto = criarProdutoComVariantes(token, "Bota Com Cor", "COR", null);

        mvc.perform(post("/api/v1/etiqueta-emissao/produtos/" + idProduto + "/variacao")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void obterOuCriarVariacaoComLinhaEColunaCriaEDepoisReaproveitaAMesma() throws Exception {
        String token = assinarNovoTenant("cria-e-reaproveita");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProdutoComVariantes(token, "Bota Cor Tamanho", "COR", "TAMANHO");
        long idLinha;
        long idColuna;
        try (Connection c = abrirConexao(idTenant)) {
            idLinha = criarVarianteLinha(c, idTenant, "AZUL");
            idColuna = criarVarianteColuna(c, idTenant, "38");
        }

        String corpo = "{\"idVarianteLinha\":%d,\"idVarianteColuna\":%d}".formatted(idLinha, idColuna);

        String primeira = mvc.perform(post("/api/v1/etiqueta-emissao/produtos/" + idProduto + "/variacao")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variacaoLinha").value("AZUL"))
                .andExpect(jsonPath("$.variacaoColuna").value("38"))
                .andReturn().getResponse().getContentAsString();
        long idVariacaoPrimeira = ((Number) JsonPath.read(primeira, "$.idVariacao")).longValue();

        // 2ª chamada com a MESMA combinação não cria outra linha em produto_barra — reaproveita.
        String segunda = mvc.perform(post("/api/v1/etiqueta-emissao/produtos/" + idProduto + "/variacao")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long idVariacaoSegunda = ((Number) JsonPath.read(segunda, "$.idVariacao")).longValue();

        assertThat(idVariacaoSegunda).isEqualTo(idVariacaoPrimeira);
    }

    @Test
    void buscarFornecedoresRetornaAtivos() throws Exception {
        String token = assinarNovoTenant("fornecedores");
        criarFornecedor(token, "Distribuidora Alfa");

        mvc.perform(get("/api/v1/etiqueta-emissao/fornecedores").header("Authorization", "Bearer " + token)
                        .param("busca", "Alfa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].razaoSocial").value("DISTRIBUIDORA ALFA"));
    }

    @Test
    void buscarPorEntradasSemFiltroNenhumEhRejeitado() throws Exception {
        String token = assinarNovoTenant("entradas-sem-filtro");

        mvc.perform(get("/api/v1/etiqueta-emissao/entradas").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buscarPorEntradasSomaQuantidadeDeVariasComprasDaMesmaVariacao() throws Exception {
        String token = assinarNovoTenant("entradas-soma");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Camisa Entrada");
        long idFornecedor = criarFornecedor(token, "Fornecedor Entrada");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            criarMovimentoCompra(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"),
                    OffsetDateTime.now().minusDays(5), idFornecedor, 100);
            criarMovimentoCompra(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"),
                    OffsetDateTime.now().minusDays(3), idFornecedor, 101);
        }

        mvc.perform(get("/api/v1/etiqueta-emissao/entradas").header("Authorization", "Bearer " + token)
                        .param("idFornecedor", String.valueOf(idFornecedor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].quantidadeSugerida").value(15.0));
    }

    @Test
    void buscarPorEntradasFiltraPorNotaFiscal() throws Exception {
        String token = assinarNovoTenant("entradas-nota");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Bermuda Entrada");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao1 = criarVariacao(c, idTenant, idProduto);
            long idVariacao2 = criarVariacao(c, idTenant, idProduto);
            criarMovimentoCompra(c, idTenant, idEmpresa, idVariacao1, new BigDecimal("3.000"),
                    OffsetDateTime.now(), null, 500);
            criarMovimentoCompra(c, idTenant, idEmpresa, idVariacao2, new BigDecimal("7.000"),
                    OffsetDateTime.now(), null, 999);
        }

        mvc.perform(get("/api/v1/etiqueta-emissao/entradas").header("Authorization", "Bearer " + token)
                        .param("notaFiscal", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].quantidadeSugerida").value(3.0));
    }

    @Test
    void buscarPorEstoquesExigeEmpresaParaAdmin() throws Exception {
        String token = assinarNovoTenant("estoque-sem-empresa");

        mvc.perform(get("/api/v1/etiqueta-emissao/estoques").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buscarPorEstoquesSoTrazQuantidadePositiva() throws Exception {
        String token = assinarNovoTenant("estoque-positivo");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Estoque");

        long idEmpresa;
        try (Connection c = abrirConexao(idTenant)) {
            idEmpresa = buscarIdEmpresa(c);
            long idVariacaoComEstoque = criarVariacao(c, idTenant, idProduto);
            long idVariacaoZerada = criarVariacao(c, idTenant, idProduto);
            criarEstoque(c, idTenant, idEmpresa, idVariacaoComEstoque, new BigDecimal("8.000"));
            criarEstoque(c, idTenant, idEmpresa, idVariacaoZerada, BigDecimal.ZERO);
        }

        mvc.perform(get("/api/v1/etiqueta-emissao/estoques").header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].quantidadeSugerida").value(8.0));
    }

    @Test
    void buscarPorEstoquesFiltraPorCategoria() throws Exception {
        String token = assinarNovoTenant("estoque-categoria");
        long idTenant = extrairIdTenant(token);
        long idCategoria = criarCategoriaProduto(token, "Calçados");
        long idProdutoNaCategoria = criarProdutoComCategoria(token, "Bota Categoria", idCategoria);
        long idProdutoForaCategoria = criarProduto(token, "Camisa Sem Categoria");

        long idEmpresa;
        try (Connection c = abrirConexao(idTenant)) {
            idEmpresa = buscarIdEmpresa(c);
            long idVariacao1 = criarVariacao(c, idTenant, idProdutoNaCategoria);
            long idVariacao2 = criarVariacao(c, idTenant, idProdutoForaCategoria);
            criarEstoque(c, idTenant, idEmpresa, idVariacao1, new BigDecimal("4.000"));
            criarEstoque(c, idTenant, idEmpresa, idVariacao2, new BigDecimal("4.000"));
        }

        mvc.perform(get("/api/v1/etiqueta-emissao/estoques").header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("idCategoria", String.valueOf(idCategoria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].descricao").value("BOTA CATEGORIA"));
    }

    private long criarCategoriaProduto(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-produto").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoria")).longValue();
    }

    private long criarProdutoComCategoria(String token, String descricao, long idCategoria) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"10","precoVenda":"11.00",
                                 "categorias":[%d]}
                                """.formatted(descricao, idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    @Test
    void outroTenantNaoVaza() throws Exception {
        String tokenA = assinarNovoTenant("tenant-a-emissao");
        String tokenB = assinarNovoTenant("tenant-b-emissao");
        criarProduto(tokenA, "Produto Tenant A");

        mvc.perform(get("/api/v1/etiqueta-emissao/produtos").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
