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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Histórico do cliente (2026-07-23): compras (venda física), parcelas (contas a receber) e
 * resumo das parcelas de crediário em aberto. Não existe ainda fluxo de lançamento de
 * venda/baixa de parcela nem cadastro de variação de produto (produto_barra) — os testes
 * gravam essas linhas direto via JDBC (mesmo padrão de {@code criarVendaParaCliente} em
 * {@link ClienteCrudTest}), como niner_app com {@code app.id_tenant} setado (RLS continua valendo).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ClienteHistoricoCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Historico %s","email":"dono%s@lojahistorico.com",
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

    private long criarCategoriaCliente(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoriaCliente")).longValue();
    }

    private long criarCliente(String token, long idCategoria, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":false,"nome":"%s","idCategoriaCliente":%d}
                                """.formatted(nome, idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
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

    private long criarTipoCarteira(String token, String nome, String categoria) throws Exception {
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"%s","categoriaCarteira":"%s","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":0,"permiteReceberCrediario":false}
                                """.formatted(nome, categoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCarteira")).longValue();
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

    private long criarVariacaoComVariante(Connection c, long idTenant, long idProduto,
                                           long idVarianteLinha, long idVarianteColuna) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_barra (id_tenant, id_produto, id_variante_linha, id_variante_coluna, sku) "
                        + "VALUES (?, ?, ?, ?, gerar_ean13_interno()) RETURNING id_variacao")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            ps.setLong(3, idVarianteLinha);
            ps.setLong(4, idVarianteColuna);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long criarVenda(Connection c, long idTenant, long idEmpresa, long idCliente, OffsetDateTime dataVenda)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO venda (id_tenant, id_empresa, id_cliente, data_venda) VALUES (?, ?, ?, ?) "
                        + "RETURNING id_venda")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idCliente);
            ps.setObject(4, dataVenda);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Grava uma linha de ledger de venda (débito de estoque) — é dela que o "valor" da compra é somado. */
    private void criarMovimentoVenda(Connection c, long idTenant, long idEmpresa, long idVenda, long idVariacao,
                                      BigDecimal qtd, BigDecimal precoVenda, BigDecimal desconto, BigDecimal acrescimo)
            throws SQLException {
        long idMovimento;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_venda) "
                        + "VALUES (?, ?, 'VENDA', ?) RETURNING id_movimento")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idVenda);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                idMovimento = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_movimento_detalhe
                    (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito,
                     qtd_produto, preco_venda, valor_desconto, valor_acrescimo)
                VALUES (?, ?, ?, ?, 'D', ?, ?, ?, ?)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idMovimento);
            ps.setLong(3, idEmpresa);
            ps.setLong(4, idVariacao);
            ps.setBigDecimal(5, qtd);
            ps.setBigDecimal(6, precoVenda);
            ps.setBigDecimal(7, desconto);
            ps.setBigDecimal(8, acrescimo);
            ps.executeUpdate();
        }
    }

    private void criarContaReceber(Connection c, long idTenant, long idVenda, long idCarteira,
                                    OffsetDateTime vencimento, OffsetDateTime recebimento,
                                    BigDecimal valorReceber, BigDecimal juros, BigDecimal recebido,
                                    Long idEmpresaPagamento) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO contas_receber
                    (id_tenant, id_venda, id_carteira, numero_parcela, data_vencimento, data_recebimento,
                     valor_receber, valor_juros, valor_recebido, id_empresa_pagamento)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idVenda);
            ps.setLong(3, idCarteira);
            ps.setObject(4, vencimento);
            ps.setObject(5, recebimento);
            ps.setBigDecimal(6, valorReceber);
            ps.setBigDecimal(7, juros);
            ps.setBigDecimal(8, recebido);
            if (idEmpresaPagamento != null) {
                ps.setLong(9, idEmpresaPagamento);
            } else {
                ps.setNull(9, java.sql.Types.INTEGER);
            }
            ps.executeUpdate();
        }
    }

    @Test
    void historicoDeComprasSomaOValorDoLedgerDeEstoque() throws Exception {
        String token = assinarNovoTenant("compras");
        long idTenant = extrairIdTenant(token);
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        long idCliente = criarCliente(token, idCategoria, "Cliente Compras");
        long idProduto = criarProduto(token, "Produto Venda");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            OffsetDateTime dataVenda = OffsetDateTime.now().minusDays(5);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente, dataVenda);
            // 2 unidades a 10,00 - 1,00 desconto + 0,50 acréscimo = 19,50
            criarMovimentoVenda(c, idTenant, idEmpresa, idVenda, idVariacao,
                    new BigDecimal("2.000"), new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("0.50"));

            mvc.perform(get("/api/v1/clientes/" + idCliente + "/historico").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.compras.length()").value(1))
                    .andExpect(jsonPath("$.compras[0].idVenda").value(idVenda))
                    .andExpect(jsonPath("$.compras[0].codigoEmpresa").value(1))
                    .andExpect(jsonPath("$.compras[0].valor").value(19.50))
                    .andExpect(jsonPath("$.compras[0].qtdProdutos").value(2.0));
        }
    }

    @Test
    void historicoDeProdutosCalculaPrecoDeVendaLiquido() throws Exception {
        String token = assinarNovoTenant("produtos");
        long idTenant = extrairIdTenant(token);
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        long idCliente = criarCliente(token, idCategoria, "Cliente Produtos");
        long idProduto = criarProduto(token, "Produto Sem Variação");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente, OffsetDateTime.now().minusDays(2));
            // 2 unidades a 10,00 - 1,00 desconto + 0,50 acréscimo = 19,50 líquido / 2 = 9,75 por unidade.
            criarMovimentoVenda(c, idTenant, idEmpresa, idVenda, idVariacao,
                    new BigDecimal("2.000"), new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("0.50"));

            mvc.perform(get("/api/v1/clientes/" + idCliente + "/historico").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.produtos.length()").value(1))
                    .andExpect(jsonPath("$.produtos[0].idVenda").value(idVenda))
                    .andExpect(jsonPath("$.produtos[0].descricaoProduto").value("PRODUTO SEM VARIAÇÃO"))
                    .andExpect(jsonPath("$.produtos[0].variacaoLinha").doesNotExist())
                    .andExpect(jsonPath("$.produtos[0].variacaoColuna").doesNotExist())
                    .andExpect(jsonPath("$.produtos[0].qtdVendida").value(2.0))
                    .andExpect(jsonPath("$.produtos[0].precoVenda").value(9.75));
        }
    }

    @Test
    void historicoDeProdutosTrazVariacaoDeLinhaEColuna() throws Exception {
        String token = assinarNovoTenant("produtos-variacao");
        long idTenant = extrairIdTenant(token);
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        long idCliente = criarCliente(token, idCategoria, "Cliente Variação");
        long idProduto = criarProduto(token, "Camiseta");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVarianteLinha = criarVarianteLinha(c, idTenant, "TAMANHO");
            long idVarianteColuna = criarVarianteColuna(c, idTenant, "COR");
            long idVariacao = criarVariacaoComVariante(c, idTenant, idProduto, idVarianteLinha, idVarianteColuna);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente, OffsetDateTime.now());
            criarMovimentoVenda(c, idTenant, idEmpresa, idVenda, idVariacao,
                    new BigDecimal("1.000"), new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO);

            mvc.perform(get("/api/v1/clientes/" + idCliente + "/historico").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.produtos[0].variacaoLinha").value("TAMANHO"))
                    .andExpect(jsonPath("$.produtos[0].variacaoColuna").value("COR"))
                    .andExpect(jsonPath("$.produtos[0].precoVenda").value(50.00));
        }
    }

    @Test
    void historicoDeParcelasCalculaValorAPagarEDiasDeAtraso() throws Exception {
        String token = assinarNovoTenant("parcelas");
        long idTenant = extrairIdTenant(token);
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        long idCliente = criarCliente(token, idCategoria, "Cliente Parcelas");
        long idCarteira = criarTipoCarteira(token, "CREDIARIO 30 DIAS", "CREDIARIO");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente, OffsetDateTime.now().minusDays(20));

            // Paga 3 dias depois do vencimento (venceu há 10 dias, pagou há 7).
            criarContaReceber(c, idTenant, idVenda, idCarteira,
                    OffsetDateTime.now().minusDays(10), OffsetDateTime.now().minusDays(7),
                    new BigDecimal("100.00"), new BigDecimal("5.00"), new BigDecimal("105.00"), idEmpresa);

            mvc.perform(get("/api/v1/clientes/" + idCliente + "/historico").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.parcelas.length()").value(1))
                    .andExpect(jsonPath("$.parcelas[0].idVenda").value(idVenda))
                    .andExpect(jsonPath("$.parcelas[0].numeroParcela").value(1))
                    .andExpect(jsonPath("$.parcelas[0].valorAPagar").value(105.00))
                    .andExpect(jsonPath("$.parcelas[0].valorPago").value(105.00))
                    .andExpect(jsonPath("$.parcelas[0].codigoEmpresaPagamento").value(1))
                    .andExpect(jsonPath("$.parcelas[0].diasAtraso").value(3))
                    .andExpect(jsonPath("$.parcelas[0].categoriaCarteira").value("CREDIARIO"));
        }
    }

    @Test
    void parcelaNaoVencidaNaoTemDiasDeAtrasoNemEmpresaDePagamento() throws Exception {
        String token = assinarNovoTenant("parcela-em-dia");
        long idTenant = extrairIdTenant(token);
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        long idCliente = criarCliente(token, idCategoria, "Cliente Em Dia");
        long idCarteira = criarTipoCarteira(token, "CREDIARIO EM DIA", "CREDIARIO");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente, OffsetDateTime.now());

            criarContaReceber(c, idTenant, idVenda, idCarteira,
                    OffsetDateTime.now().plusDays(15), null,
                    new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO, null);

            mvc.perform(get("/api/v1/clientes/" + idCliente + "/historico").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.parcelas[0].diasAtraso").doesNotExist())
                    .andExpect(jsonPath("$.parcelas[0].codigoEmpresaPagamento").doesNotExist());
        }
    }

    @Test
    void resumoCrediarioSomaSoParcelasEmAbertoDeCategoriaCrediario() throws Exception {
        String token = assinarNovoTenant("resumo");
        long idTenant = extrairIdTenant(token);
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        long idCliente = criarCliente(token, idCategoria, "Cliente Resumo");
        long idCrediario = criarTipoCarteira(token, "CREDIARIO RESUMO", "CREDIARIO");
        long idCartao = criarTipoCarteira(token, "CARTAO RESUMO", "CARTAO_CREDITO");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente, OffsetDateTime.now().minusDays(40));

            // Crediário vencida, em aberto: conta pro resumo.
            criarContaReceber(c, idTenant, idVenda, idCrediario,
                    OffsetDateTime.now().minusDays(10), null,
                    new BigDecimal("100.00"), new BigDecimal("8.00"), BigDecimal.ZERO, null);
            // Crediário a vencer, em aberto: conta pro resumo.
            criarContaReceber(c, idTenant, idVenda, idCrediario,
                    OffsetDateTime.now().plusDays(10), null,
                    new BigDecimal("200.00"), BigDecimal.ZERO, BigDecimal.ZERO, null);
            // Crediário já paga: NÃO conta (mesmo vencida no passado).
            criarContaReceber(c, idTenant, idVenda, idCrediario,
                    OffsetDateTime.now().minusDays(30), OffsetDateTime.now().minusDays(25),
                    new BigDecimal("300.00"), new BigDecimal("20.00"), new BigDecimal("320.00"), idEmpresa);
            // Cartão de crédito vencido, em aberto: NÃO conta (categoria errada).
            criarContaReceber(c, idTenant, idVenda, idCartao,
                    OffsetDateTime.now().minusDays(5), null,
                    new BigDecimal("999.00"), BigDecimal.ZERO, BigDecimal.ZERO, null);

            mvc.perform(get("/api/v1/clientes/" + idCliente + "/historico").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.parcelas.length()").value(4))
                    .andExpect(jsonPath("$.resumoCrediario.vencidas.valorTotal").value(100.00))
                    .andExpect(jsonPath("$.resumoCrediario.vencidas.valorJurosMulta").value(8.00))
                    .andExpect(jsonPath("$.resumoCrediario.vencidas.numeroParcelas").value(1))
                    .andExpect(jsonPath("$.resumoCrediario.aVencer.valorTotal").value(200.00))
                    .andExpect(jsonPath("$.resumoCrediario.aVencer.numeroParcelas").value(1))
                    .andExpect(jsonPath("$.resumoCrediario.total.valorTotal").value(300.00))
                    .andExpect(jsonPath("$.resumoCrediario.total.valorJurosMulta").value(8.00))
                    .andExpect(jsonPath("$.resumoCrediario.total.numeroParcelas").value(2));
        }
    }

    @Test
    void clienteInexistenteRespondeNaoEncontrado() throws Exception {
        String token = assinarNovoTenant("cliente-inexistente");

        mvc.perform(get("/api/v1/clientes/999999/historico").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void historicoDeOutroTenantNaoVaza() throws Exception {
        String tokenA = assinarNovoTenant("tenant-a-hist");
        String tokenB = assinarNovoTenant("tenant-b-hist");
        long idTenantA = extrairIdTenant(tokenA);
        long idCategoriaA = criarCategoriaCliente(tokenA, "Padrão");
        long idClienteA = criarCliente(tokenA, idCategoriaA, "Cliente Tenant A");

        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresa = buscarIdEmpresa(c);
            criarVenda(c, idTenantA, idEmpresa, idClienteA, OffsetDateTime.now());
        }

        mvc.perform(get("/api/v1/clientes/" + idClienteA + "/historico").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
