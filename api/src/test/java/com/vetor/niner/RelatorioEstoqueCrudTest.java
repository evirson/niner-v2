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
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Relatório de Estoque (package-info.java do pacote estoque.relatorioestoque) — 3 modelos
 * (Inventário/Sintético/Analítico), colunas dinâmicas por empresa, filtros de marca/categoria/
 * situação/tipo de quantidade.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RelatorioEstoqueCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Relatorio Estoque %s","email":"dono%s@lojarelatorioestoque.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new TenantNovo(JsonPath.read(resp, "$.token"), JsonPath.read(resp, "$.slug"));
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    private long buscarPrimeiraEmpresa(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$[0].idEmpresa")).longValue();
    }

    private String criarOperadorEFazerLogin(String tokenAdmin, String slug, String sufixo, long idEmpresa) throws Exception {
        String email = "operador%s@lojarelatorioestoque.com".formatted(sufixo);
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nome":"Operador %s","email":"%s","senha":"senha1234",
                                 "administrador":false,"idsEmpresa":[%d]}
                                """.formatted(sufixo, email, idEmpresa)))
                .andExpect(status().isCreated());

        // RBAC (2026-08-27): operador nasce sem acesso a nada. Este teste é sobre outra
        // regra, então recebe a grade que um administrador daria — ver PermissaoDeTeste.
        PermissaoDeTeste.liberarTudoPorEmail(mvc, tokenAdmin, email);
        String login = """
                {"email":"%s","senha":"senha1234"}
                """.formatted(email);
        String resp = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private long criarProduto(String token, String descricao, String marca, String referencia, String precoCusto,
                               boolean ativo, List<Long> categorias) throws Exception {
        String categoriasJson = categorias.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","marca":"%s","referencia":"%s","precoCusto":"%s",
                                 "percentualVenda":"100","precoVenda":"50.00","ativo":%s,"categorias":%s}
                                """.formatted(descricao, marca, referencia, precoCusto, ativo, categoriasJson)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarCategoriaProduto(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-produto").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoria")).longValue();
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    private long criarSegundaEmpresa(Connection c, long idTenant) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, cfg_nome_etiqueta) VALUES ("
                             + idTenant + ", 2, 'FILIAL RELATORIO ESTOQUE', '{sku}') RETURNING id_empresa")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long criarVariacao(Connection c, long idTenant, long idProduto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_barra (id_tenant, id_produto, sku) VALUES (?, ?, gerar_ean13_interno())
                RETURNING id_variacao
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 2ª (ou 3ª...) variação do MESMO produto (2026-08-13) — {@code criarVariacao(c, idTenant,
     *  idProduto)} sempre grava cor/tamanho PADRÃO (id=1), então duas chamadas pro mesmo produto
     *  colidiriam em {@code produto_barra_variacao_uk}; este overload usa um tamanho real e
     *  distinto pra cada variação extra. */
    private long criarVariacaoComTamanho(Connection c, long idTenant, long idProduto, String descricaoTamanho) throws SQLException {
        long idTamanho;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO cfg_tamanho (id_tenant, id_tamanho, descricao)
                VALUES (?, COALESCE((SELECT MAX(id_tamanho) FROM cfg_tamanho WHERE id_tenant = ?), 0) + 1, ?)
                RETURNING id_tamanho
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idTenant);
            ps.setString(3, descricaoTamanho);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                idTamanho = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_barra (id_tenant, id_produto, id_tamanho, sku)
                VALUES (?, ?, ?, gerar_ean13_interno())
                RETURNING id_variacao
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            ps.setLong(3, idTamanho);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void definirEstoque(Connection c, long idTenant, long idEmpresa, long idVariacao, BigDecimal qtd) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque) VALUES (?, ?, ?, ?)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idVariacao);
            ps.setBigDecimal(4, qtd);
            ps.execute();
        }
    }

    @Test
    void inventarioSomaQuantidadeDeVariasVariacoesEEmpresasECalculaCustoTotal() throws Exception {
        TenantNovo tenant = assinarNovoTenant("inventario-soma");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Inventario Soma", "MARCA A", "REF1", "10.00", true, List.of());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa1 = buscarIdEmpresaViaConexao(c);
            long idEmpresa2 = criarSegundaEmpresa(c, idTenant);
            long idVariacao1 = criarVariacao(c, idTenant, idProduto);
            long idVariacao2 = criarVariacaoComTamanho(c, idTenant, idProduto, "38");
            definirEstoque(c, idTenant, idEmpresa1, idVariacao1, new BigDecimal("3.000"));
            definirEstoque(c, idTenant, idEmpresa1, idVariacao2, new BigDecimal("2.000"));
            definirEstoque(c, idTenant, idEmpresa2, idVariacao1, new BigDecimal("5.000"));
        }

        mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "INVENTARIO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasInventario.length()").value(1))
                .andExpect(jsonPath("$.linhasInventario[0].qtdTotal").value(10))
                .andExpect(jsonPath("$.linhasInventario[0].custoUnitario").value(10.00))
                .andExpect(jsonPath("$.linhasInventario[0].custoTotal").value(100.00))
                .andExpect(jsonPath("$.totalInventario.qtdTotal").value(10))
                .andExpect(jsonPath("$.totalInventario.custoTotal").value(100.00));
    }

    @Test
    void sinteticoAbreQuantidadePorEmpresaComColunaDeTotal() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sintetico-colunas");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Sintetico Colunas", "MARCA B", "REF2", "5.00", true, List.of());

        long idEmpresa1;
        long idEmpresa2;
        try (Connection c = abrirConexao(idTenant)) {
            idEmpresa1 = buscarIdEmpresaViaConexao(c);
            idEmpresa2 = criarSegundaEmpresa(c, idTenant);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa1, idVariacao, new BigDecimal("4.000"));
            definirEstoque(c, idTenant, idEmpresa2, idVariacao, new BigDecimal("6.000"));
        }

        String resp = mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "SINTETICO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.colunasEmpresa.length()").value(2))
                .andExpect(jsonPath("$.linhasSintetico.length()").value(1))
                .andExpect(jsonPath("$.linhasSintetico[0].qtdTotal").value(10))
                .andReturn().getResponse().getContentAsString();

        List<Number> idsColunaRaw = JsonPath.read(resp, "$.colunasEmpresa[*].idEmpresa");
        List<Long> idsColuna = idsColunaRaw.stream().map(Number::longValue).toList();
        int indice1 = idsColuna.indexOf(idEmpresa1);
        int indice2 = idsColuna.indexOf(idEmpresa2);
        List<Number> qtdPorEmpresa = JsonPath.read(resp, "$.linhasSintetico[0].qtdPorEmpresa");
        assertThat(qtdPorEmpresa.get(indice1).doubleValue()).isEqualTo(4.0);
        assertThat(qtdPorEmpresa.get(indice2).doubleValue()).isEqualTo(6.0);
    }

    @Test
    void analiticoTrazUmaLinhaPorVariacaoSemTotalizarNoBackend() throws Exception {
        TenantNovo tenant = assinarNovoTenant("analitico-variacao");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Analitico Variacao", "MARCA C", "REF3", "8.00", true, List.of());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresaViaConexao(c);
            long idVariacao1 = criarVariacao(c, idTenant, idProduto);
            long idVariacao2 = criarVariacaoComTamanho(c, idTenant, idProduto, "40");
            definirEstoque(c, idTenant, idEmpresa, idVariacao1, new BigDecimal("1.000"));
            definirEstoque(c, idTenant, idEmpresa, idVariacao2, new BigDecimal("2.000"));
        }

        mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "ANALITICO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasAnalitico.length()").value(2))
                .andExpect(jsonPath("$.linhasAnalitico[0].variacaoCor").value(nullValue()))
                .andExpect(jsonPath("$.linhasAnalitico[0].variacaoTamanho").value(nullValue()))
                .andExpect(jsonPath("$.totalInventario").doesNotExist())
                .andExpect(jsonPath("$.totalSintetico").doesNotExist());
    }

    @Test
    void filtroDeMarcaRestringeAsLinhas() throws Exception {
        TenantNovo tenant = assinarNovoTenant("filtro-marca");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoA = criarProduto(tenant.token(), "Produto Marca A", "MARCA FILTRO A", "REF-A", "1.00", true, List.of());
        criarProduto(tenant.token(), "Produto Marca B", "MARCA FILTRO B", "REF-B", "1.00", true, List.of());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresaViaConexao(c);
            long idVariacao = criarVariacao(c, idTenant, idProdutoA);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("1.000"));
        }

        mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "INVENTARIO").param("marcas", "MARCA FILTRO A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasInventario.length()").value(1))
                .andExpect(jsonPath("$.linhasInventario[0].descricaoProduto").value("PRODUTO MARCA A"));
    }

    @Test
    void filtroDeCategoriaRestringeAsLinhas() throws Exception {
        TenantNovo tenant = assinarNovoTenant("filtro-categoria");
        long idTenant = extrairIdTenant(tenant.token());
        long idCategoria = criarCategoriaProduto(tenant.token(), "CATEGORIA FILTRO");
        long idProdutoComCategoria = criarProduto(tenant.token(), "Produto Com Categoria", "MARCA", "REF", "1.00", true, List.of(idCategoria));
        long idProdutoSemCategoria = criarProduto(tenant.token(), "Produto Sem Categoria", "MARCA", "REF", "1.00", true, List.of());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresaViaConexao(c);
            definirEstoque(c, idTenant, idEmpresa, criarVariacao(c, idTenant, idProdutoComCategoria), new BigDecimal("1.000"));
            definirEstoque(c, idTenant, idEmpresa, criarVariacao(c, idTenant, idProdutoSemCategoria), new BigDecimal("1.000"));
        }

        mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "INVENTARIO").param("idsCategoria", String.valueOf(idCategoria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasInventario.length()").value(1))
                .andExpect(jsonPath("$.linhasInventario[0].descricaoProduto").value("PRODUTO COM CATEGORIA"));
    }

    @Test
    void filtroTipoQuantidadeSeparaZeradaDeDiferenteDeZero() throws Exception {
        TenantNovo tenant = assinarNovoTenant("filtro-tipo-qtd");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoComEstoque = criarProduto(tenant.token(), "Produto Com Estoque", "MARCA", "REF", "1.00", true, List.of());
        long idProdutoZerado = criarProduto(tenant.token(), "Produto Zerado", "MARCA", "REF", "1.00", true, List.of());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresaViaConexao(c);
            long idVariacao = criarVariacao(c, idTenant, idProdutoComEstoque);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("3.000"));
            // Produto Zerado precisa de ao menos uma variação pra existir no relatório (INNER JOIN
            // produto_barra — produto sem nenhuma variação não tem como ter estoque nunca), mas
            // SEM linha em produto_estoque (nunca movimentado) — COALESCE trata como 0, é a mesma
            // situação de "estoque nunca escaneado" já coberta em Diferenças de Estoque.
            criarVariacao(c, idTenant, idProdutoZerado);
        }

        mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "INVENTARIO").param("tipoQuantidade", "DIFERENTE_DE_ZERO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasInventario.length()").value(1))
                .andExpect(jsonPath("$.linhasInventario[0].descricaoProduto").value("PRODUTO COM ESTOQUE"));

        mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "INVENTARIO").param("tipoQuantidade", "ZERADA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasInventario.length()").value(1))
                .andExpect(jsonPath("$.linhasInventario[0].descricaoProduto").value("PRODUTO ZERADO"));
    }

    @Test
    void situacaoDoProdutoPadraoEscondeInativosETodosMostraTudo() throws Exception {
        TenantNovo tenant = assinarNovoTenant("situacao-produto");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoAtivo = criarProduto(tenant.token(), "Produto Ativo Situacao", "MARCA", "REF", "1.00", true, List.of());
        long idProdutoInativo = criarProduto(tenant.token(), "Produto Inativo Situacao", "MARCA", "REF", "1.00", false, List.of());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresaViaConexao(c);
            definirEstoque(c, idTenant, idEmpresa, criarVariacao(c, idTenant, idProdutoAtivo), new BigDecimal("1.000"));
            definirEstoque(c, idTenant, idEmpresa, criarVariacao(c, idTenant, idProdutoInativo), new BigDecimal("1.000"));
        }

        mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "INVENTARIO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasInventario.length()").value(1))
                .andExpect(jsonPath("$.linhasInventario[0].descricaoProduto").value("PRODUTO ATIVO SITUACAO"));

        mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "INVENTARIO").param("situacao", "INATIVOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasInventario.length()").value(1))
                .andExpect(jsonPath("$.linhasInventario[0].descricaoProduto").value("PRODUTO INATIVO SITUACAO"));

        mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "INVENTARIO").param("situacao", "TODOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasInventario.length()").value(2));
    }

    @Test
    void operadorSempreConsultaAPropriaEmpresaMesmoInformandoOutra() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-estoque");
        long idTenant = extrairIdTenant(tenant.token());
        long idEmpresaOrigem = buscarPrimeiraEmpresa(tenant.token());

        long idEmpresaDestino;
        try (Connection c = abrirConexao(idTenant)) {
            idEmpresaDestino = criarSegundaEmpresa(c, idTenant);
        }

        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "operador-estoque", idEmpresaOrigem);

        String resp = mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tokenOperador)
                        .param("modelo", "SINTETICO").param("idsEmpresa", String.valueOf(idEmpresaDestino)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Number> idsColuna = JsonPath.read(resp, "$.colunasEmpresa[*].idEmpresa");
        assertThat(idsColuna).extracting(Number::longValue).containsExactly(idEmpresaOrigem);
    }

    @Test
    void adminSemFiltroDeEmpresaUsaTodasAsEmpresasAtivasDoTenant() throws Exception {
        TenantNovo tenant = assinarNovoTenant("admin-todas-empresas");
        long idTenant = extrairIdTenant(tenant.token());
        long idEmpresaOrigem = buscarPrimeiraEmpresa(tenant.token());

        long idEmpresaDestino;
        try (Connection c = abrirConexao(idTenant)) {
            idEmpresaDestino = criarSegundaEmpresa(c, idTenant);
        }

        String resp = mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "SINTETICO"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Number> idsColuna = JsonPath.read(resp, "$.colunasEmpresa[*].idEmpresa");
        assertThat(idsColuna).extracting(Number::longValue).containsExactlyInAnyOrder(idEmpresaOrigem, idEmpresaDestino);
    }

    @Test
    void ordenacaoPadraoEPorDescricaoDoProduto() throws Exception {
        TenantNovo tenant = assinarNovoTenant("ordenacao-descricao");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoZebra = criarProduto(tenant.token(), "Zebra Produto", "MARCA", "REF", "1.00", true, List.of());
        long idProdutoAbelha = criarProduto(tenant.token(), "Abelha Produto", "MARCA", "REF", "1.00", true, List.of());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresaViaConexao(c);
            definirEstoque(c, idTenant, idEmpresa, criarVariacao(c, idTenant, idProdutoZebra), new BigDecimal("1.000"));
            definirEstoque(c, idTenant, idEmpresa, criarVariacao(c, idTenant, idProdutoAbelha), new BigDecimal("1.000"));
        }

        mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "INVENTARIO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasInventario.length()").value(2))
                .andExpect(jsonPath("$.linhasInventario[0].descricaoProduto").value("ABELHA PRODUTO"))
                .andExpect(jsonPath("$.linhasInventario[1].descricaoProduto").value("ZEBRA PRODUTO"));
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        TenantNovo tenantA = assinarNovoTenant("isolamento-estoque-a");
        TenantNovo tenantB = assinarNovoTenant("isolamento-estoque-b");
        criarProduto(tenantA.token(), "Produto Isolamento Estoque", "MARCA", "REF", "1.00", true, List.of());

        mvc.perform(get("/api/v1/relatorios/estoque").header("Authorization", "Bearer " + tenantB.token())
                        .param("modelo", "INVENTARIO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasInventario.length()").value(0));
    }

    private long buscarIdEmpresaViaConexao(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
