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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resgate de vale-mercadoria como forma de pagamento no PDV (2026-08-03) — o vale é emitido pela
 * Devolução de Produtos ({@code venda_devolucao}, categoria de carteira {@code VALE_MERCADORIA},
 * já seedada por tenant no signup). Paga na hora (mesmo tratamento de AVISTA), exige o número do
 * vale, bloqueia reutilização e bloqueia vale maior que o saldo a pagar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ValeMercadoriaCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Vale %s","email":"dono%s@lojavale.com",
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

    private long criarProdutoComPreco(String token, String descricao, String precoVenda) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"%s"}
                                """.formatted(descricao, precoVenda)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarCategoriaCliente(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoriaCliente")).longValue();
    }

    private long criarCliente(String token, String nome) throws Exception {
        long idCategoria = criarCategoriaCliente(token, "PADRAO " + nome);
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":false,"nome":"%s","idCategoriaCliente":%d}
                                """.formatted(nome, idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    private long criarFuncionario(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();
    }

    private long buscarIdCarteiraDinheiro(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        return carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
    }

    /** "VALE MERCADORIA" já nasce seedada por tenant no signup (categoria VALE_MERCADORIA). */
    private long buscarIdCarteiraValeMercadoria(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .param("limite", "100"))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> itens = JsonPath.read(resp, "$.itens");
        return itens.stream()
                .filter(c -> "VALE_MERCADORIA".equals(c.get("categoriaCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
    }

    private void abrirCaixaDinheiro(String token) throws Exception {
        long idCarteira = buscarIdCarteiraDinheiro(token);
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteira)))
                .andExpect(status().isOk());
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

    /** Devolve 1 unidade de {@code idVariacao} via o endpoint real de devolução — devolve o
     *  {@code idDevolucao} (número do vale) e o {@code valorVale}. */
    private record ValeGerado(long idDevolucao, BigDecimal valorVale) {
    }

    private ValeGerado gerarVale(String token, long idVariacao) throws Exception {
        String corpo = """
                {"numeroVenda":null,"itens":[{"idVariacao":%d,"qtd":1}]}
                """.formatted(idVariacao);
        String resp = mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new ValeGerado(
                ((Number) JsonPath.read(resp, "$.idDevolucao")).longValue(),
                new BigDecimal(JsonPath.read(resp, "$.valorVale").toString()));
    }

    @Test
    void pagarComValeMercadoriaEmSplitTenderQuitaNaHoraELancaNoCaixaEMarcaValeUsado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("split-tender");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoDevolvido = criarProdutoComPreco(tenant.token(), "Produto Devolvido Vale", "50.00");
        long idProdutoVenda = criarProdutoComPreco(tenant.token(), "Produto Vendido Com Vale", "100.00");
        long idCliente = criarCliente(tenant.token(), "Cliente Vale Split");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Vale Split");
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraDinheiro = buscarIdCarteiraDinheiro(tenant.token());
        long idCarteiraVale = buscarIdCarteiraValeMercadoria(tenant.token());

        long idVariacaoVenda;
        ValeGerado vale;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacaoDevolvida = criarVariacao(c, idTenant, idProdutoDevolvido);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoDevolvida, BigDecimal.ZERO);
            idVariacaoVenda = criarVariacao(c, idTenant, idProdutoVenda);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoVenda, new BigDecimal("10.000"));

            vale = gerarVale(tenant.token(), idVariacaoDevolvida);
            assertThat(vale.valorVale()).isEqualByComparingTo("50.00");
        }

        // Venda de R$100 (2 unidades a R$50... na verdade 1 unidade a R$100) paga metade com o
        // vale (R$50) e metade em dinheiro (R$50) — split-tender de verdade.
        String corpoVenda = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[
                   {"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1,"idDevolucao":%d},
                   {"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}
                 ]}
                """.formatted(idVariacaoVenda, idCliente, idFuncionario, idCarteiraVale, vale.idDevolucao(), idCarteiraDinheiro);
        String respVenda = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoVenda))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idVenda = ((Number) JsonPath.read(respVenda, "$.idVenda")).longValue();

        try (Connection c = abrirConexao(idTenant)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT vale_usado, id_venda_debito FROM venda_devolucao WHERE id_devolucao = ?")) {
                ps.setLong(1, vale.idDevolucao());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBoolean("vale_usado")).isTrue();
                    assertThat(rs.getLong("id_venda_debito")).isEqualTo(idVenda);
                }
            }

            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT valor, credito_debito FROM caixa_detalhe WHERE id_venda = ? AND id_carteira = ?
                    """)) {
                ps.setLong(1, idVenda);
                ps.setLong(2, idCarteiraVale);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBigDecimal("valor")).isEqualByComparingTo("50.00");
                    assertThat(rs.getString("credito_debito")).isEqualTo("C");
                }
            }

            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT valor_recebido, data_recebimento FROM contas_receber WHERE id_venda = ? AND id_carteira = ?
                    """)) {
                ps.setLong(1, idVenda);
                ps.setLong(2, idCarteiraVale);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBigDecimal("valor_recebido")).isEqualByComparingTo("50.00");
                    assertThat(rs.getObject("data_recebimento")).isNotNull();
                }
            }
        }
    }

    @Test
    void usarValeJaUsadoRespondeConflito() throws Exception {
        TenantNovo tenant = assinarNovoTenant("vale-reusado");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoDevolvido = criarProdutoComPreco(tenant.token(), "Produto Devolvido Reuso", "30.00");
        long idProdutoVenda = criarProdutoComPreco(tenant.token(), "Produto Vendido Reuso", "30.00");
        long idCliente = criarCliente(tenant.token(), "Cliente Vale Reuso");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Vale Reuso");
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraVale = buscarIdCarteiraValeMercadoria(tenant.token());

        long idVariacaoVenda;
        ValeGerado vale;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacaoDevolvida = criarVariacao(c, idTenant, idProdutoDevolvido);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoDevolvida, BigDecimal.ZERO);
            idVariacaoVenda = criarVariacao(c, idTenant, idProdutoVenda);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoVenda, new BigDecimal("10.000"));
            vale = gerarVale(tenant.token(), idVariacaoDevolvida);
        }

        String corpoVenda = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":30.00,"numeroParcelas":1,"idDevolucao":%d}]}
                """.formatted(idVariacaoVenda, idCliente, idFuncionario, idCarteiraVale, vale.idDevolucao());

        mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoVenda))
                .andExpect(status().isCreated());

        // Segunda tentativa de usar o mesmo vale — precisa de outra venda igual pra tentar de novo.
        // Produto NOVO pra essa 2ª variação (2026-08-20): produto_barra_variacao_uk agora é
        // violável de verdade sem cor/tamanho — id_cor/id_tamanho gravam 1 (PADRÃO) em vez de
        // NULL, então 2 variações "sem variação" do MESMO produto colidiriam.
        long idProdutoVenda2 = criarProdutoComPreco(tenant.token(), "Produto Vendido Reuso 2", "30.00");
        long idVariacaoVenda2;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacaoVenda2 = criarVariacao(c, idTenant, idProdutoVenda2);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoVenda2, new BigDecimal("10.000"));
        }
        String corpoVenda2 = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":30.00,"numeroParcelas":1,"idDevolucao":%d}]}
                """.formatted(idVariacaoVenda2, idCliente, idFuncionario, idCarteiraVale, vale.idDevolucao());
        mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoVenda2))
                .andExpect(status().isConflict());
    }

    @Test
    void usarValeMaiorQueSaldoRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("vale-maior");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoDevolvido = criarProdutoComPreco(tenant.token(), "Produto Devolvido Maior", "200.00");
        long idProdutoVenda = criarProdutoComPreco(tenant.token(), "Produto Vendido Maior", "30.00");
        long idCliente = criarCliente(tenant.token(), "Cliente Vale Maior");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Vale Maior");
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraVale = buscarIdCarteiraValeMercadoria(tenant.token());

        long idVariacaoVenda;
        ValeGerado vale;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacaoDevolvida = criarVariacao(c, idTenant, idProdutoDevolvido);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoDevolvida, BigDecimal.ZERO);
            idVariacaoVenda = criarVariacao(c, idTenant, idProdutoVenda);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoVenda, new BigDecimal("10.000"));
            vale = gerarVale(tenant.token(), idVariacaoDevolvida);
            assertThat(vale.valorVale()).isEqualByComparingTo("200.00");
        }

        String corpoVenda = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":200.00,"numeroParcelas":1,"idDevolucao":%d}]}
                """.formatted(idVariacaoVenda, idCliente, idFuncionario, idCarteiraVale, vale.idDevolucao());

        mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoVenda))
                .andExpect(status().isBadRequest());
    }

    @Test
    void usarCategoriaValeMercadoriaSemInformarONumeroDoValeRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("vale-sem-numero");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoVenda = criarProdutoComPreco(tenant.token(), "Produto Vendido Sem Vale", "30.00");
        long idCliente = criarCliente(tenant.token(), "Cliente Vale Sem Numero");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Vale Sem Numero");
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraVale = buscarIdCarteiraValeMercadoria(tenant.token());

        long idVariacaoVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacaoVenda = criarVariacao(c, idTenant, idProdutoVenda);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoVenda, new BigDecimal("10.000"));
        }

        String corpoVenda = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":30.00,"numeroParcelas":1}]}
                """.formatted(idVariacaoVenda, idCliente, idFuncionario, idCarteiraVale);

        mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoVenda))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buscarValePorNumeroRetornaValorEStatus() throws Exception {
        TenantNovo tenant = assinarNovoTenant("vale-consulta");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Vale Consulta", "42.50");

        ValeGerado vale;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, BigDecimal.ZERO);
            vale = gerarVale(tenant.token(), idVariacao);
        }

        mvc.perform(get("/api/v1/vendas/devolucao/vale/" + vale.idDevolucao())
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valorVale").value(42.50))
                .andExpect(jsonPath("$.valeUsado").value(false));
    }

    @Test
    void cancelarVendaQuePagouComValeMercadoriaReabreOValeParaUsoFuturo() throws Exception {
        TenantNovo tenant = assinarNovoTenant("vale-cancelamento");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoDevolvido = criarProdutoComPreco(tenant.token(), "Produto Devolvido Cancelamento", "80.00");
        long idProdutoVenda = criarProdutoComPreco(tenant.token(), "Produto Vendido Cancelamento", "80.00");
        long idCliente = criarCliente(tenant.token(), "Cliente Vale Cancelamento");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Vale Cancelamento");
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraVale = buscarIdCarteiraValeMercadoria(tenant.token());

        long idVariacaoVenda;
        ValeGerado vale;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacaoDevolvida = criarVariacao(c, idTenant, idProdutoDevolvido);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoDevolvida, BigDecimal.ZERO);
            idVariacaoVenda = criarVariacao(c, idTenant, idProdutoVenda);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoVenda, new BigDecimal("10.000"));
            vale = gerarVale(tenant.token(), idVariacaoDevolvida);
        }

        String corpoVenda = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":80.00,"numeroParcelas":1,"idDevolucao":%d}]}
                """.formatted(idVariacaoVenda, idCliente, idFuncionario, idCarteiraVale, vale.idDevolucao());
        String respVenda = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoVenda))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idVenda = ((Number) JsonPath.read(respVenda, "$.idVenda")).longValue();

        try (Connection c = abrirConexao(idTenant)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT vale_usado, id_venda_debito FROM venda_devolucao WHERE id_devolucao = ?")) {
                ps.setLong(1, vale.idDevolucao());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBoolean("vale_usado")).isTrue();
                    assertThat(rs.getLong("id_venda_debito")).isEqualTo(idVenda);
                }
            }
        }

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"Cliente desistiu\"}"))
                .andExpect(status().isOk());

        try (Connection c = abrirConexao(idTenant)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT vale_usado, id_venda_debito FROM venda_devolucao WHERE id_devolucao = ?")) {
                ps.setLong(1, vale.idDevolucao());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBoolean("vale_usado")).isFalse();
                    rs.getLong("id_venda_debito");
                    assertThat(rs.wasNull()).isTrue();
                }
            }
        }

        // O vale reaberto pode ser usado numa nova venda. Produto NOVO pra essa 2ª variação
        // (2026-08-20): produto_barra_variacao_uk agora é violável de verdade sem cor/tamanho —
        // id_cor/id_tamanho gravam 1 (PADRÃO) em vez de NULL, então 2 variações "sem variação"
        // do MESMO produto colidiriam.
        long idProdutoVenda2 = criarProdutoComPreco(tenant.token(), "Produto Vendido Cancelamento 2", "80.00");
        long idVariacaoVenda2;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacaoVenda2 = criarVariacao(c, idTenant, idProdutoVenda2);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoVenda2, new BigDecimal("10.000"));
        }
        String corpoVenda2 = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":80.00,"numeroParcelas":1,"idDevolucao":%d}]}
                """.formatted(idVariacaoVenda2, idCliente, idFuncionario, idCarteiraVale, vale.idDevolucao());
        mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoVenda2))
                .andExpect(status().isCreated());
    }
}
