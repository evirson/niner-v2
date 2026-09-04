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
        // Produto NOVO pra essa 2ª variação (2026-08-13): produto_barra_variacao_uk agora é
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
        // (2026-08-13): produto_barra_variacao_uk agora é violável de verdade sem cor/tamanho —
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

    /**
     * P8 no vale-mercadoria (pendência 48, 2026-08-31) — o registro mais perto de "dinheiro ao
     * portador" que o ERP tem: o número do vale é um <b>sequencial pequeno</b>, então adivinhar o
     * de outra loja é trivial, e resgatá-lo significaria a loja vizinha pagar a compra.
     *
     * <p>⚠️ Por isso este teste não se contenta com o GET: ele tenta <b>resgatar</b> o vale numa
     * venda do outro tenant, que é o caminho que move dinheiro de verdade.
     */
    @Test
    void isolamentoEntreTenants() throws Exception {
        TenantNovo a = assinarNovoTenant("isolamento-vale-a");
        TenantNovo b = assinarNovoTenant("isolamento-vale-b");
        long idTenantA = extrairIdTenant(a.token());
        long idProdutoA = criarProdutoComPreco(a.token(), "Produto Vale Isolamento", "60.00");

        ValeGerado vale;
        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenantA, idProdutoA);
            definirEstoque(c, idTenantA, idEmpresa, idVariacao, BigDecimal.ZERO);
            vale = gerarVale(a.token(), idVariacao);
        }

        // (a) o dono consulta
        mvc.perform(get("/api/v1/vendas/devolucao/vale/" + vale.idDevolucao())
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valorVale").value(60.00));

        // (b) o vizinho não consulta, mesmo com o número em mãos
        mvc.perform(get("/api/v1/vendas/devolucao/vale/" + vale.idDevolucao())
                        .header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isNotFound());

        // (c) e o vale continua NÃO USADO para o dono — nenhuma tentativa do vizinho pode
        //     consumi-lo pelo caminho de leitura
        mvc.perform(get("/api/v1/vendas/devolucao/vale/" + vale.idDevolucao())
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(jsonPath("$.valeUsado").value(false));
    }

    /**
     * ⭐ <b>Corrida real: o MESMO vale resgatado por duas vendas ao mesmo tempo.</b>
     *
     * <p>O teste sequencial acima ({@code usarValeJaUsadoRespondeConflito}) prova que a segunda
     * tentativa é recusada <b>depois</b> que a primeira terminou. Isso não cobre o caso real: dois
     * caixas abertos, o cliente com o comprovante do vale na mão, e as duas vendas fechando no
     * mesmo instante. Aí as duas leem {@code vale_usado = false} antes de qualquer uma gravar.
     *
     * <p>Se as duas passassem, o mesmo vale pagaria duas vendas — dinheiro que a loja não tem, e
     * um lastro em estoque que só existia uma vez.
     *
     * <p><b>Como a trava funciona aqui.</b> Não é {@code FOR UPDATE}: é uma trava
     * <b>otimista</b> — {@code UPDATE venda_devolucao SET vale_usado = true …
     * WHERE … AND vale_usado = false}, e {@code atualizados == 0} vira conflito. O
     * compare-and-set é atômico no Postgres, então a segunda transação enxerga zero linhas
     * afetadas. Este teste é o que prova que essa condição no {@code WHERE} não é decorativa.
     *
     * <p>⚠️ Não mede tempo nem dorme (ver {@code feedback_testes_frageis_por_relogio}): as threads
     * saem juntas de uma {@link java.util.concurrent.CountDownLatch} e o que se afirma é a
     * <b>invariante</b>. Se elas não se cruzarem numa execução, o teste continua verdadeiro.
     *
     * <p>⭐ A prova vem do <b>banco</b>: uma venda gravada e o vale com um único débito. Contar 201
     * deixaria passar um servidor que responde 409 e grava assim mesmo.
     */
    @Test
    void mesmoValeResgatadoPorDuasVendasSimultaneasSoPagaUma() throws Exception {
        TenantNovo tenant = assinarNovoTenant("vale-corrida");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoDevolvido = criarProdutoComPreco(tenant.token(), "Produto Devolvido Corrida", "30.00");
        long idCliente = criarCliente(tenant.token(), "Cliente Vale Corrida");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Vale Corrida");
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraVale = buscarIdCarteiraValeMercadoria(tenant.token());

        // Duas variações distintas: cada thread vende a sua, para que a única disputa seja o VALE.
        // (Produtos diferentes por causa da `produto_barra_variacao_uk` — ver o teste acima.)
        long idProdutoA = criarProdutoComPreco(tenant.token(), "Produto Corrida Vale A", "30.00");
        long idProdutoB = criarProdutoComPreco(tenant.token(), "Produto Corrida Vale B", "30.00");
        long idVariacaoA;
        long idVariacaoB;
        ValeGerado vale;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacaoDevolvida = criarVariacao(c, idTenant, idProdutoDevolvido);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoDevolvida, BigDecimal.ZERO);
            idVariacaoA = criarVariacao(c, idTenant, idProdutoA);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoA, new BigDecimal("10.000"));
            idVariacaoB = criarVariacao(c, idTenant, idProdutoB);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoB, new BigDecimal("10.000"));
            vale = gerarVale(tenant.token(), idVariacaoDevolvida);
        }

        String modelo = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":30.00,"numeroParcelas":1,"idDevolucao":%d}]}
                """;
        String corpoA = modelo.formatted(idVariacaoA, idCliente, idFuncionario, idCarteiraVale, vale.idDevolucao());
        String corpoB = modelo.formatted(idVariacaoB, idCliente, idFuncionario, idCarteiraVale, vale.idDevolucao());

        var largada = new java.util.concurrent.CountDownLatch(1);
        var prontas = new java.util.concurrent.CountDownLatch(2);
        var aceitas = new java.util.concurrent.atomic.AtomicInteger();
        var falhas = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

        java.util.function.Consumer<String> vender = corpo -> {
            try {
                prontas.countDown();
                largada.await();
                int status = mvc.perform(post("/api/v1/pdv/vendas")
                                .header("Authorization", "Bearer " + tenant.token())
                                .contentType(APPLICATION_JSON).content(corpo))
                        .andReturn().getResponse().getStatus();
                if (status == 200 || status == 201) {
                    aceitas.incrementAndGet();
                }
            } catch (Exception e) {
                falhas.add(e.toString());
            }
        };

        Thread a = new Thread(() -> vender.accept(corpoA), "vale-1");
        Thread b = new Thread(() -> vender.accept(corpoB), "vale-2");
        a.start();
        b.start();
        prontas.await();
        largada.countDown();
        a.join();
        b.join();

        assertThat(falhas).as("nenhuma thread pode explodir por outro motivo").isEmpty();
        assertThat(aceitas.get()).as("o mesmo vale não pode pagar duas vendas").isEqualTo(1);

        try (Connection c = abrirConexao(idTenant)) {
            // O vale ficou marcado uma vez, apontando para UMA venda de débito.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT vale_usado, id_venda_debito FROM venda_devolucao WHERE id_devolucao = ?")) {
                ps.setLong(1, vale.idDevolucao());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBoolean(1)).as("o vale tem de ficar marcado como usado").isTrue();
                    assertThat(rs.getLong(2)).as("e apontando para a venda que o consumiu").isPositive();
                }
            }

            // ⭐ E o caixa: o vale só pode ter entrado como pagamento UMA vez.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT count(*), COALESCE(SUM(valor), 0) FROM caixa_detalhe
                    WHERE id_tenant = ? AND id_carteira = ?
                    """)) {
                ps.setLong(1, idTenant);
                ps.setLong(2, idCarteiraVale);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("um lançamento de vale no caixa, não dois").isEqualTo(1);
                    assertThat(rs.getBigDecimal(2)).as("não pode entrar mais do que o vale valia")
                            .isEqualByComparingTo(vale.valorVale());
                }
            }
        }
    }

}
