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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Relatório de Contas a Receber / Recebidas (docs/telas/relatorio-contas-receber.md) — parcelas
 * de CARTAO_DEBITO/CARTAO_CREDITO/CREDIARIO, valor bruto × taxa administrativa (0 pra crediário)
 * = valor líquido, três períodos independentes (pelo menos um obrigatório).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RelatorioContasReceberCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Contas Receber %s","email":"dono%s@lojacontasreceber.com",
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

    private long criarTipoCarteiraComTaxa(String token, String nome, String categoria, String taxaAdministradora) throws Exception {
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"%s","categoriaCarteira":"%s","prazoPagamento":1,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":%s,"permiteReceberCrediario":false}
                                """.formatted(nome, categoria, taxaAdministradora)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCarteira")).longValue();
    }

    private long criarTipoCarteiraCrediario(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"%s","categoriaCarteira":"CREDIARIO","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":6,"permiteReceberCrediario":true}
                                """.formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCarteira")).longValue();
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

    private void abrirCaixaDinheiro(String token) throws Exception {
        long idCarteira = buscarIdCarteiraDinheiro(token);
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteira)))
                .andExpect(status().isOk());
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

    private long efetivarVenda(String token, long idVariacao, long idCliente, long idFuncionario,
                                long idCarteira, String valorPago) throws Exception {
        return efetivarVendaParcelada(token, idVariacao, idCliente, idFuncionario, idCarteira, valorPago, 1);
    }

    private long efetivarVendaParcelada(String token, long idVariacao, long idCliente, long idFuncionario,
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

    private void receberParcela(String token, long idCliente, long idContaReceber, long idCarteiraPagamento, String valor) throws Exception {
        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":%s}]}
                                """.formatted(idCliente, idContaReceber, idCarteiraPagamento, valor)))
                .andExpect(status().isOk());
    }

    private String hojeISO() {
        return java.time.LocalDate.now().toString();
    }

    @Test
    void cartaoDebitoComTaxaCalculaValorLiquidoCorretamente() throws Exception {
        TenantNovo tenant = assinarNovoTenant("debito-taxa");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Debito Taxa", "200.00");
        long idCarteira = criarTipoCarteiraComTaxa(tenant.token(), "HIPER DEBITO TAXA", "CARTAO_DEBITO", "5.00");
        long idCliente = criarCliente(tenant.token(), "Cliente Debito Taxa");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Debito Taxa");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "200.00");
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token())
                        .param("dataVendaInicial", hoje).param("dataVendaFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(1))
                .andExpect(jsonPath("$.linhas[0].categoriaCarteira").value("CARTAO_DEBITO"))
                .andExpect(jsonPath("$.linhas[0].valorBruto").value(200.00))
                .andExpect(jsonPath("$.linhas[0].taxaAdministrativa").value(5.00))
                .andExpect(jsonPath("$.linhas[0].valorLiquido").value(190.00))
                .andExpect(jsonPath("$.totalGeral.valorLiquido").value(190.00));
    }

    @Test
    void crediarioSemTaxaValorLiquidoIgualBruto() throws Exception {
        TenantNovo tenant = assinarNovoTenant("crediario-sem-taxa");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Crediario Sem Taxa", "100.00");
        long idCarteira = criarTipoCarteiraCrediario(tenant.token(), "CREDIARIO SEM TAXA");
        long idCliente = criarCliente(tenant.token(), "Cliente Crediario Sem Taxa");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Crediario Sem Taxa");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "100.00");
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token())
                        .param("dataVendaInicial", hoje).param("dataVendaFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(1))
                .andExpect(jsonPath("$.linhas[0].categoriaCarteira").value("CREDIARIO"))
                .andExpect(jsonPath("$.linhas[0].valorBruto").value(100.00))
                .andExpect(jsonPath("$.linhas[0].taxaAdministrativa").value(0))
                .andExpect(jsonPath("$.linhas[0].valorLiquido").value(100.00));
    }

    @Test
    void filtroPorPeriodoDeRecebimentoTrazSoParcelaRecebidaNaJanela() throws Exception {
        TenantNovo tenant = assinarNovoTenant("filtro-recebimento");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Filtro Recebimento", "120.00");
        long idCarteiraCrediario = criarTipoCarteiraCrediario(tenant.token(), "CREDIARIO FILTRO RECEBIMENTO");
        long idCarteiraDinheiro = buscarIdCarteiraDinheiro(tenant.token());
        long idCliente = criarCliente(tenant.token(), "Cliente Filtro Recebimento");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Filtro Recebimento");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteiraCrediario, "120.00");
        }

        String hoje = hojeISO();
        // Ainda não recebida: não deve aparecer com filtro de período de recebimento hoje.
        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token())
                        .param("dataRecebimentoInicial", hoje).param("dataRecebimentoFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(0));

        try (Connection c = abrirConexao(idTenant)) {
            long idContaReceber = buscarIdContaReceber(c, idVenda);
            receberParcela(tenant.token(), idCliente, idContaReceber, idCarteiraDinheiro, "120.00");
        }

        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token())
                        .param("dataRecebimentoInicial", hoje).param("dataRecebimentoFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(1))
                .andExpect(jsonPath("$.linhas[0].dataRecebimento").exists());
    }

    @Test
    void nenhumPeriodoInformadoRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sem-periodo-cr");
        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void vendaAVistaNaoApareceNoRelatorio() throws Exception {
        TenantNovo tenant = assinarNovoTenant("avista-fora");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Avista Fora", "50.00");
        long idCarteira = buscarIdCarteiraDinheiro(tenant.token());
        long idCliente = criarCliente(tenant.token(), "Cliente Avista Fora");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Avista Fora");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00");
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token())
                        .param("dataVendaInicial", hoje).param("dataVendaFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(0));
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        TenantNovo tenantA = assinarNovoTenant("isolamento-cr-a");
        TenantNovo tenantB = assinarNovoTenant("isolamento-cr-b");
        long idTenantA = extrairIdTenant(tenantA.token());
        long idProduto = criarProdutoComPreco(tenantA.token(), "Produto Isolamento CR", "90.00");
        long idCarteira = criarTipoCarteiraCrediario(tenantA.token(), "CREDIARIO ISOLAMENTO CR");
        long idCliente = criarCliente(tenantA.token(), "Cliente Isolamento CR");
        long idFuncionario = criarFuncionario(tenantA.token(), "Vendedor Isolamento CR");
        abrirCaixaDinheiro(tenantA.token());

        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenantA, idProduto);
            definirEstoque(c, idTenantA, idEmpresa, idVariacao, new BigDecimal("10.000"));
            efetivarVenda(tenantA.token(), idVariacao, idCliente, idFuncionario, idCarteira, "90.00");
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenantB.token())
                        .param("dataVendaInicial", hoje).param("dataVendaFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(0));
    }

    @Test
    void numeroParcelaETotalParcelasCorretosEmVendaParcelada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("parcelas");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Parcelas", "300.00");
        long idCarteira = criarTipoCarteiraCrediario(tenant.token(), "CREDIARIO PARCELAS");
        long idCliente = criarCliente(tenant.token(), "Cliente Parcelas");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Parcelas");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            efetivarVendaParcelada(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "300.00", 3);
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token())
                        .param("dataVendaInicial", hoje).param("dataVendaFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(3))
                .andExpect(jsonPath("$.linhas[0].numeroParcela").value(1))
                .andExpect(jsonPath("$.linhas[0].totalParcelas").value(3))
                .andExpect(jsonPath("$.linhas[2].numeroParcela").value(3))
                .andExpect(jsonPath("$.linhas[2].totalParcelas").value(3));
    }

    @Test
    void filtroDeStatusIsolaAbertasERecebidas() throws Exception {
        TenantNovo tenant = assinarNovoTenant("status-parcela");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoRecebido = criarProdutoComPreco(tenant.token(), "Produto Status Recebido", "80.00");
        long idProdutoAberto = criarProdutoComPreco(tenant.token(), "Produto Status Aberto", "60.00");
        long idCarteiraCrediario = criarTipoCarteiraCrediario(tenant.token(), "CREDIARIO STATUS PARCELA");
        long idCarteiraDinheiro = buscarIdCarteiraDinheiro(tenant.token());
        long idCliente = criarCliente(tenant.token(), "Cliente Status Parcela");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Status Parcela");
        abrirCaixaDinheiro(tenant.token());

        long idVendaRecebida;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacaoRecebido = criarVariacao(c, idTenant, idProdutoRecebido);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoRecebido, new BigDecimal("10.000"));
            idVendaRecebida = efetivarVenda(tenant.token(), idVariacaoRecebido, idCliente, idFuncionario, idCarteiraCrediario, "80.00");

            long idVariacaoAberto = criarVariacao(c, idTenant, idProdutoAberto);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoAberto, new BigDecimal("10.000"));
            efetivarVenda(tenant.token(), idVariacaoAberto, idCliente, idFuncionario, idCarteiraCrediario, "60.00");
        }

        try (Connection c = abrirConexao(idTenant)) {
            long idContaReceber = buscarIdContaReceber(c, idVendaRecebida);
            receberParcela(tenant.token(), idCliente, idContaReceber, idCarteiraDinheiro, "80.00");
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token())
                        .param("dataVendaInicial", hoje).param("dataVendaFinal", hoje).param("status", "RECEBIDA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(1))
                .andExpect(jsonPath("$.linhas[0].valorBruto").value(80.00));

        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token())
                        .param("dataVendaInicial", hoje).param("dataVendaFinal", hoje).param("status", "ABERTO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(1))
                .andExpect(jsonPath("$.linhas[0].valorBruto").value(60.00));

        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token())
                        .param("dataVendaInicial", hoje).param("dataVendaFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(2));
    }

    @Test
    void ordenacaoPorValorBrutoRespeitaAscEDesc() throws Exception {
        TenantNovo tenant = assinarNovoTenant("ordenacao-valor");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoMenor = criarProdutoComPreco(tenant.token(), "Produto Ordenacao Menor", "40.00");
        long idProdutoMaior = criarProdutoComPreco(tenant.token(), "Produto Ordenacao Maior", "90.00");
        long idCliente = criarCliente(tenant.token(), "Cliente Ordenacao Valor");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Ordenacao Valor");
        abrirCaixaDinheiro(tenant.token());

        // Cartão débito (não à vista) pra aparecer no relatório — cria a menor venda primeiro,
        // depois a maior, de propósito: se a ordenação não funcionasse cairia no default (por
        // vencimento/inserção), que já colocaria a menor antes — o teste desc só é conclusivo
        // porque a ordem de criação é OPOSTA à ordem esperada quando ordenado por valor asc.
        long idCarteiraDebito = criarTipoCarteiraComTaxa(tenant.token(), "HIPER ORDENACAO", "CARTAO_DEBITO", "0.00");
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacaoMenor = criarVariacao(c, idTenant, idProdutoMenor);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoMenor, new BigDecimal("10.000"));
            efetivarVenda(tenant.token(), idVariacaoMenor, idCliente, idFuncionario, idCarteiraDebito, "40.00");

            long idVariacaoMaior = criarVariacao(c, idTenant, idProdutoMaior);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoMaior, new BigDecimal("10.000"));
            efetivarVenda(tenant.token(), idVariacaoMaior, idCliente, idFuncionario, idCarteiraDebito, "90.00");
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token())
                        .param("dataVendaInicial", hoje).param("dataVendaFinal", hoje)
                        .param("ordenarPor", "valorBruto").param("direcao", "ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(2))
                .andExpect(jsonPath("$.linhas[0].valorBruto").value(40.00))
                .andExpect(jsonPath("$.linhas[1].valorBruto").value(90.00));

        mvc.perform(get("/api/v1/relatorios/contas-receber").header("Authorization", "Bearer " + tenant.token())
                        .param("dataVendaInicial", hoje).param("dataVendaFinal", hoje)
                        .param("ordenarPor", "valorBruto").param("direcao", "DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(2))
                .andExpect(jsonPath("$.linhas[0].valorBruto").value(90.00))
                .andExpect(jsonPath("$.linhas[1].valorBruto").value(40.00));
    }
}
