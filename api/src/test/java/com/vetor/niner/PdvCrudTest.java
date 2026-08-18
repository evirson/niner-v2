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
 * PDV — busca/leitura de produto e efetivação de venda (docs/telas/pdv.md), inclusive
 * split-tender + desconto promocional/por forma de pagamento (2026-07-28). Sem endpoint pra
 * variação/estoque ainda — gravados direto via JDBC (mesmo padrão de {@code criarVariacao} em
 * {@link ClienteHistoricoCrudTest}), como niner_app com {@code app.id_tenant} setado (RLS
 * continua valendo).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PdvCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Pdv %s","email":"dono%s@lojapdv.com",
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

    private long criarProduto(String token, String descricao, boolean ativo) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"50.00","ativo":%s}
                                """.formatted(descricao, ativo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarTipoCarteira(String token, String nome, String categoria, int prazoPagamento, int pcMinima, int pcMaxima)
            throws Exception {
        return criarTipoCarteiraComDescontoOuAcrescimo(token, nome, categoria, prazoPagamento, pcMinima, pcMaxima, null, null);
    }

    private long criarTipoCarteiraComDescontoOuAcrescimo(String token, String nome, String categoria, int prazoPagamento,
                                                           int pcMinima, int pcMaxima, BigDecimal percDesconto, BigDecimal percAcrescimo)
            throws Exception {
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"%s","categoriaCarteira":"%s","prazoPagamento":%d,
                                 "pcMinima":%d,"pcMaxima":%d,"taxaAdministradora":0,
                                 "percDesconto":%s,"percAcrescimo":%s,"permiteReceberCrediario":false}
                                """.formatted(nome, categoria, prazoPagamento, pcMinima, pcMaxima,
                                percDesconto == null ? "null" : percDesconto, percAcrescimo == null ? "null" : percAcrescimo)))
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

    /** Cliente e vendedor são obrigatórios em toda venda do PDV (2026-07-28) — cria os dois
     *  de uma vez, mesma categoria "Padrão" pra todos os testes que só precisam de um cliente
     *  qualquer pra fechar a venda. */
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

    /** Mesmo papel de {@link #criarCliente}, mas define {@code limiteCredito} explicitamente —
     *  usado só nos testes da RN de limite de crédito em crediário. */
    private long criarClienteComLimiteCredito(String token, String nome, String limiteCredito) throws Exception {
        long idCategoria = criarCategoriaCliente(token, "PADRAO " + nome);
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":false,"nome":"%s","idCategoriaCliente":%d,"limiteCredito":%s}
                                """.formatted(nome, idCategoria, limiteCredito)))
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

    /** `assinarNovoTenant` já devolve token ADMIN — PUT exige o corpo inteiro (sem campo nullable). */
    private void definirPercentualDescontoVenda(String token, String percentual) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"percentualDescontoVenda":%s,"jurosCrediarioDias":0,"jurosCrediario":0,
                                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":false,
                                 "cfgPermiteQtdDecimal":true,"cfgExigeNumeroVendaDevolucao":false,
                                 "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,"cfgConsisteValorContasPagar":false,
                                 "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":true}
                                """.formatted(percentual)))
                .andExpect(status().isOk());
    }

    /** Abre o caixa do dia usando o "DINHEIRO" semeado no signup (2026-07-30) — a venda agora
     *  exige caixa aberto (financeiro.caixa.CaixaService) antes de efetivar. */
    private void abrirCaixaDinheiro(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        long idCarteira = carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteira)))
                .andExpect(status().isOk());
    }

    private void definirPermiteQtdDecimal(String token, boolean permite) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":false,
                                 "cfgPermiteQtdDecimal":%s,"cfgExigeNumeroVendaDevolucao":false,
                                 "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,"cfgConsisteValorContasPagar":false,
                                 "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":true}
                                """.formatted(permite)))
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

    private String buscarSku(Connection c, long idVariacao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT sku FROM produto_barra WHERE id_variacao = ?")) {
            ps.setLong(1, idVariacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
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

    private BigDecimal buscarQtdEstoque(Connection c, long idVariacao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT qtd_estoque FROM produto_estoque WHERE id_variacao = ?")) {
            ps.setLong(1, idVariacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** Assume 1 única linha de item na venda (a maioria dos testes) — soma se houver mais de uma. */
    private BigDecimal buscarValorDescontoDoItem(Connection c, long idVenda) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(SUM(pmd.valor_desconto), 0)
                FROM produto_movimento_detalhe pmd
                JOIN produto_movimento_mestre pmm ON pmm.id_movimento = pmd.id_movimento
                WHERE pmm.id_venda = ?
                """)) {
            ps.setLong(1, idVenda);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private int contarParcelas(Connection c, long idVenda) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM contas_receber WHERE id_venda = ?")) {
            ps.setLong(1, idVenda);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // --- Busca / leitura ---------------------------------------------------------------

    @Test
    void buscaPorDescricaoTrazPrecoEEstoquePorEmpresa() throws Exception {
        String token = assinarNovoTenant("busca");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Tenis Corrida Pdv", true);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("12.000"));

            mvc.perform(get("/api/v1/pdv/produtos?busca=CORRIDA").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].idVariacao").value(idVariacao))
                    .andExpect(jsonPath("$[0].descricaoProduto").value("TENIS CORRIDA PDV"))
                    .andExpect(jsonPath("$[0].precoVenda").value(50.00))
                    .andExpect(jsonPath("$[0].estoquePorEmpresa.length()").value(1))
                    .andExpect(jsonPath("$[0].estoquePorEmpresa[0].qtd").value(12.0))
                    .andExpect(jsonPath("$[0].estoqueTotal").value(12.0));
        }
    }

    @Test
    void produtoInativoNaoApareceNaBusca() throws Exception {
        String token = assinarNovoTenant("inativo");
        criarProduto(token, "Produto Inativo Pdv", false);

        mvc.perform(get("/api/v1/pdv/produtos?busca=INATIVO").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void leituraPorSkuFunciona() throws Exception {
        String token = assinarNovoTenant("sku");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Por Sku", true);

        try (Connection c = abrirConexao(idTenant)) {
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            String sku = buscarSku(c, idVariacao);

            mvc.perform(get("/api/v1/pdv/produtos/codigo/" + sku).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.idVariacao").value(idVariacao))
                    .andExpect(jsonPath("$.descricaoProduto").value("PRODUTO POR SKU"));
        }
    }

    @Test
    void leituraPorCodigoInexistenteRespondeNaoEncontrado() throws Exception {
        String token = assinarNovoTenant("cod-inexistente");

        mvc.perform(get("/api/v1/pdv/produtos/codigo/0000000000000").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // --- Efetivar venda ------------------------------------------------------------------

    @Test
    void vendaAVistaGeraUmaParcelaJaPagaEBaixaEstoque() throws Exception {
        String token = assinarNovoTenant("avista");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Avista", true);
        long idCarteira = criarTipoCarteira(token, "DINHEIRO PDV", "AVISTA", 0, 1, 1);
        long idCliente = criarCliente(token, "Cliente Avista");
        long idFuncionario = criarFuncionario(token, "Vendedor Avista");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":3}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":150.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.valorTotalProdutos").value(150.00))
                    .andExpect(jsonPath("$.descontoVenda").value(0))
                    .andExpect(jsonPath("$.valorLiquido").value(150.00))
                    .andExpect(jsonPath("$.pagamentos.length()").value(1))
                    .andExpect(jsonPath("$.pagamentos[0].valorPago").value(150.00))
                    .andExpect(jsonPath("$.pagamentos[0].parcelas.length()").value(1))
                    .andExpect(jsonPath("$.pagamentos[0].parcelas[0].paga").value(true))
                    .andExpect(jsonPath("$.pagamentos[0].parcelas[0].valorParcela").value(150.00));

            // Estoque baixado pela trigger (10 - 3 = 7).
            org.assertj.core.api.Assertions.assertThat(buscarQtdEstoque(c, idVariacao))
                    .isEqualByComparingTo("7.000");
        }
    }

    @Test
    void vendaComQuantidadeDecimalEhRejeitadaQuandoParametroDesligado() throws Exception {
        String token = assinarNovoTenant("qtd-decimal-desligado");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Qtd Decimal", true);
        long idCarteira = criarTipoCarteira(token, "DINHEIRO QTD DECIMAL", "AVISTA", 0, 1, 1);
        long idCliente = criarCliente(token, "Cliente Qtd Decimal");
        long idFuncionario = criarFuncionario(token, "Vendedor Qtd Decimal");
        definirPermiteQtdDecimal(token, false);
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":2.500}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":125.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());

            // Nada foi gravado — estoque continua intacto.
            org.assertj.core.api.Assertions.assertThat(buscarQtdEstoque(c, idVariacao))
                    .isEqualByComparingTo("10.000");
        }
    }

    @Test
    void vendaCrediarioGeraParcelasEmAbertoComSomaExata() throws Exception {
        String token = assinarNovoTenant("crediario");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Crediario", true);
        long idCarteira = criarTipoCarteira(token, "CREDIARIO PDV", "CREDIARIO", 30, 1, 6);
        long idCliente = criarCliente(token, "Cliente Crediario");
        long idFuncionario = criarFuncionario(token, "Vendedor Crediario");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            // 1 unidade a 50.00 / 3 parcelas = 16.66 + 16.66 + 16.68 (resto na última).
            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":3}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.pagamentos[0].parcelas.length()").value(3))
                    .andExpect(jsonPath("$.pagamentos[0].parcelas[0].paga").value(false))
                    .andExpect(jsonPath("$.pagamentos[0].parcelas[0].valorParcela").value(16.66))
                    .andExpect(jsonPath("$.pagamentos[0].parcelas[1].valorParcela").value(16.66))
                    .andExpect(jsonPath("$.pagamentos[0].parcelas[2].valorParcela").value(16.68));
        }
    }

    @Test
    void estoqueInsuficienteNaoBloqueiaVendaEDeixaSaldoNegativo() throws Exception {
        // Saldo negativo é permitido de propósito em qualquer movimentação (2026-07-29) — não
        // há mais bloqueio de estoque insuficiente no PDV.
        String token = assinarNovoTenant("sem-estoque");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Sem Estoque", true);
        long idCarteira = criarTipoCarteira(token, "DINHEIRO SEM ESTOQUE", "AVISTA", 0, 1, 1);
        long idCliente = criarCliente(token, "Cliente Sem Estoque");
        long idFuncionario = criarFuncionario(token, "Vendedor Sem Estoque");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("2.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":5}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":250.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated());

            // Vendeu 5 com só 2 em estoque — saldo fica negativo, venda gravada normalmente.
            org.assertj.core.api.Assertions.assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("-3.000");
        }
    }

    @Test
    void numeroDeParcelasForaDaFaixaRespondeErroDeValidacao() throws Exception {
        String token = assinarNovoTenant("fora-faixa");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Fora Faixa", true);
        long idCarteira = criarTipoCarteira(token, "CARTAO FORA FAIXA", "CARTAO_CREDITO", 30, 2, 6);
        long idCliente = criarCliente(token, "Cliente Fora Faixa");
        long idFuncionario = criarFuncionario(token, "Vendedor Fora Faixa");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void formaAVistaComMaisDeUmaParcelaRespondeErroDeValidacao() throws Exception {
        String token = assinarNovoTenant("avista-parcelado");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Avista Parcelado", true);
        long idCarteira = criarTipoCarteira(token, "DEBITO AVISTA", "CARTAO_DEBITO", 0, 1, 1);
        long idCliente = criarCliente(token, "Cliente Avista Parcelado");
        long idFuncionario = criarFuncionario(token, "Vendedor Avista Parcelado");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":2}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void crediarioAcimaDoLimiteDeCreditoDoClienteRespondeErroDeValidacao() throws Exception {
        String token = assinarNovoTenant("limite-credito");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Limite Credito", true);
        long idCarteira = criarTipoCarteira(token, "CREDIARIO LIMITE", "CREDIARIO", 30, 1, 1);
        long idCliente = criarClienteComLimiteCredito(token, "Cliente Limite Credito", "40.00");
        long idFuncionario = criarFuncionario(token, "Vendedor Limite Credito");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            // Limite de R$ 40,00, venda inteira em crediário de R$ 50,00 — excede.
            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void crediarioSomaParcelasJaEmAbertoAoConferirOLimiteDeCredito() throws Exception {
        String token = assinarNovoTenant("limite-credito-soma");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Limite Credito Soma", true);
        long idCarteira = criarTipoCarteira(token, "CREDIARIO LIMITE SOMA", "CREDIARIO", 30, 1, 1);
        long idCliente = criarClienteComLimiteCredito(token, "Cliente Limite Credito Soma", "60.00");
        long idFuncionario = criarFuncionario(token, "Vendedor Limite Credito Soma");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            // 1ª venda em crediário: R$ 50,00, dentro do limite de R$ 60,00 — passa e fica em aberto.
            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated());

            // 2ª venda: mais R$ 50,00 em crediário — somado ao R$ 50,00 já em aberto, dá R$ 100,00,
            // acima do limite de R$ 60,00, mesmo que cada venda isolada não passe do limite sozinha.
            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void limiteDeCreditoZeroOuNaoDefinidoNaoBloqueiaCrediario() throws Exception {
        String token = assinarNovoTenant("sem-limite-credito");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Sem Limite Credito", true);
        long idCarteira = criarTipoCarteira(token, "CREDIARIO SEM LIMITE", "CREDIARIO", 30, 1, 1);
        // Cliente sem limiteCredito informado — fica com o padrão 0 do banco, ou seja, sem limite.
        long idCliente = criarCliente(token, "Cliente Sem Limite Credito");
        long idFuncionario = criarFuncionario(token, "Vendedor Sem Limite Credito");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void idCarteiraInexistenteRespondeErroDeValidacao() throws Exception {
        String token = assinarNovoTenant("carteira-inexistente");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Carteira Inexistente", true);
        long idCliente = criarCliente(token, "Cliente Carteira Inexistente");
        long idFuncionario = criarFuncionario(token, "Vendedor Carteira Inexistente");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":999999,"valorPago":50.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void produtoDeOutroTenantNaoApareceNaBuscaNemPodeSerVendido() throws Exception {
        String tokenA = assinarNovoTenant("tenant-a-pdv");
        String tokenB = assinarNovoTenant("tenant-b-pdv");
        long idTenantA = extrairIdTenant(tokenA);
        long idProdutoA = criarProduto(tokenA, "Produto Exclusivo Tenant A", true);
        long idCarteiraB = criarTipoCarteira(tokenB, "DINHEIRO TENANT B", "AVISTA", 0, 1, 1);
        long idClienteB = criarCliente(tokenB, "Cliente Tenant B");
        long idFuncionarioB = criarFuncionario(tokenB, "Vendedor Tenant B");
        abrirCaixaDinheiro(tokenB);

        long idVariacaoA;
        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresaA = buscarIdEmpresa(c);
            idVariacaoA = criarVariacao(c, idTenantA, idProdutoA);
            definirEstoque(c, idTenantA, idEmpresaA, idVariacaoA, new BigDecimal("10.000"));
        }

        mvc.perform(get("/api/v1/pdv/produtos?busca=EXCLUSIVO").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String corpo = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}]}
                """.formatted(idVariacaoA, idClienteB, idFuncionarioB, idCarteiraB);

        mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    // --- Desconto da venda (limitado por um máximo) / split-tender (2026-07-28/29) --------

    @Test
    void descontoInformadoNaVendaEhRateadoNoItem() throws Exception {
        String token = assinarNovoTenant("desconto-informado");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Com Desconto Informado", true);
        long idCarteira = criarTipoCarteira(token, "DINHEIRO DESCONTO INFORMADO", "AVISTA", 0, 1, 1);
        long idCliente = criarCliente(token, "Cliente Desconto Informado");
        long idFuncionario = criarFuncionario(token, "Vendedor Desconto Informado");
        definirPercentualDescontoVenda(token, "10"); // desconto MÁXIMO permitido, não mais automático.
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            // 2 unidades a 50.00 = 100.00; operador informa R$10 de desconto (no limite do
            // máximo de 10%); líquido 90.00.
            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":2}],"descontoVenda":10.00,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":90.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.valorTotalProdutos").value(100.00))
                    .andExpect(jsonPath("$.descontoVenda").value(10.00))
                    .andExpect(jsonPath("$.valorLiquido").value(90.00))
                    .andReturn().getResponse().getContentAsString();
            long idVenda = ((Number) JsonPath.read(resp, "$.idVenda")).longValue();

            org.assertj.core.api.Assertions.assertThat(buscarValorDescontoDoItem(c, idVenda))
                    .isEqualByComparingTo("10.00");
        }
    }

    @Test
    void descontoInformadoAcimaDoMaximoPermitidoRespondeErroDeValidacaoENaoGravaNada() throws Exception {
        String token = assinarNovoTenant("desconto-acima-maximo");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Desconto Acima Maximo", true);
        long idCarteira = criarTipoCarteira(token, "DINHEIRO DESCONTO ACIMA", "AVISTA", 0, 1, 1);
        long idCliente = criarCliente(token, "Cliente Desconto Acima");
        long idFuncionario = criarFuncionario(token, "Vendedor Desconto Acima");
        definirPercentualDescontoVenda(token, "10");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            // 2 unidades a 50.00 = 100.00; máximo permitido é 10.00 (10%), operador tentou 15.00.
            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":2}],"descontoVenda":15.00,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":85.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());

            org.assertj.core.api.Assertions.assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("10.000");
        }
    }

    @Test
    void semPercentualMaximoConfiguradoDescontoInformadoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("sem-desconto-maximo");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Sem Desconto Maximo", true);
        long idCarteira = criarTipoCarteira(token, "DINHEIRO SEM DESCONTO MAXIMO", "AVISTA", 0, 1, 1);
        long idCliente = criarCliente(token, "Cliente Sem Desconto Maximo");
        long idFuncionario = criarFuncionario(token, "Vendedor Sem Desconto Maximo");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.descontoVenda").value(0))
                    .andExpect(jsonPath("$.valorLiquido").value(50.00));
        }
    }

    @Test
    void splitTenderComDescontoPorFormaDePagamentoFechaOSaldo() throws Exception {
        String token = assinarNovoTenant("split-tender");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Split Tender", true);
        // Dinheiro com 10% de desconto: desconto sobre o valor pago (2026-07-28, retificado) —
        // R$100 pagos (+10% = R$110 de cobertura).
        long idCarteiraDinheiro = criarTipoCarteiraComDescontoOuAcrescimo(
                token, "DINHEIRO SPLIT", "AVISTA", 0, 1, 1, new BigDecimal("10"), null);
        long idCarteiraDebito = criarTipoCarteira(token, "DEBITO SPLIT", "CARTAO_DEBITO", 1, 1, 1);
        long idCliente = criarCliente(token, "Cliente Split Tender");
        long idFuncionario = criarFuncionario(token, "Vendedor Split Tender");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            // 4 unidades a 50.00 = 200.00. Dinheiro R$100 (+10% = 110 de cobertura); saldo 90;
            // débito R$90 fecha o saldo em 0 — soma de valorPago = 190.00.
            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":4}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[
                        {"idCarteira":%d,"valorPago":100.00,"numeroParcelas":1},
                        {"idCarteira":%d,"valorPago":90.00,"numeroParcelas":1}
                     ]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteiraDinheiro, idCarteiraDebito);

            String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.valorTotalProdutos").value(200.00))
                    .andExpect(jsonPath("$.descontoVenda").value(0))
                    .andExpect(jsonPath("$.valorLiquido").value(200.00))
                    .andExpect(jsonPath("$.pagamentos.length()").value(2))
                    .andExpect(jsonPath("$.pagamentos[0].valorPago").value(100.00))
                    .andExpect(jsonPath("$.pagamentos[1].valorPago").value(90.00))
                    .andReturn().getResponse().getContentAsString();
            long idVenda = ((Number) JsonPath.read(resp, "$.idVenda")).longValue();

            // Os 10.00 de desconto do dinheiro (100.00 × 10%) caem inteiros no único item da venda.
            org.assertj.core.api.Assertions.assertThat(buscarValorDescontoDoItem(c, idVenda))
                    .isEqualByComparingTo("10.00");
            org.assertj.core.api.Assertions.assertThat(contarParcelas(c, idVenda)).isEqualTo(2);
            // Estoque baixado uma única vez pela trigger (10 - 4 = 6), não duplicado por linha de pagamento.
            org.assertj.core.api.Assertions.assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("6.000");
        }
    }

    /** Cada linha de pagamento à vista de dinheiro/lançamento de venda vira um crédito em
     *  {@code caixa_detalhe} (2026-07-30) — CREDIARIO fica de fora de propósito, porque a
     *  parcela ainda não foi recebida (só entra no caixa quando o Recebimento de Crediário
     *  efetivamente baixar essa parcela depois). */
    @Test
    void efetivarVendaComVariasFormasLancaCaixaDetalheParaTodasMenosCrediario() throws Exception {
        String token = assinarNovoTenant("caixa-detalhe-venda");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Caixa Detalhe", true);
        long idCarteiraDinheiro = criarTipoCarteira(token, "DINHEIRO CAIXA DETALHE", "AVISTA", 0, 1, 1);
        long idCarteiraCredito = criarTipoCarteira(token, "CREDITO CAIXA DETALHE", "CARTAO_CREDITO", 30, 1, 1);
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO CAIXA DETALHE", "CREDIARIO", 30, 1, 1);
        long idCliente = criarCliente(token, "Cliente Caixa Detalhe");
        long idFuncionario = criarFuncionario(token, "Vendedor Caixa Detalhe");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            // 2 unidades a 50.00 = 100.00, cobertas por 3 formas: dinheiro (50) + crédito (30) +
            // crediário (20) — sem desconto/acréscimo, cobertura = valorPago em cada linha.
            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":2}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[
                        {"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1},
                        {"idCarteira":%d,"valorPago":30.00,"numeroParcelas":1},
                        {"idCarteira":%d,"valorPago":20.00,"numeroParcelas":1}
                     ]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteiraDinheiro, idCarteiraCredito, idCarteiraCrediario);

            String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            long idVenda = ((Number) JsonPath.read(resp, "$.idVenda")).longValue();

            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT cd.id_carteira, cd.valor, cd.credito_debito::text, cd.tipo_operacao::text
                    FROM caixa_detalhe cd WHERE cd.id_venda = ? ORDER BY cd.id_carteira
                    """)) {
                ps.setLong(1, idVenda);
                try (ResultSet rs = ps.executeQuery()) {
                    org.assertj.core.api.Assertions.assertThat(rs.next()).isTrue();
                    long primeiraCarteira = rs.getLong("id_carteira");
                    org.assertj.core.api.Assertions.assertThat(primeiraCarteira).isIn(idCarteiraDinheiro, idCarteiraCredito);
                    org.assertj.core.api.Assertions.assertThat(rs.getString(3)).isEqualTo("C");
                    org.assertj.core.api.Assertions.assertThat(rs.getString(4)).isEqualTo("RECEBIMENTO_VENDA");
                    org.assertj.core.api.Assertions.assertThat(rs.next()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(rs.getLong("id_carteira")).isIn(idCarteiraDinheiro, idCarteiraCredito);
                    org.assertj.core.api.Assertions.assertThat(rs.next()).isFalse();
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM caixa_detalhe WHERE id_venda = ? AND id_carteira = ?")) {
                ps.setLong(1, idVenda);
                ps.setLong(2, idCarteiraCrediario);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    org.assertj.core.api.Assertions.assertThat(rs.getInt(1)).isZero();
                }
            }
        }
    }

    /** Débito entra no caixa na hora (dinheiro do lojista, ver teste acima), mas a parcela em
     *  {@code contas_receber} fica em aberto — o prazo de liquidação da bandeira (D+1, aqui) só
     *  fecha via uma futura conciliação de cartões, não na hora da venda (2026-07-30, revisão:
     *  antes CARTAO_DEBITO nascia quitado junto com AVISTA). AVISTA continua nascendo quitado. */
    @Test
    void debitoFicaEmAbertoEmContasReceberMasAvistaJaNasceQuitado() throws Exception {
        String token = assinarNovoTenant("debito-em-aberto");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Debito Em Aberto", true);
        long idCarteiraDinheiro = criarTipoCarteira(token, "DINHEIRO DEBITO ABERTO", "AVISTA", 0, 1, 1);
        long idCarteiraDebito = criarTipoCarteira(token, "DEBITO DEBITO ABERTO", "CARTAO_DEBITO", 1, 1, 1);
        long idCliente = criarCliente(token, "Cliente Debito Em Aberto");
        long idFuncionario = criarFuncionario(token, "Vendedor Debito Em Aberto");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            // 2 unidades a 50.00 = 100.00: metade em dinheiro, metade no débito.
            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":2}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[
                        {"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1},
                        {"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}
                     ]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteiraDinheiro, idCarteiraDebito);

            String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.pagamentos[0].parcelas[0].paga").value(true))
                    .andExpect(jsonPath("$.pagamentos[1].parcelas[0].paga").value(false))
                    .andReturn().getResponse().getContentAsString();
            long idVenda = ((Number) JsonPath.read(resp, "$.idVenda")).longValue();

            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT cr.id_carteira, cr.data_recebimento, cr.valor_recebido, cr.id_empresa_pagamento
                    FROM contas_receber cr WHERE cr.id_venda = ? ORDER BY cr.id_carteira
                    """)) {
                ps.setLong(1, idVenda);
                try (ResultSet rs = ps.executeQuery()) {
                    org.assertj.core.api.Assertions.assertThat(rs.next()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(rs.getLong("id_carteira")).isEqualTo(idCarteiraDinheiro);
                    org.assertj.core.api.Assertions.assertThat(rs.getObject("data_recebimento")).isNotNull();
                    org.assertj.core.api.Assertions.assertThat(rs.getBigDecimal("valor_recebido")).isEqualByComparingTo("50.00");
                    org.assertj.core.api.Assertions.assertThat(rs.getObject("id_empresa_pagamento")).isNotNull();

                    org.assertj.core.api.Assertions.assertThat(rs.next()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(rs.getLong("id_carteira")).isEqualTo(idCarteiraDebito);
                    org.assertj.core.api.Assertions.assertThat(rs.getObject("data_recebimento")).isNull();
                    org.assertj.core.api.Assertions.assertThat(rs.getBigDecimal("valor_recebido")).isEqualByComparingTo("0.00");
                    org.assertj.core.api.Assertions.assertThat(rs.getObject("id_empresa_pagamento")).isNull();
                }
            }

            // Mas o débito já entrou no caixa na hora da venda (P1: caixa != contas_receber).
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT valor FROM caixa_detalhe WHERE id_venda = ? AND id_carteira = ?")) {
                ps.setLong(1, idVenda);
                ps.setLong(2, idCarteiraDebito);
                try (ResultSet rs = ps.executeQuery()) {
                    org.assertj.core.api.Assertions.assertThat(rs.next()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("50.00");
                }
            }
        }
    }

    @Test
    void valorPagoAcimaDoMaximoPermitidoPelaFormaDePagamentoComDescontoRespondeErroDeValidacao() throws Exception {
        String token = assinarNovoTenant("valor-pago-acima-maximo");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Valor Pago Acima Maximo", true);
        // Dinheiro com 10% de desconto: o desconto é sobre o valor pago (2026-07-28,
        // retificado) — sobre um saldo de 500.00, o máximo pagável é 454.55 (500 ÷ 1,10), o
        // ponto exato em que essa forma de pagamento fecha o saldo sozinha sem sobra (pagar
        // 454.55 cobre 454.55 × 1,10 = 500.00). Pagar mais do que isso faria a cobertura passar do saldo.
        long idCarteiraDinheiro = criarTipoCarteiraComDescontoOuAcrescimo(
                token, "DINHEIRO VALOR MAXIMO", "AVISTA", 0, 1, 1, new BigDecimal("10"), null);
        long idCliente = criarCliente(token, "Cliente Valor Maximo");
        long idFuncionario = criarFuncionario(token, "Vendedor Valor Maximo");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            // 10 unidades a 50.00 = 500.00. Tentando pagar 460.00 (acima do máximo de 454.55).
            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":10}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":460.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteiraDinheiro);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());

            org.assertj.core.api.Assertions.assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("10.000");
        }
    }

    @Test
    void formaDePagamentoComDescontoFechaOSaldoSozinhaSemSobra() throws Exception {
        String token = assinarNovoTenant("fecha-sozinha");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Fecha Sozinha", true);
        // Desconto sobre o valor pago (2026-07-28, retificado): sobre um saldo de 100.00, o
        // valor pago que fecha sozinho é 100 ÷ 1,10 = 90.91 (arredondado) — cobertura =
        // 90.91 + 9.09 = 100.00 exato, uma única linha de pagamento fecha a venda sem precisar
        // de uma segunda.
        long idCarteiraDinheiro = criarTipoCarteiraComDescontoOuAcrescimo(
                token, "DINHEIRO FECHA SOZINHA", "AVISTA", 0, 1, 1, new BigDecimal("10"), null);
        long idCliente = criarCliente(token, "Cliente Fecha Sozinha");
        long idFuncionario = criarFuncionario(token, "Vendedor Fecha Sozinha");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            // 2 unidades a 50.00 = 100.00.
            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":2}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":90.91,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteiraDinheiro);

            String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.valorLiquido").value(100.00))
                    .andExpect(jsonPath("$.pagamentos.length()").value(1))
                    .andExpect(jsonPath("$.pagamentos[0].valorPago").value(90.91))
                    .andReturn().getResponse().getContentAsString();
            long idVenda = ((Number) JsonPath.read(resp, "$.idVenda")).longValue();

            org.assertj.core.api.Assertions.assertThat(buscarValorDescontoDoItem(c, idVenda))
                    .isEqualByComparingTo("9.09");
        }
    }

    @Test
    void pagamentosQueNaoFechamOSaldoRespondemErroDeValidacaoENaoGravamNada() throws Exception {
        String token = assinarNovoTenant("saldo-nao-fecha");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Saldo Nao Fecha", true);
        long idCarteira = criarTipoCarteira(token, "DINHEIRO SALDO NAO FECHA", "AVISTA", 0, 1, 1);
        long idCliente = criarCliente(token, "Cliente Saldo Nao Fecha");
        long idFuncionario = criarFuncionario(token, "Vendedor Saldo Nao Fecha");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            // Item de 50.00, mas só R$30 informados — saldo de R$20 não fecha.
            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":30.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());

            org.assertj.core.api.Assertions.assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("10.000");
        }
    }

    // --- Caixa aberto é obrigatório (2026-07-30) -------------------------------------------

    @Test
    void vendaSemCaixaAbertoRespondeErroDeValidacaoENaoGravaNada() throws Exception {
        String token = assinarNovoTenant("sem-caixa");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Sem Caixa", true);
        long idCarteira = criarTipoCarteira(token, "DINHEIRO SEM CAIXA", "AVISTA", 0, 1, 1);
        long idCliente = criarCliente(token, "Cliente Sem Caixa");
        long idFuncionario = criarFuncionario(token, "Vendedor Sem Caixa");
        // Sem abrirCaixaDinheiro(token) de propósito — nenhum caixa aberto hoje.

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());

            org.assertj.core.api.Assertions.assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("10.000");
        }
    }

    // --- Cliente e vendedor obrigatórios (2026-07-28) -------------------------------------

    @Test
    void idClienteInexistenteRespondeErroDeValidacao() throws Exception {
        String token = assinarNovoTenant("cliente-inexistente");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Cliente Inexistente", true);
        long idCarteira = criarTipoCarteira(token, "DINHEIRO CLIENTE INEXISTENTE", "AVISTA", 0, 1, 1);
        long idFuncionario = criarFuncionario(token, "Vendedor Cliente Inexistente");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":999999,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idFuncionario, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());

            org.assertj.core.api.Assertions.assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("10.000");
        }
    }

    @Test
    void idFuncionarioInexistenteRespondeErroDeValidacao() throws Exception {
        String token = assinarNovoTenant("funcionario-inexistente");
        long idTenant = extrairIdTenant(token);
        long idProduto = criarProduto(token, "Produto Funcionario Inexistente", true);
        long idCarteira = criarTipoCarteira(token, "DINHEIRO FUNCIONARIO INEXISTENTE", "AVISTA", 0, 1, 1);
        long idCliente = criarCliente(token, "Cliente Funcionario Inexistente");
        abrirCaixaDinheiro(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));

            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":999999,
                     "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idCarteira);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());

            org.assertj.core.api.Assertions.assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("10.000");
        }
    }

    // --- Busca de cliente (F6) --------------------------------------------------------------

    @Test
    void buscaDeClientePorNomeCpfOuCelularFunciona() throws Exception {
        String token = assinarNovoTenant("busca-cliente");
        long idCategoria = criarCategoriaCliente(token, "Categoria Busca Cliente");
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":true,"nome":"Maria Busca Pdv","idCategoriaCliente":%d,
                                 "cpfCnpj":"11144477735","telefone":"11988887777","genero":"FEMININO"}
                                """.formatted(idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idCliente = ((Number) JsonPath.read(resp, "$.idCliente")).longValue();

        mvc.perform(get("/api/v1/pdv/clientes?busca=MARIA").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].idCliente").value(idCliente))
                .andExpect(jsonPath("$[0].nome").value("MARIA BUSCA PDV"));

        mvc.perform(get("/api/v1/pdv/clientes?busca=11144477735").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].idCliente").value(idCliente));

        mvc.perform(get("/api/v1/pdv/clientes?busca=11988887777").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].idCliente").value(idCliente));
    }

    @Test
    void clienteDeOutroTenantNaoApareceNaBuscaDoPdv() throws Exception {
        String tokenA = assinarNovoTenant("cliente-tenant-a");
        String tokenB = assinarNovoTenant("cliente-tenant-b");
        criarCliente(tokenA, "Cliente Exclusivo Tenant A");

        mvc.perform(get("/api/v1/pdv/clientes?busca=EXCLUSIVO").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
