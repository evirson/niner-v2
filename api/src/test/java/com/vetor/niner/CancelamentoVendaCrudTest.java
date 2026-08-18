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
 * Cancelamento de Venda (docs/telas/cancelamento-venda.md) — ADMIN-only (RN-04/RN-01),
 * bloqueio definitivo de crediário com parcela recebida (RN-03, checado antes do caixa), caixa
 * de hoje aberto obrigatório (RN-02, simplificado), reversão completa de estoque/caixa/contas a
 * receber numa única transação. Vendas são geradas via o endpoint real do PDV (não inseridas
 * direto via SQL) pra exercitar o ledger de verdade.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CancelamentoVendaCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Cancelamento %s","email":"dono%s@lojacancelamento.com",
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

    private String criarOperadorEFazerLogin(String tokenAdmin, String slug, String sufixo) throws Exception {
        long idEmpresa = buscarPrimeiraEmpresa(tokenAdmin);
        String email = "operador%s@lojacancelamento.com".formatted(sufixo);
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nome":"Operador %s","email":"%s","senha":"senha1234",
                                 "administrador":false,"idsEmpresa":[%d]}
                                """.formatted(sufixo, email, idEmpresa)))
                .andExpect(status().isCreated());
        String login = """
                {"slug":"%s","email":"%s","senha":"senha1234"}
                """.formatted(slug, email);
        String resp = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private long criarProduto(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"50.00"}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarTipoCarteira(String token, String nome, String categoria) throws Exception {
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"%s","categoriaCarteira":"%s","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"permiteReceberCrediario":true}
                                """.formatted(nome, categoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCarteira")).longValue();
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
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        return carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
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

    /** Fecha o caixa de hoje "às cegas" contando exatamente o valor esperado de cada carteira
     *  com movimento — usado só pra chegar num caixa fechado nos testes, não testa a divergência
     *  em si (isso é coberto em {@code FechamentoCaixaCrudTest}). */
    private void fecharCaixaHoje(String token) throws Exception {
        String statusResp = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        long idCaixa = ((Number) JsonPath.read(statusResp, "$.idCaixa")).longValue();

        String fechamentoResp = mvc.perform(get("/api/v1/caixa/fechamento/" + idCaixa).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> linhas = JsonPath.read(fechamentoResp, "$.linhas");

        StringBuilder valoresContados = new StringBuilder();
        for (java.util.Map<String, Object> linha : linhas) {
            if (valoresContados.length() > 0) valoresContados.append(",");
            valoresContados.append("{\"idCarteira\":%d,\"valorContado\":%s}"
                    .formatted(((Number) linha.get("idCarteira")).longValue(), linha.get("valorEsperado")));
        }

        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCaixa\":%d,\"valoresContados\":[%s],\"forcarComDivergencia\":false}".formatted(idCaixa, valoresContados)))
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

    private BigDecimal buscarQtdEstoque(Connection c, long idVariacao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT qtd_estoque FROM produto_estoque WHERE id_variacao = ?")) {
            ps.setLong(1, idVariacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** Efetiva uma venda de 1 unidade via PDV de verdade (não SQL bruto) — devolve o id_venda. */
    private long efetivarVenda(String token, long idVariacao, long idCliente, long idFuncionario,
                                long idCarteira, String valorPago, int numeroParcelas) throws Exception {
        String corpo = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":%d}]}
                """.formatted(idVariacao, idCliente, idFuncionario, idCarteira, valorPago, numeroParcelas);
        String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
    }

    private void receberParcela(String token, long idCliente, long idContaReceber, long idCarteiraPagamento) throws Exception {
        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":50.00}]}
                                """.formatted(idCliente, idContaReceber, idCarteiraPagamento)))
                .andExpect(status().isOk());
    }

    private long buscarIdContaReceber(Connection c, long idVenda) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id_conta_receber FROM contas_receber WHERE id_venda = ?")) {
            ps.setLong(1, idVenda);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private int contarLinhas(Connection c, String sql, long param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void operadorNaoConsegueListarNemCancelar() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-bloqueado");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "operador-bloqueado");

        mvc.perform(get("/api/v1/vendas/cancelamento")
                        .header("Authorization", "Bearer " + tokenOperador)
                        .param("dataInicial", "2020-01-01").param("dataFinal", "2030-01-01"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/vendas/cancelamento/1")
                        .header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"teste\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelarVendaAVistaComSucessoRevertaEstoqueCaixaEContasReceber() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sucesso-avista");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Cancelamento Sucesso");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO CANCELAMENTO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Cancelamento Sucesso");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Cancelamento Sucesso");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
            assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("9.000");
            assertThat(contarLinhas(c, "SELECT count(*) FROM caixa_detalhe WHERE id_venda = ?", idVenda)).isEqualTo(1);
            assertThat(contarLinhas(c, "SELECT count(*) FROM contas_receber WHERE id_venda = ?", idVenda)).isEqualTo(1);
        }

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"Cliente desistiu da compra\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idVenda").value(idVenda));

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("10.000");
            assertThat(contarLinhas(c, "SELECT count(*) FROM caixa_detalhe WHERE id_venda = ?", idVenda)).isZero();
            assertThat(contarLinhas(c, "SELECT count(*) FROM contas_receber WHERE id_venda = ?", idVenda)).isZero();
            assertThat(contarLinhas(c,
                    "SELECT count(*) FROM produto_movimento_mestre WHERE id_venda = ? AND tipo_movimento = 'CANCELAMENTO'",
                    idVenda)).isEqualTo(1);
        }

        mvc.perform(get("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelada").value(true))
                .andExpect(jsonPath("$.motivoCancelamento").value("Cliente desistiu da compra"));
    }

    @Test
    void cancelarVendaJaCanceladaRespondeConflito() throws Exception {
        TenantNovo tenant = assinarNovoTenant("ja-cancelada");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Ja Cancelada");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO JA CANCELADA", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Ja Cancelada");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Ja Cancelada");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        String corpo = "{\"motivo\":\"motivo qualquer\"}";
        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelarVendaComCaixaDeHojeFechadoRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("caixa-fechado");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Caixa Fechado");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO CAIXA FECHADO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Caixa Fechado");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Caixa Fechado");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        fecharCaixaHoje(tenant.token());

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"motivo qualquer\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelarVendaCrediarioComParcelaRecebidaRespondeConflitoDefinitivoENaoRevertaNada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("crediario-recebido");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Crediario Recebido");
        long idCarteiraCrediario = criarTipoCarteira(tenant.token(), "CREDIARIO RECEBIDO", "CREDIARIO");
        long idCarteiraPagamento = buscarIdCarteiraDinheiro(tenant.token());
        long idCliente = criarCliente(tenant.token(), "Cliente Crediario Recebido");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Crediario Recebido");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteiraCrediario, "50.00", 1);

            long idContaReceber = buscarIdContaReceber(c, idVenda);
            receberParcela(tenant.token(), idCliente, idContaReceber, idCarteiraPagamento);
        }

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"tentativa bloqueada\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("crediário")));

        try (Connection c = abrirConexao(idTenant)) {
            // Nada foi revertido — a validação barrou antes de qualquer mudança.
            assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("4.000");
            assertThat(contarLinhas(c, "SELECT count(*) FROM contas_receber WHERE id_venda = ?", idVenda)).isEqualTo(1);
        }
    }

    @Test
    void cancelarVendaCrediarioSemParcelaRecebidaPodeSerCancelada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("crediario-aberto");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Crediario Aberto");
        long idCarteiraCrediario = criarTipoCarteira(tenant.token(), "CREDIARIO ABERTO", "CREDIARIO");
        long idCliente = criarCliente(tenant.token(), "Cliente Crediario Aberto");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Crediario Aberto");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteiraCrediario, "50.00", 1);
        }

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"crediario sem recebimento\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void cancelarVendaInexistenteRespondeNaoEncontrada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("inexistente");
        mvc.perform(post("/api/v1/vendas/cancelamento/999999").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"qualquer\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void motivoEmBrancoEhRejeitado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("motivo-em-branco");
        mvc.perform(post("/api/v1/vendas/cancelamento/1").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buscarPorNumeroDaVendaIgnoraDemaisFiltrosEEncontraForaDoIntervalo() throws Exception {
        TenantNovo tenant = assinarNovoTenant("busca-numero");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Busca Numero");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO BUSCA NUMERO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Busca Numero");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Busca Numero");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        // Intervalo de data de 2020 não bateria com a venda de hoje — mas numeroVenda ignora isso.
        mvc.perform(get("/api/v1/vendas/cancelamento").header("Authorization", "Bearer " + tenant.token())
                        .param("numeroVenda", String.valueOf(idVenda))
                        .param("dataInicial", "2020-01-01").param("dataFinal", "2020-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].idVenda").value(idVenda));
    }

    @Test
    void vendaDeUmTenantNaoApareceNemPodeSerCanceladaPorOutro() throws Exception {
        TenantNovo tenantA = assinarNovoTenant("isolamento-a");
        TenantNovo tenantB = assinarNovoTenant("isolamento-b");
        long idTenantA = extrairIdTenant(tenantA.token());
        long idProduto = criarProduto(tenantA.token(), "Produto Isolamento");
        long idCarteira = criarTipoCarteira(tenantA.token(), "DINHEIRO ISOLAMENTO", "AVISTA");
        long idCliente = criarCliente(tenantA.token(), "Cliente Isolamento");
        long idFuncionario = criarFuncionario(tenantA.token(), "Vendedor Isolamento");
        abrirCaixaDinheiro(tenantA.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenantA, idProduto);
            definirEstoque(c, idTenantA, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenantA.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenantB.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"tentativa de outro tenant\"}"))
                .andExpect(status().isNotFound());
    }
}
