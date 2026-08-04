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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Relatório de Movimentação de Produtos / Kardex (package-info.java do pacote
 * estoque.relatoriomovimentacao) — Analítico/Kardex/Sintético, tipos "não físicos"
 * (RESERVA/LIBERACAO_RESERVA), saldo corrido do Kardex, Top Ajustes Negativos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RelatorioMovimentacaoProdutosCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Movimentacao %s","email":"dono%s@lojamovimentacao.com",
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

    private long criarProdutoComPreco(String token, String descricao, String precoCusto, String precoVenda) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"%s","percentualVenda":"100","precoVenda":"%s"}
                                """.formatted(descricao, precoCusto, precoVenda)))
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
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();
    }

    private long buscarIdCarteiraDinheiro(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
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

    private long efetivarVenda(String token, long idVariacao, long idCliente, long idFuncionario,
                                long idCarteira, String valorPago) throws Exception {
        String corpo = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":1}]}
                """.formatted(idVariacao, idCliente, idFuncionario, idCarteira, valorPago);
        String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
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

    private long criarSegundaEmpresa(Connection c, long idTenant) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, cfg_nome_etiqueta) VALUES ("
                             + idTenant + ", 2, 'FILIAL MOVIMENTACAO', '{sku}') RETURNING id_empresa")) {
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

    /** Insere um movimento direto no ledger — usado pra tipos sem tela ainda (RESERVA/
     *  LIBERACAO_RESERVA) e pra controlar a data com precisão (Kardex antes/depois do período). */
    private long inserirMovimento(Connection c, long idTenant, long idEmpresa, String tipoMovimento,
                                   OffsetDateTime dataMovimento) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, data_movimento)
                VALUES (?, ?, ?::tipo_movimento, ?)
                RETURNING id_movimento
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setString(3, tipoMovimento);
            ps.setObject(4, dataMovimento);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void inserirDetalhe(Connection c, long idTenant, long idMovimento, long idEmpresa, long idVariacao,
                                 String creditoDebito, BigDecimal qtd, BigDecimal precoCusto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_movimento_detalhe
                    (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto, preco_custo, origem)
                VALUES (?, ?, ?, ?, ?::credito_debito, ?, ?, 'teste')
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idMovimento);
            ps.setLong(3, idEmpresa);
            ps.setLong(4, idVariacao);
            ps.setString(5, creditoDebito);
            ps.setBigDecimal(6, qtd);
            ps.setBigDecimal(7, precoCusto);
            ps.execute();
        }
    }

    private String hojeISO() {
        return LocalDate.now().toString();
    }

    private OffsetDateTime agora() {
        return OffsetDateTime.now();
    }

    @Test
    void analiticoTrazVendaComDocumentoContendoNumeroDaVenda() throws Exception {
        TenantNovo tenant = assinarNovoTenant("venda-documento");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Movimentacao Venda", "10.00", "50.00");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO MOV VENDA", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Mov Venda");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Mov Venda");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00");
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/movimentacao-produtos").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "ANALITICO").param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasAnalitico.length()").value(1))
                .andExpect(jsonPath("$.linhasAnalitico[0].tipoMovimento").value("VENDA"))
                .andExpect(jsonPath("$.linhasAnalitico[0].movimentoFisico").value(true))
                .andExpect(jsonPath("$.linhasAnalitico[0].entrada").value(0))
                .andExpect(jsonPath("$.linhasAnalitico[0].saida").value(1))
                .andExpect(jsonPath("$.linhasAnalitico[0].custoUnitario").value(10.00))
                .andExpect(jsonPath("$.linhasAnalitico[0].valorMovimentado").value(10.00))
                .andExpect(jsonPath("$.linhasAnalitico[0].documento").value("Venda #" + idVenda));
    }

    @Test
    void analiticoFiltraPorTipoDeMovimento() throws Exception {
        TenantNovo tenant = assinarNovoTenant("filtro-tipo");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Mov Filtro Tipo", "5.00", "20.00");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO MOV FILTRO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Mov Filtro");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Mov Filtro");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "20.00");

            long idMovimentoAjuste = inserirMovimento(c, idTenant, idEmpresa, "AJUSTE", agora());
            inserirDetalhe(c, idTenant, idMovimentoAjuste, idEmpresa, idVariacao, "C", new BigDecimal("3.000"), new BigDecimal("5.00"));
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/movimentacao-produtos").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "ANALITICO").param("dataInicial", hoje).param("dataFinal", hoje)
                        .param("tipos", "AJUSTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasAnalitico.length()").value(1))
                .andExpect(jsonPath("$.linhasAnalitico[0].tipoMovimento").value("AJUSTE"))
                .andExpect(jsonPath("$.linhasAnalitico[0].entrada").value(3))
                .andExpect(jsonPath("$.linhasAnalitico[0].documento").value("Teste"));
    }

    @Test
    void sinteticoAgrupaPorTipoSomandoEntradaSaidaEValor() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sintetico-agrupa");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Mov Sintetico", "10.00", "40.00");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO MOV SINTETICO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Mov Sintetico");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Mov Sintetico");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "40.00");
        }

        String corpoDevolucao = """
                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                """.formatted(idVenda, idVariacao);
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoDevolucao))
                .andExpect(status().isCreated());

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/movimentacao-produtos").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "SINTETICO").param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasSintetico.length()").value(2))
                // Ordem determinística: a query já sai ordenada por data_movimento/id_movimento, e a
                // venda aconteceu antes da devolução (LinkedHashMap preserva a 1ª aparição).
                .andExpect(jsonPath("$.linhasSintetico[0].tipoMovimento").value("VENDA"))
                .andExpect(jsonPath("$.linhasSintetico[0].qtdSaida").value(1))
                .andExpect(jsonPath("$.linhasSintetico[0].valorSaida").value(10.00))
                .andExpect(jsonPath("$.linhasSintetico[1].tipoMovimento").value("DEVOLUCAO"))
                .andExpect(jsonPath("$.linhasSintetico[1].qtdEntrada").value(1))
                .andExpect(jsonPath("$.linhasSintetico[1].valorEntrada").value(10.00));
    }

    @Test
    void kpisFisicosExcluemReservaELiberacaoReserva() throws Exception {
        TenantNovo tenant = assinarNovoTenant("kpi-fisico");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Mov Kpi Fisico", "10.00", "10.00");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);

            long idMovVenda = inserirMovimento(c, idTenant, idEmpresa, "VENDA", agora());
            inserirDetalhe(c, idTenant, idMovVenda, idEmpresa, idVariacao, "D", new BigDecimal("2.000"), new BigDecimal("10.00"));

            long idMovReserva = inserirMovimento(c, idTenant, idEmpresa, "RESERVA", agora());
            inserirDetalhe(c, idTenant, idMovReserva, idEmpresa, idVariacao, "D", new BigDecimal("5.000"), new BigDecimal("10.00"));
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/movimentacao-produtos").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "ANALITICO").param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasAnalitico.length()").value(2))
                .andExpect(jsonPath("$.kpis.qtdSaidaFisica").value(2))
                .andExpect(jsonPath("$.kpis.valorSaidaFisica").value(20.00));
    }

    @Test
    void kardexSomaTodosOsTiposIncluindoReservaNoSaldoCorrido() throws Exception {
        TenantNovo tenant = assinarNovoTenant("kardex-saldo");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Mov Kardex", "10.00", "10.00");

        long idVariacao;
        long idEmpresa;
        try (Connection c = abrirConexao(idTenant)) {
            idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);

            // Antes do período — vira saldo inicial.
            long idMovAjusteAntigo = inserirMovimento(c, idTenant, idEmpresa, "AJUSTE", OffsetDateTime.now().minusDays(5));
            inserirDetalhe(c, idTenant, idMovAjusteAntigo, idEmpresa, idVariacao, "C", new BigDecimal("10.000"), new BigDecimal("10.00"));

            // Dentro do período.
            long idMovVenda = inserirMovimento(c, idTenant, idEmpresa, "VENDA", agora());
            inserirDetalhe(c, idTenant, idMovVenda, idEmpresa, idVariacao, "D", new BigDecimal("3.000"), new BigDecimal("10.00"));

            long idMovReserva = inserirMovimento(c, idTenant, idEmpresa, "RESERVA", agora());
            inserirDetalhe(c, idTenant, idMovReserva, idEmpresa, idVariacao, "D", new BigDecimal("2.000"), new BigDecimal("10.00"));
        }

        String hoje = hojeISO();
        String ontem = LocalDate.now().minusDays(1).toString();
        mvc.perform(get("/api/v1/relatorios/movimentacao-produtos").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "KARDEX").param("dataInicial", ontem).param("dataFinal", hoje)
                        .param("idVariacaoKardex", String.valueOf(idVariacao)).param("idEmpresaKardex", String.valueOf(idEmpresa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cabecalhoKardex.saldoInicial").value(10))
                .andExpect(jsonPath("$.cabecalhoKardex.saldoFinal").value(5))
                .andExpect(jsonPath("$.linhasKardex.length()").value(2))
                .andExpect(jsonPath("$.linhasKardex[0].tipoMovimento").value("VENDA"))
                .andExpect(jsonPath("$.linhasKardex[0].saldoApos").value(7))
                .andExpect(jsonPath("$.linhasKardex[1].tipoMovimento").value("RESERVA"))
                .andExpect(jsonPath("$.linhasKardex[1].movimentoFisico").value(false))
                .andExpect(jsonPath("$.linhasKardex[1].saldoApos").value(5));
    }

    @Test
    void topAjustesNegativosRankeiaPerdaPorProduto() throws Exception {
        TenantNovo tenant = assinarNovoTenant("top-ajustes");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoMaiorPerda = criarProdutoComPreco(tenant.token(), "Produto Mov Maior Perda", "20.00", "20.00");
        long idProdutoMenorPerda = criarProdutoComPreco(tenant.token(), "Produto Mov Menor Perda", "5.00", "5.00");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacaoMaior = criarVariacao(c, idTenant, idProdutoMaiorPerda);
            long idVariacaoMenor = criarVariacao(c, idTenant, idProdutoMenorPerda);

            long idMov1 = inserirMovimento(c, idTenant, idEmpresa, "AJUSTE", agora());
            inserirDetalhe(c, idTenant, idMov1, idEmpresa, idVariacaoMaior, "D", new BigDecimal("5.000"), new BigDecimal("20.00"));

            long idMov2 = inserirMovimento(c, idTenant, idEmpresa, "AJUSTE", agora());
            inserirDetalhe(c, idTenant, idMov2, idEmpresa, idVariacaoMenor, "D", new BigDecimal("1.000"), new BigDecimal("5.00"));
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/movimentacao-produtos").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "ANALITICO").param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graficos.topAjustesNegativos.length()").value(2))
                .andExpect(jsonPath("$.graficos.topAjustesNegativos[0].rotulo").value("PRODUTO MOV MAIOR PERDA"))
                .andExpect(jsonPath("$.graficos.topAjustesNegativos[0].valor").value(100.00))
                .andExpect(jsonPath("$.graficos.topAjustesNegativos[1].rotulo").value("PRODUTO MOV MENOR PERDA"))
                .andExpect(jsonPath("$.graficos.topAjustesNegativos[1].valor").value(5.00));
    }

    @Test
    void operadorSempreConsultaAPropriaEmpresaMesmoInformandoOutra() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-mov");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Mov Operador", "10.00", "10.00");

        String tokenOperador;
        long idEmpresaOrigem;
        long idEmpresaDestino;
        try (Connection c = abrirConexao(idTenant)) {
            idEmpresaOrigem = buscarIdEmpresa(c);
            idEmpresaDestino = criarSegundaEmpresa(c, idTenant);
            long idVariacao = criarVariacao(c, idTenant, idProduto);

            long idMovOrigem = inserirMovimento(c, idTenant, idEmpresaOrigem, "AJUSTE", agora());
            inserirDetalhe(c, idTenant, idMovOrigem, idEmpresaOrigem, idVariacao, "C", new BigDecimal("1.000"), new BigDecimal("10.00"));

            long idMovDestino = inserirMovimento(c, idTenant, idEmpresaDestino, "AJUSTE", agora());
            inserirDetalhe(c, idTenant, idMovDestino, idEmpresaDestino, idVariacao, "C", new BigDecimal("1.000"), new BigDecimal("10.00"));
        }

        String email = "operadormov@lojamovimentacao.com";
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nome":"Operador Mov","email":"%s","senha":"senha1234",
                                 "administrador":false,"idsEmpresa":[%d]}
                                """.formatted(email, idEmpresaOrigem)))
                .andExpect(status().isCreated());
        String login = """
                {"slug":"%s","email":"%s","senha":"senha1234"}
                """.formatted(tenant.slug(), email);
        String respLogin = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        tokenOperador = JsonPath.read(respLogin, "$.token");

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/movimentacao-produtos").header("Authorization", "Bearer " + tokenOperador)
                        .param("modelo", "ANALITICO").param("dataInicial", hoje).param("dataFinal", hoje)
                        .param("idsEmpresa", String.valueOf(idEmpresaDestino)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasAnalitico.length()").value(1))
                .andExpect(jsonPath("$.linhasAnalitico[0].idEmpresa").value(idEmpresaOrigem));
    }

    @Test
    void periodoObrigatorioRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("mov-sem-periodo");
        mvc.perform(get("/api/v1/relatorios/movimentacao-produtos").header("Authorization", "Bearer " + tenant.token())
                        .param("modelo", "ANALITICO"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        TenantNovo tenantA = assinarNovoTenant("isolamento-mov-a");
        TenantNovo tenantB = assinarNovoTenant("isolamento-mov-b");
        long idTenantA = extrairIdTenant(tenantA.token());
        long idProduto = criarProdutoComPreco(tenantA.token(), "Produto Isolamento Mov", "10.00", "10.00");

        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenantA, idProduto);
            long idMov = inserirMovimento(c, idTenantA, idEmpresa, "AJUSTE", agora());
            inserirDetalhe(c, idTenantA, idMov, idEmpresa, idVariacao, "C", new BigDecimal("1.000"), new BigDecimal("10.00"));
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/movimentacao-produtos").header("Authorization", "Bearer " + tenantB.token())
                        .param("modelo", "ANALITICO").param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasAnalitico.length()").value(0));
    }

    @Test
    void buscarVariacoesRetornaPorDescricaoOuSku() throws Exception {
        TenantNovo tenant = assinarNovoTenant("busca-variacao");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Busca Variacao Unico", "10.00", "10.00");

        try (Connection c = abrirConexao(idTenant)) {
            criarVariacao(c, idTenant, idProduto);
        }

        mvc.perform(get("/api/v1/relatorios/movimentacao-produtos/variacoes")
                        .header("Authorization", "Bearer " + tenant.token()).param("busca", "Busca Variacao Unico"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].descricaoProduto").value("PRODUTO BUSCA VARIACAO UNICO"));
    }
}
