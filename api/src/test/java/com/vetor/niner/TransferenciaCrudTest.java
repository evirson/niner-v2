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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transferência de estoque entre empresas (docs/telas/transferencia-estoque.md) — mesmo padrão
 * de {@link PdvCrudTest}: produto/variação/estoque semeados direto via JDBC (sem endpoint de
 * estoque ainda); a empresa de destino é uma segunda empresa criada direto no banco, já que não
 * existe CRUD de empresa. A empresa de origem vem do claim {@code eid} do token de signup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TransferenciaCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Transferencia %s","email":"dono%s@lojatransferencia.com",
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
        return ((Number) JsonPath.read(payload, "$.tid")).longValue();
    }

    private static long extrairIdEmpresa(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        return ((Number) JsonPath.read(payload, "$.eid")).longValue();
    }

    private long criarProduto(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"50.00","ativo":true}
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

    private void definirEstoque(Connection c, long idTenant, long idEmpresa, long idVariacao, BigDecimal qtd) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idVariacao);
            ps.setBigDecimal(4, qtd);
            ps.executeUpdate();
        }
    }

    private BigDecimal buscarQtdEstoque(Connection c, long idEmpresa, long idVariacao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT qtd_estoque FROM produto_estoque WHERE id_empresa = ? AND id_variacao = ?")) {
            ps.setLong(1, idEmpresa);
            ps.setLong(2, idVariacao);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    private long criarSegundaEmpresa(Connection c, long idTenant) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, cfg_nome_etiqueta) VALUES ("
                             + idTenant + ", 2, 'FILIAL DOIS', '{sku}') RETURNING id_empresa")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void criaTransferenciaComEstoqueSuficienteEMovimentaOsSaldos() throws Exception {
        String token = assinarNovoTenant("sucesso");
        long idTenant = extrairIdTenant(token);
        long idEmpresaOrigem = extrairIdEmpresa(token);
        long idProduto = criarProduto(token, "Produto Transferido");

        long idVariacao;
        long idEmpresaDestino;
        try (Connection c = abrirConexao(idTenant)) {
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresaOrigem, idVariacao, new BigDecimal("10.000"));
            idEmpresaDestino = criarSegundaEmpresa(c, idTenant);
        }

        String corpo = """
                {"idEmpresaDestino":%d,"itens":[{"idVariacao":%d,"qtd":4}],"observacoes":"teste"}
                """.formatted(idEmpresaDestino, idVariacao);

        mvc.perform(post("/api/v1/estoque/transferencias").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.empresaOrigem.idEmpresa").value(idEmpresaOrigem))
                .andExpect(jsonPath("$.empresaDestino.idEmpresa").value(idEmpresaDestino))
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].qtd").value(4));

        try (Connection c = abrirConexao(idTenant)) {
            assertEquals(new BigDecimal("6.000"), buscarQtdEstoque(c, idEmpresaOrigem, idVariacao));
            assertEquals(new BigDecimal("4.000"), buscarQtdEstoque(c, idEmpresaDestino, idVariacao));
        }
    }

    private static void assertEquals(BigDecimal esperado, BigDecimal atual) {
        org.junit.jupiter.api.Assertions.assertEquals(0, esperado.compareTo(atual),
                "esperado " + esperado + " mas era " + atual);
    }

    @Test
    void transferenciaComEstoqueInsuficienteEhAceitaEDeixaSaldoNegativoNaOrigem() throws Exception {
        // Saldo negativo é permitido de propósito em qualquer movimentação (2026-07-29) —
        // não há mais bloqueio de estoque insuficiente na transferência.
        String token = assinarNovoTenant("estoque-insuficiente");
        long idTenant = extrairIdTenant(token);
        long idEmpresaOrigem = extrairIdEmpresa(token);
        long idProduto = criarProduto(token, "Produto Sem Estoque");

        long idVariacao;
        long idEmpresaDestino;
        try (Connection c = abrirConexao(idTenant)) {
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresaOrigem, idVariacao, new BigDecimal("2.000"));
            idEmpresaDestino = criarSegundaEmpresa(c, idTenant);
        }

        String corpo = """
                {"idEmpresaDestino":%d,"itens":[{"idVariacao":%d,"qtd":5}]}
                """.formatted(idEmpresaDestino, idVariacao);

        mvc.perform(post("/api/v1/estoque/transferencias").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated());

        try (Connection c = abrirConexao(idTenant)) {
            assertEquals(new BigDecimal("-3.000"), buscarQtdEstoque(c, idEmpresaOrigem, idVariacao));
            assertEquals(new BigDecimal("5.000"), buscarQtdEstoque(c, idEmpresaDestino, idVariacao));
        }
    }

    @Test
    void transferenciaParaAMesmaEmpresaEhRejeitada() throws Exception {
        String token = assinarNovoTenant("mesma-empresa");
        long idTenant = extrairIdTenant(token);
        long idEmpresaOrigem = extrairIdEmpresa(token);
        long idProduto = criarProduto(token, "Produto Qualquer");

        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresaOrigem, idVariacao, new BigDecimal("10.000"));
        }

        String corpo = """
                {"idEmpresaDestino":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                """.formatted(idEmpresaOrigem, idVariacao);

        mvc.perform(post("/api/v1/estoque/transferencias").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transferenciaParaEmpresaInexistenteEhRejeitada() throws Exception {
        String token = assinarNovoTenant("empresa-inexistente");
        long idTenant = extrairIdTenant(token);
        long idEmpresaOrigem = extrairIdEmpresa(token);
        long idProduto = criarProduto(token, "Produto Qualquer Dois");

        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresaOrigem, idVariacao, new BigDecimal("10.000"));
        }

        String corpo = """
                {"idEmpresaDestino":999999,"itens":[{"idVariacao":%d,"qtd":1}]}
                """.formatted(idVariacao);

        mvc.perform(post("/api/v1/estoque/transferencias").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listagemEBuscaPorIdRetornamATransferenciaCriada() throws Exception {
        String token = assinarNovoTenant("listagem");
        long idTenant = extrairIdTenant(token);
        long idEmpresaOrigem = extrairIdEmpresa(token);
        long idProduto = criarProduto(token, "Produto Listado");

        long idVariacao;
        long idEmpresaDestino;
        try (Connection c = abrirConexao(idTenant)) {
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresaOrigem, idVariacao, new BigDecimal("10.000"));
            idEmpresaDestino = criarSegundaEmpresa(c, idTenant);
        }

        String corpo = """
                {"idEmpresaDestino":%d,"itens":[{"idVariacao":%d,"qtd":3}]}
                """.formatted(idEmpresaDestino, idVariacao);
        String resp = mvc.perform(post("/api/v1/estoque/transferencias").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idTransferencia = ((Number) JsonPath.read(resp, "$.idTransferencia")).longValue();

        mvc.perform(get("/api/v1/estoque/transferencias").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].idTransferencia").value(idTransferencia));

        mvc.perform(get("/api/v1/estoque/transferencias/" + idTransferencia).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].descricaoProduto").value("PRODUTO LISTADO"))
                .andExpect(jsonPath("$.itens[0].qtd").value(3));
    }

    @Test
    void transferenciaDeOutroTenantNaoApareceNaBusca() throws Exception {
        String tokenA = assinarNovoTenant("tenant-a");
        long idTenantA = extrairIdTenant(tokenA);
        long idEmpresaOrigemA = extrairIdEmpresa(tokenA);
        long idProdutoA = criarProduto(tokenA, "Produto Tenant A");

        long idVariacaoA;
        long idEmpresaDestinoA;
        try (Connection c = abrirConexao(idTenantA)) {
            idVariacaoA = criarVariacao(c, idTenantA, idProdutoA);
            definirEstoque(c, idTenantA, idEmpresaOrigemA, idVariacaoA, new BigDecimal("10.000"));
            idEmpresaDestinoA = criarSegundaEmpresa(c, idTenantA);
        }
        String corpoA = """
                {"idEmpresaDestino":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                """.formatted(idEmpresaDestinoA, idVariacaoA);
        String respA = mvc.perform(post("/api/v1/estoque/transferencias").header("Authorization", "Bearer " + tokenA)
                        .contentType(APPLICATION_JSON).content(corpoA))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idTransferenciaA = ((Number) JsonPath.read(respA, "$.idTransferencia")).longValue();

        String tokenB = assinarNovoTenant("tenant-b");
        mvc.perform(get("/api/v1/estoque/transferencias/" + idTransferenciaA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
