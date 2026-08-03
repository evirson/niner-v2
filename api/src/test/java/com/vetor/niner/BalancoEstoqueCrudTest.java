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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rotina de Contagem de Estoque (2026-08-04, api/.../estoque/balanco/package-info.java) —
 * contagem por leitura de código de barras (soma leituras), diferenças vs. produto_estoque
 * (inclusive produto nunca contado), efetivação grava AJUSTE e zera o balanço ativo, desfazer
 * reverte o estoque e libera as linhas de volta pro balanço ativo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BalancoEstoqueCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Balanco %s","email":"dono%s@lojabalanco.com",
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

    private long criarProduto(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"20.00"}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
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

    private BigDecimal buscarEstoque(Connection c, long idVariacao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT qtd_estoque FROM produto_estoque WHERE id_variacao = ?")) {
            ps.setLong(1, idVariacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private void registrarContagem(String token, long idVariacao, String qtd) throws Exception {
        mvc.perform(post("/api/v1/estoque/balanco/contagem").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idVariacao\":%d,\"qtd\":%s}".formatted(idVariacao, qtd)))
                .andExpect(status().isNoContent());
    }

    @Test
    void registrarContagemAcumulaLeiturasDoMesmoProduto() throws Exception {
        TenantNovo tenant = assinarNovoTenant("acumula");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Balanco Acumula");
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            idVariacao = criarVariacao(c, idTenant, idProduto);
        }

        registrarContagem(tenant.token(), idVariacao, "3");
        registrarContagem(tenant.token(), idVariacao, "2");

        mvc.perform(get("/api/v1/estoque/balanco/contagem").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].descricaoProduto").value("PRODUTO BALANCO ACUMULA"))
                .andExpect(jsonPath("$[0].qtdContada").value(5));
    }

    @Test
    void ajustarContagemCorrigeSemAfetarOutroProduto() throws Exception {
        TenantNovo tenant = assinarNovoTenant("ajusta");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoA = criarProduto(tenant.token(), "Produto Balanco Ajusta A");
        long idProdutoB = criarProduto(tenant.token(), "Produto Balanco Ajusta B");
        long idVariacaoA;
        long idVariacaoB;
        try (Connection c = abrirConexao(idTenant)) {
            idVariacaoA = criarVariacao(c, idTenant, idProdutoA);
            idVariacaoB = criarVariacao(c, idTenant, idProdutoB);
        }

        registrarContagem(tenant.token(), idVariacaoA, "10");
        registrarContagem(tenant.token(), idVariacaoB, "7");

        mvc.perform(put("/api/v1/estoque/balanco/contagem/" + idVariacaoA).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"qtdContada\":4}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/estoque/balanco/contagem").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].qtdContada").value(4))
                .andExpect(jsonPath("$[1].qtdContada").value(7));
    }

    @Test
    void removerContagemApagaALinhaInteira() throws Exception {
        TenantNovo tenant = assinarNovoTenant("remove");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Balanco Remove");
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            idVariacao = criarVariacao(c, idTenant, idProduto);
        }

        registrarContagem(tenant.token(), idVariacao, "1");

        mvc.perform(delete("/api/v1/estoque/balanco/contagem/" + idVariacao).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/estoque/balanco/contagem").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void zerarContagemApagaTodasAsLinhasAtivas() throws Exception {
        TenantNovo tenant = assinarNovoTenant("zerar");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoA = criarProduto(tenant.token(), "Produto Balanco Zerar A");
        long idProdutoB = criarProduto(tenant.token(), "Produto Balanco Zerar B");
        try (Connection c = abrirConexao(idTenant)) {
            registrarContagem(tenant.token(), criarVariacao(c, idTenant, idProdutoA), "1");
            registrarContagem(tenant.token(), criarVariacao(c, idTenant, idProdutoB), "2");
        }

        mvc.perform(delete("/api/v1/estoque/balanco/contagem").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/estoque/balanco/contagem").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void diferencasMostraProdutoContadoComQuantidadeDiferenteDoEstoque() throws Exception {
        TenantNovo tenant = assinarNovoTenant("diferenca");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Balanco Diferenca");
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
        }
        registrarContagem(tenant.token(), idVariacao, "7");

        mvc.perform(get("/api/v1/estoque/balanco/diferencas").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(1))
                .andExpect(jsonPath("$.linhas[0].qtdEstoque").value(10))
                .andExpect(jsonPath("$.linhas[0].qtdContada").value(7))
                .andExpect(jsonPath("$.linhas[0].diferenca").value(-3));
    }

    @Test
    void diferencasMostraProdutoEmEstoqueNuncaContadoQuandoHaContagemEmAndamento() throws Exception {
        TenantNovo tenant = assinarNovoTenant("nunca-contado");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoEsquecido = criarProduto(tenant.token(), "Produto Balanco Nunca Contado");
        long idProdutoContado = criarProduto(tenant.token(), "Produto Balanco Contado Na Rodada");
        long idVariacaoContado;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacaoEsquecido = criarVariacao(c, idTenant, idProdutoEsquecido);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoEsquecido, new BigDecimal("5.000"));
            idVariacaoContado = criarVariacao(c, idTenant, idProdutoContado);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoContado, new BigDecimal("3.000"));
        }
        // Só com uma contagem em andamento (produto contado bate com o estoque, de propósito)
        // é que o produto esquecido deve aparecer como diferença — sem NENHUMA leitura na
        // empresa, a tela deve vir vazia (ver `diferencasSemNenhumaContagemVemVaziaComFlag`).
        registrarContagem(tenant.token(), idVariacaoContado, "3");

        mvc.perform(get("/api/v1/estoque/balanco/diferencas").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existeContagemAtiva").value(true))
                .andExpect(jsonPath("$.linhas.length()").value(1))
                .andExpect(jsonPath("$.linhas[0].qtdEstoque").value(5))
                .andExpect(jsonPath("$.linhas[0].qtdContada").value(0))
                .andExpect(jsonPath("$.linhas[0].diferenca").value(-5));
    }

    @Test
    void diferencasSemNenhumaContagemVemVaziaComFlag() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sem-contagem-nenhuma");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Balanco Sem Contagem Nenhuma");
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
        }

        mvc.perform(get("/api/v1/estoque/balanco/diferencas").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existeContagemAtiva").value(false))
                .andExpect(jsonPath("$.linhas.length()").value(0));
    }

    @Test
    void diferencasNaoMostraProdutoComContagemIgualAoEstoque() throws Exception {
        TenantNovo tenant = assinarNovoTenant("igual");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Balanco Igual");
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("8.000"));
        }
        registrarContagem(tenant.token(), idVariacao, "8");

        mvc.perform(get("/api/v1/estoque/balanco/diferencas").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(0));
    }

    @Test
    void efetivarGravaAjusteAtualizaEstoqueEZeraBalancoAtivo() throws Exception {
        TenantNovo tenant = assinarNovoTenant("efetivar");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoSobra = criarProduto(tenant.token(), "Produto Balanco Efetivar Sobra");
        long idProdutoFalta = criarProduto(tenant.token(), "Produto Balanco Efetivar Falta");
        long idVariacaoSobra;
        long idVariacaoFalta;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacaoSobra = criarVariacao(c, idTenant, idProdutoSobra);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoSobra, new BigDecimal("10.000"));
            idVariacaoFalta = criarVariacao(c, idTenant, idProdutoFalta);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoFalta, new BigDecimal("10.000"));
        }
        registrarContagem(tenant.token(), idVariacaoSobra, "15"); // +5 (crédito)
        registrarContagem(tenant.token(), idVariacaoFalta, "6");  // -4 (débito)

        mvc.perform(post("/api/v1/estoque/balanco/efetivar").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProdutosAjustados").value(2));

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(buscarEstoque(c, idVariacaoSobra)).isEqualByComparingTo("15.000");
            assertThat(buscarEstoque(c, idVariacaoFalta)).isEqualByComparingTo("6.000");
        }

        mvc.perform(get("/api/v1/estoque/balanco/contagem").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/v1/estoque/balanco/diferencas").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(0));
    }

    @Test
    void efetivarSemDiferencasRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sem-diferenca");
        mvc.perform(post("/api/v1/estoque/balanco/efetivar").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void desfazerRevertEstoqueERestauraBalancoAtivo() throws Exception {
        TenantNovo tenant = assinarNovoTenant("desfazer");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Balanco Desfazer");
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
        }
        registrarContagem(tenant.token(), idVariacao, "13");

        mvc.perform(post("/api/v1/estoque/balanco/efetivar").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(buscarEstoque(c, idVariacao)).isEqualByComparingTo("13.000");
        }

        mvc.perform(get("/api/v1/estoque/balanco/ultima-efetivacao").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existe").value(true))
                .andExpect(jsonPath("$.totalProdutos").value(1));

        mvc.perform(post("/api/v1/estoque/balanco/desfazer").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNoContent());

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(buscarEstoque(c, idVariacao)).isEqualByComparingTo("10.000");
        }

        // A contagem original (13) volta pro balanço ativo, pronta pra corrigir e efetivar de novo.
        mvc.perform(get("/api/v1/estoque/balanco/contagem").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].qtdContada").value(13));

        mvc.perform(get("/api/v1/estoque/balanco/ultima-efetivacao").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existe").value(false));
    }

    @Test
    void desfazerSemEfetivacaoRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("desfazer-vazio");
        mvc.perform(post("/api/v1/estoque/balanco/desfazer").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void desfazerDuasVezesSeguidasNaSegundaNaoHaMaisNadaParaDesfazer() throws Exception {
        TenantNovo tenant = assinarNovoTenant("desfazer-duas-vezes");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Balanco Desfazer Duas Vezes");
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
        }
        registrarContagem(tenant.token(), idVariacao, "20");
        mvc.perform(post("/api/v1/estoque/balanco/efetivar").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/estoque/balanco/desfazer").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNoContent());

        // Só havia uma efetivação — desfazer de novo sem uma nova efetivação no meio não tem mais o que desfazer.
        mvc.perform(post("/api/v1/estoque/balanco/desfazer").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void desfazerVoltaProEfetivacaoAnteriorQuandoAMaisRecenteJaFoiDesfeita() throws Exception {
        TenantNovo tenant = assinarNovoTenant("desfazer-rolling");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoA = criarProduto(tenant.token(), "Produto Balanco Rolling A");
        long idProdutoB = criarProduto(tenant.token(), "Produto Balanco Rolling B");
        long idVariacaoA;
        long idVariacaoB;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacaoA = criarVariacao(c, idTenant, idProdutoA);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoA, new BigDecimal("10.000"));
            // Produto B só ganha estoque na 2ª rodada (nenhum produto_estoque agora) — se ele já
            // tivesse saldo aqui, a regra "não contado conta como diferença" o incluiria (sem
            // querer) já na 1ª efetivação, contaminando o teste que quer isolar só o mecanismo
            // de desfazer em rodadas separadas (a regra de "nunca contado" em si já tem teste
            // próprio, `diferencasMostraProdutoEmEstoqueNuncaContado`).
            idVariacaoB = criarVariacao(c, idTenant, idProdutoB);
        }

        // 1ª efetivação: só o produto A.
        registrarContagem(tenant.token(), idVariacaoA, "20");
        mvc.perform(post("/api/v1/estoque/balanco/efetivar").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());

        // 2ª efetivação: só o produto B (nasce agora, direto em 30).
        registrarContagem(tenant.token(), idVariacaoB, "30");
        mvc.perform(post("/api/v1/estoque/balanco/efetivar").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());

        // Desfaz a mais recente (2ª, produto B volta a 0 — não existia antes dela).
        mvc.perform(post("/api/v1/estoque/balanco/desfazer").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNoContent());
        try (Connection c = abrirConexao(idTenant)) {
            assertThat(buscarEstoque(c, idVariacaoB)).isEqualByComparingTo("0.000");
            assertThat(buscarEstoque(c, idVariacaoA)).isEqualByComparingTo("20.000");
        }

        // Desfaz de novo: agora não há efetivação nova depois da 1ª, então desfaz a 1ª (produto A volta a 10).
        mvc.perform(post("/api/v1/estoque/balanco/desfazer").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNoContent());
        try (Connection c = abrirConexao(idTenant)) {
            assertThat(buscarEstoque(c, idVariacaoA)).isEqualByComparingTo("10.000");
        }
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        TenantNovo tenantA = assinarNovoTenant("isolamento-balanco-a");
        TenantNovo tenantB = assinarNovoTenant("isolamento-balanco-b");
        long idTenantA = extrairIdTenant(tenantA.token());
        long idProduto = criarProduto(tenantA.token(), "Produto Isolamento Balanco");
        try (Connection c = abrirConexao(idTenantA)) {
            long idVariacao = criarVariacao(c, idTenantA, idProduto);
            registrarContagem(tenantA.token(), idVariacao, "1");
        }

        mvc.perform(get("/api/v1/estoque/balanco/contagem").header("Authorization", "Bearer " + tenantB.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
