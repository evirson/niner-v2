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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pesquisa de Venda (docs/telas/pesquisa-vendas.md) — qualquer papel, somente leitura. Vendas
 * são geradas via o endpoint real do PDV (não inseridas direto via SQL) pra exercitar o ledger
 * de verdade, exceto onde o teste só precisa de uma linha de {@code venda} pra provar isolamento
 * de empresa/tenant (mesmo espírito de {@code CancelamentoVendaCrudTest}, de onde a maior parte
 * dos helpers foi reaproveitada).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PesquisaVendaCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Pesquisa %s","email":"dono%s@lojapesquisa.com",
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
        String email = "operador%s@lojapesquisa.com".formatted(sufixo);
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
        return criarTipoCarteira(token, nome, categoria, 1);
    }

    private long criarTipoCarteira(String token, String nome, String categoria, int pcMaxima) throws Exception {
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"%s","categoriaCarteira":"%s","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":%d,"permiteReceberCrediario":true}
                                """.formatted(nome, categoria, pcMaxima)))
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
                             + idTenant + ", 2, 'FILIAL PESQUISA', '{sku}') RETURNING id_empresa")) {
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

    /** Efetiva uma venda de 1 unidade via PDV de verdade — devolve o id_venda. */
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

    private void receberParcela(String token, long idCliente, long idContaReceber, long idCarteiraPagamento, String valor) throws Exception {
        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":%s}]}
                                """.formatted(idCliente, idContaReceber, idCarteiraPagamento, valor)))
                .andExpect(status().isOk());
    }

    private List<Long> buscarIdsContaReceber(Connection c, long idVenda) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id_conta_receber FROM contas_receber WHERE id_venda = ? ORDER BY numero_parcela")) {
            ps.setLong(1, idVenda);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    private void tornarParcelaVencida(Connection c, long idContaReceber) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE contas_receber SET data_vencimento = now() - interval '5 days' WHERE id_conta_receber = ?")) {
            ps.setLong(1, idContaReceber);
            ps.executeUpdate();
        }
    }

    private long criarVendaBruta(Connection c, long idTenant, long idEmpresa) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO venda (id_tenant, id_empresa) VALUES (?, ?) RETURNING id_venda")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Intervalo amplo (180 dias antes/depois de hoje, 360 no total — dentro do limite de 365)
     *  usado quando o teste só precisa garantir que as vendas de hoje caiam no filtro, sem se
     *  importar com a data exata. */
    private static String[] intervaloAmplo() {
        return new String[] { LocalDate.now().minusDays(180).toString(), LocalDate.now().plusDays(180).toString() };
    }

    private void cancelar(String token, long idVenda) throws Exception {
        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"teste pesquisa de vendas\"}"))
                .andExpect(status().isOk());
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

        mvc.perform(get("/api/v1/vendas/pesquisa").header("Authorization", "Bearer " + tenant.token())
                        .param("numeroVenda", String.valueOf(idVenda))
                        .param("dataInicial", "2020-01-01").param("dataFinal", "2020-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].idVenda").value(idVenda));
    }

    @Test
    void pesquisaSemDataENemNumeroRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sem-filtro");
        mvc.perform(get("/api/v1/vendas/pesquisa").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void periodoMaiorQue365DiasSemNumeroRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("periodo-maximo");
        mvc.perform(get("/api/v1/vendas/pesquisa").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", "2020-01-01").param("dataFinal", "2022-06-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void operadorSempreConsultaAPropriaEmpresaMesmoInformandoOutra() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-empresa");
        long idTenant = extrairIdTenant(tenant.token());
        long idEmpresaOrigem = buscarPrimeiraEmpresa(tenant.token());

        long idEmpresaDestino;
        long idVendaOutraEmpresa;
        try (Connection c = abrirConexao(idTenant)) {
            idEmpresaDestino = criarSegundaEmpresa(c, idTenant);
            idVendaOutraEmpresa = criarVendaBruta(c, idTenant, idEmpresaDestino);
        }

        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "operador-empresa", idEmpresaOrigem);

        String resp = mvc.perform(get("/api/v1/vendas/pesquisa").header("Authorization", "Bearer " + tokenOperador)
                        .param("idEmpresa", String.valueOf(idEmpresaDestino))
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1]))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Number> idsRetornados = JsonPath.read(resp, "$.itens[*].idVenda");
        assertThat(idsRetornados).extracting(Number::longValue).doesNotContain(idVendaOutraEmpresa);
    }

    @Test
    void filtroDeSituacaoRestringeAAtivasOuCanceladas() throws Exception {
        TenantNovo tenant = assinarNovoTenant("filtro-situacao");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Filtro Situacao");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO FILTRO SITUACAO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Filtro Situacao");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Filtro Situacao");
        abrirCaixaDinheiro(tenant.token());

        long idVendaAtiva;
        long idVendaCancelada;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVendaAtiva = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
            idVendaCancelada = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }
        cancelar(tenant.token(), idVendaCancelada);

        mvc.perform(get("/api/v1/vendas/pesquisa").header("Authorization", "Bearer " + tenant.token())
                        .param("situacao", "ATIVAS")
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[?(@.idVenda == %d)]".formatted(idVendaCancelada)).isEmpty())
                .andExpect(jsonPath("$.itens[?(@.idVenda == %d)]".formatted(idVendaAtiva)).isNotEmpty());

        mvc.perform(get("/api/v1/vendas/pesquisa").header("Authorization", "Bearer " + tenant.token())
                        .param("situacao", "CANCELADAS")
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[?(@.idVenda == %d)]".formatted(idVendaAtiva)).isEmpty())
                .andExpect(jsonPath("$.itens[?(@.idVenda == %d)]".formatted(idVendaCancelada)).isNotEmpty());
    }

    @Test
    void totalizadorIgnoraVendasCanceladasMesmoAparecendoNaGrid() throws Exception {
        TenantNovo tenant = assinarNovoTenant("totalizador");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Totalizador");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO TOTALIZADOR", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Totalizador");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Totalizador");
        abrirCaixaDinheiro(tenant.token());

        long idVendaCancelada;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
            idVendaCancelada = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }
        cancelar(tenant.token(), idVendaCancelada);

        mvc.perform(get("/api/v1/vendas/pesquisa").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.totalItens").value(2))
                .andExpect(jsonPath("$.totalItensAtivos").value(1))
                .andExpect(jsonPath("$.somaValorAtivas").value(50.00));
    }

    @Test
    void detalheDeVendaComItensCaixaEParcelasBateComOLedger() throws Exception {
        TenantNovo tenant = assinarNovoTenant("detalhe-completo");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Detalhe Completo");
        long idCarteiraCrediario = criarTipoCarteira(tenant.token(), "CREDIARIO DETALHE", "CREDIARIO", 2);
        long idCarteiraPagamento = buscarIdCarteiraDinheiro(tenant.token());
        long idCliente = criarCliente(tenant.token(), "Cliente Detalhe Completo");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Detalhe Completo");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteiraCrediario, "50.00", 2);

            List<Long> parcelas = buscarIdsContaReceber(c, idVenda);
            assertThat(parcelas).hasSize(2);
            receberParcela(tenant.token(), idCliente, parcelas.get(0), idCarteiraPagamento, "25.00");
        }

        mvc.perform(get("/api/v1/vendas/pesquisa/" + idVenda).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].qtd").value(1))
                .andExpect(jsonPath("$.valorTotal").value(50.00))
                .andExpect(jsonPath("$.temParcelasCredario").value(true))
                .andExpect(jsonPath("$.parcelas.length()").value(2))
                .andExpect(jsonPath("$.parcelas[0].totalParcelas").value(2))
                .andExpect(jsonPath("$.parcelas[0].situacao").value("PAGA"))
                // prazoPagamento=0 no fixture do tipo de carteira → vencimento é "agora", já
                // passado no instante da asserção → VENCIDA é o resultado correto (não ABERTA).
                .andExpect(jsonPath("$.parcelas[1].situacao").value("VENCIDA"))
                .andExpect(jsonPath("$.recebido").value(25.00))
                .andExpect(jsonPath("$.aReceber").value(25.00))
                .andExpect(jsonPath("$.movimentosCaixa[?(@.tipoOperacao == 'RECEBIMENTO_PARCELA_CREDIARIO')]").isNotEmpty());
    }

    @Test
    void detalheDeVendaSemCredarioMostraListaVaziaDeParcelas() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sem-crediario");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Sem Crediario");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO SEM CREDIARIO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Sem Crediario");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Sem Crediario");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        mvc.perform(get("/api/v1/vendas/pesquisa/" + idVenda).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temParcelasCredario").value(false))
                .andExpect(jsonPath("$.parcelas.length()").value(0))
                .andExpect(jsonPath("$.recebido").value(50.00))
                .andExpect(jsonPath("$.aReceber").value(0));
    }

    @Test
    void parcelaVencidaEhCalculadaEmTempoDeExibicao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("parcela-vencida");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Parcela Vencida");
        long idCarteiraCrediario = criarTipoCarteira(tenant.token(), "CREDIARIO VENCIDA", "CREDIARIO");
        long idCliente = criarCliente(tenant.token(), "Cliente Parcela Vencida");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Parcela Vencida");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteiraCrediario, "50.00", 1);

            long idContaReceber = buscarIdsContaReceber(c, idVenda).get(0);
            tornarParcelaVencida(c, idContaReceber);
        }

        mvc.perform(get("/api/v1/vendas/pesquisa/" + idVenda).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelas[0].situacao").value("VENCIDA"));
    }

    @Test
    void vendaDeOutroTenantNaoApareceNaListaNemNoDetalhe() throws Exception {
        TenantNovo tenantA = assinarNovoTenant("isolamento-a-pesquisa");
        TenantNovo tenantB = assinarNovoTenant("isolamento-b-pesquisa");
        long idTenantA = extrairIdTenant(tenantA.token());
        long idProduto = criarProduto(tenantA.token(), "Produto Isolamento Pesquisa");
        long idCarteira = criarTipoCarteira(tenantA.token(), "DINHEIRO ISOLAMENTO PESQUISA", "AVISTA");
        long idCliente = criarCliente(tenantA.token(), "Cliente Isolamento Pesquisa");
        long idFuncionario = criarFuncionario(tenantA.token(), "Vendedor Isolamento Pesquisa");
        abrirCaixaDinheiro(tenantA.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenantA, idProduto);
            definirEstoque(c, idTenantA, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenantA.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        mvc.perform(get("/api/v1/vendas/pesquisa").header("Authorization", "Bearer " + tenantB.token())
                        .param("numeroVenda", String.valueOf(idVenda)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(0));

        mvc.perform(get("/api/v1/vendas/pesquisa/" + idVenda).header("Authorization", "Bearer " + tenantB.token()))
                .andExpect(status().isNotFound());
    }
}
