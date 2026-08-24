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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Relatório de Lucratividade (docs/telas/relatorio-lucratividade.md) — os 11 critérios de aceitação
 * da spec.
 *
 * <p>⚠️ <b>A data do filtro vem do BANCO, não de {@code LocalDate.now()}.</b> O serviço compara
 * {@code (coluna AT TIME ZONE 'America/Sao_Paulo')::date}, e o relógio da JVM roda em UTC nos
 * containers: das 21h à meia-noite de Brasília os dois discordam por um dia, e o teste passaria a
 * falhar só nesse intervalo. Ver {@code feedback_testes_frageis_por_relogio}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RelatorioLucratividadeCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    // ------------------------------------------------------------------ fixtures

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Lucro %s","email":"dono%s@lojalucro.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new TenantNovo(JsonPath.read(resp, "$.token"), JsonPath.read(resp, "$.slug"));
    }

    private static long extrairIdTenant(String token) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return ((Number) JsonPath.read(payload, "$.tid")).longValue();
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    /** O "hoje" do relatório é o do fuso da loja, apurado pelo próprio banco. Ver o aviso da classe. */
    private LocalDate hojeNoFusoDaLoja(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT (now() AT TIME ZONE 'America/Sao_Paulo')::date")) {
            rs.next();
            return rs.getObject(1, LocalDate.class);
        }
    }

    private long buscarIdEmpresa(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Produto com custo 60 e venda 100 — os números da spec. */
    private long criarProduto(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"60.00","percentualVenda":"66.67","precoVenda":"100.00"}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarVariacaoComEstoque(Connection c, long idTenant, long idEmpresa, long idProduto)
            throws SQLException {
        long idVariacao;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_barra (id_tenant, id_produto, sku) VALUES (?, ?, gerar_ean13_interno())
                RETURNING id_variacao
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                idVariacao = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque) VALUES (?, ?, ?, ?)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idVariacao);
            ps.setBigDecimal(4, new BigDecimal("50.000"));
            ps.execute();
        }
        return idVariacao;
    }

    private long criarCliente(String token, String nome) throws Exception {
        String respCat = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"PADRAO %s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idCategoria = ((Number) JsonPath.read(respCat, "$.idCategoriaCliente")).longValue();
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":false,"nome":"%s","idCategoriaCliente":%d,"limiteCredito":"10000.00"}
                                """.formatted(nome, idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    private long criarFuncionario(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"%s\",\"percComissao\":0}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();
    }

    private long carteiraDinheiro(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        return carteiras.stream()
                .filter(x -> "DINHEIRO".equals(x.get("nomeCarteira")))
                .map(x -> ((Number) x.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
    }

    private void abrirCaixa(String token, long idCarteira) throws Exception {
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteira)))
                .andExpect(status().isOk());
    }

    private long efetivarVenda(String token, long idVariacao, long idCliente, long idFuncionario,
                               long idCarteira, int qtd, String valorPago) throws Exception {
        String corpo = """
                {"itens":[{"idVariacao":%d,"qtd":%d}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":1}]}
                """.formatted(idVariacao, qtd, idCliente, idFuncionario, idCarteira, valorPago);
        String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
    }

    private long devolver(String token, long numeroVenda, long idVariacao, String qtd) throws Exception {
        String corpo = """
                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":%s}]}
                """.formatted(numeroVenda, idVariacao, qtd);
        String resp = mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idDevolucao")).longValue();
    }

    private long criarFornecedor(String token, String razaoSocial) throws Exception {
        String resp = mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"razaoSocial":"%s","idPlanoContas":"3.03.001","ativo":true}
                                """.formatted(razaoSocial)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFornecedor")).longValue();
    }

    /** Garante a conta de despesa; 409 é esperado quando ela já veio do plano padrão do signup. */
    private void criarContaDespesa(String token, String codigo, String descricao, String natureza) throws Exception {
        int status = mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"%s","descricao":"%s","tipoMovimento":"DEBITO","natureza":"%s",
                                 "incluiDre":true,"grupoDre":"DESPESA_FIXA","incluiFluxoCaixa":true,
                                 "grupoDfc":"OPERACIONAL"}
                                """.formatted(codigo, descricao, natureza)))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(201, 409);
    }

    /**
     * Lançamento direto em {@code contas_pagar} — a Entrada de Produtos só sabe lançar na conta de
     * compra, e estes testes precisam de contas de despesa variadas e de datas de pagamento
     * controladas.
     *
     * @param diaDoPagamento {@code null} = conta ainda não paga.
     */
    private void lancarContaPagar(Connection c, long idTenant, long idEmpresa, long idFornecedor,
                                  String idPlanoContas, String valor, LocalDate diaDoPagamento)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO contas_pagar
                    (id_tenant, id_empresa, id_fornecedor, id_plano_contas, data_lancamento, data_vencimento,
                     data_pagamento, valor_pagar, valor_pago)
                VALUES (?, ?, ?, ?, now(), now(),
                        CASE WHEN ?::date IS NULL THEN NULL
                             -- Meio-dia de Brasília: qualquer hora perto da virada faria o
                             -- `::date` do serviço cair no dia vizinho. Ver o aviso da classe.
                             ELSE (?::date + time '12:00') AT TIME ZONE 'America/Sao_Paulo' END,
                        ?::numeric, ?::numeric)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idFornecedor);
            ps.setString(4, idPlanoContas);
            ps.setObject(5, diaDoPagamento);
            ps.setObject(6, diaDoPagamento);
            ps.setString(7, valor);
            ps.setString(8, diaDoPagamento == null ? "0" : valor);
            ps.execute();
        }
    }

    private String gerar(String token, LocalDate inicio, LocalDate fim) throws Exception {
        return mvc.perform(get("/api/v1/relatorios/lucratividade").header("Authorization", "Bearer " + token)
                        .param("dataInicial", inicio.toString()).param("dataFinal", fim.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static double numero(String json, String caminho) {
        Object v = JsonPath.read(json, caminho);
        return ((Number) v).doubleValue();
    }

    /** ⚠️ Filtro do JsonPath devolve SEMPRE um array, mesmo casando uma linha só — e indexar
     *  depois do filtro (…)[0] não funciona: a indexação tem de ser em Java. */
    private static double numeroDaConta(String json, String codigo, String campo) {
        List<Number> valores = JsonPath.read(
                json, "$.contasPagas[?(@.idPlanoContas=='" + codigo + "')]." + campo);
        assertThat(valores).as("conta " + codigo + " no relatório").hasSize(1);
        return valores.get(0).doubleValue();
    }

    // ------------------------------------------------------------------ critérios

    /** Critério 1 — venda 100, custo 60: lucro bruto 40, margem 40%. */
    @Test
    void vendaSemDevolucaoTrazVendaCustoLucroEMargem() throws Exception {
        TenantNovo tenant = assinarNovoTenant("simples");
        long idTenant = extrairIdTenant(tenant.token());
        LocalDate hoje;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            hoje = hojeNoFusoDaLoja(c);
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacaoComEstoque(c, idTenant, idEmpresa,
                    criarProduto(tenant.token(), "CAMISETA LUCRO"));
        }
        long idCarteira = carteiraDinheiro(tenant.token());
        abrirCaixa(tenant.token(), idCarteira);
        efetivarVenda(tenant.token(), idVariacao, criarCliente(tenant.token(), "Cliente Lucro"),
                criarFuncionario(tenant.token(), "Vendedor Lucro"), idCarteira, 1, "100.00");

        String json = gerar(tenant.token(), hoje, hoje);

        assertThat(numero(json, "$.vendaLiquida")).isEqualTo(100.00);
        assertThat(numero(json, "$.custoMercadoriaVendida")).isEqualTo(60.00);
        assertThat(numero(json, "$.lucroBruto")).isEqualTo(40.00);
        assertThat(numero(json, "$.percentualLucroBruto")).isEqualTo(40.00);
        // Sem devolução, as duas bases do item 6 coincidem — e isso é informação, não redundância.
        assertThat(numero(json, "$.percentualSobreVendaBruta"))
                .isEqualTo(numero(json, "$.percentualSobreVendaLiquida"));
    }

    /**
     * Critério 2 — a devolução abate os <b>dois</b> lados: venda 300 (3 peças) menos devolução de
     * 100 dá 200, e o custo cai de 180 para 120.
     */
    @Test
    void devolucaoAbateVendaEReverteCusto() throws Exception {
        TenantNovo tenant = assinarNovoTenant("devolucao");
        long idTenant = extrairIdTenant(tenant.token());
        LocalDate hoje;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            hoje = hojeNoFusoDaLoja(c);
            idVariacao = criarVariacaoComEstoque(c, idTenant, buscarIdEmpresa(c),
                    criarProduto(tenant.token(), "CALCA DEVOLVIDA"));
        }
        long idCarteira = carteiraDinheiro(tenant.token());
        abrirCaixa(tenant.token(), idCarteira);
        long idVenda = efetivarVenda(tenant.token(), idVariacao, criarCliente(tenant.token(), "Cliente Dev"),
                criarFuncionario(tenant.token(), "Vendedor Dev"), idCarteira, 3, "300.00");
        devolver(tenant.token(), idVenda, idVariacao, "1");

        String json = gerar(tenant.token(), hoje, hoje);

        assertThat(numero(json, "$.vendaBruta")).isEqualTo(300.00);
        assertThat(numero(json, "$.devolucoes")).isEqualTo(100.00);
        assertThat(numero(json, "$.vendaLiquida")).isEqualTo(200.00);
        assertThat(numero(json, "$.custoMercadoriaVendida")).isEqualTo(120.00);
        assertThat(numero(json, "$.lucroBruto")).isEqualTo(80.00);
        // ⭐ Aqui as duas bases do item 6 divergem, e é exatamente o que a devolução significa.
        assertThat(numero(json, "$.percentualSobreVendaBruta"))
                .isLessThan(numero(json, "$.percentualSobreVendaLiquida"));
    }

    /** Critério 3 — venda cancelada não entra em número nenhum. */
    @Test
    void vendaCanceladaNaoEntra() throws Exception {
        TenantNovo tenant = assinarNovoTenant("cancelada");
        long idTenant = extrairIdTenant(tenant.token());
        LocalDate hoje;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            hoje = hojeNoFusoDaLoja(c);
            idVariacao = criarVariacaoComEstoque(c, idTenant, buscarIdEmpresa(c),
                    criarProduto(tenant.token(), "PRODUTO CANCELADO"));
        }
        long idCarteira = carteiraDinheiro(tenant.token());
        abrirCaixa(tenant.token(), idCarteira);
        long idVenda = efetivarVenda(tenant.token(), idVariacao, criarCliente(tenant.token(), "Cliente Canc"),
                criarFuncionario(tenant.token(), "Vendedor Canc"), idCarteira, 1, "100.00");
        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"TESTE DE CANCELAMENTO\"}"))
                .andExpect(status().isOk());

        String json = gerar(tenant.token(), hoje, hoje);

        assertThat(numero(json, "$.vendaBruta")).isZero();
        assertThat(numero(json, "$.custoMercadoriaVendida")).isZero();
        assertThat(numero(json, "$.lucroBruto")).isZero();
    }

    /**
     * Critério 4 — devolução <b>cancelada</b> não deduz venda nem reverte custo.
     *
     * <p>⚠️ Cancelar não apaga as linhas {@code DEVOLUCAO} do ledger: só marca
     * {@code venda_devolucao.cancelada} e lança o movimento compensatório. Sem o filtro, o mês
     * erraria nas duas pontas — foi um achado real de auditoria na DRE.
     */
    @Test
    void devolucaoCanceladaNaoDeduzNada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("devcancelada");
        long idTenant = extrairIdTenant(tenant.token());
        LocalDate hoje;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            hoje = hojeNoFusoDaLoja(c);
            idVariacao = criarVariacaoComEstoque(c, idTenant, buscarIdEmpresa(c),
                    criarProduto(tenant.token(), "PRODUTO DEV CANC"));
        }
        long idCarteira = carteiraDinheiro(tenant.token());
        abrirCaixa(tenant.token(), idCarteira);
        long idVenda = efetivarVenda(tenant.token(), idVariacao, criarCliente(tenant.token(), "Cliente DevCanc"),
                criarFuncionario(tenant.token(), "Vendedor DevCanc"), idCarteira, 2, "200.00");
        long idDevolucao = devolver(tenant.token(), idVenda, idVariacao, "1");
        mvc.perform(post("/api/v1/vendas/cancelamento-devolucao/" + idDevolucao)
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"TESTE\"}"))
                .andExpect(status().isOk());

        String json = gerar(tenant.token(), hoje, hoje);

        assertThat(numero(json, "$.devolucoes")).isZero();
        assertThat(numero(json, "$.vendaLiquida")).isEqualTo(200.00);
        assertThat(numero(json, "$.custoMercadoriaVendida")).isEqualTo(120.00);
    }

    /** Critério 5 — conta de despesa paga no período aparece no item 5 e reduz o lucro líquido. */
    @Test
    void contaDespesaPagaNoPeriodoReduzOLucroLiquido() throws Exception {
        TenantNovo tenant = assinarNovoTenant("despesa");
        long idTenant = extrairIdTenant(tenant.token());
        criarContaDespesa(tenant.token(), "4.01.001", "Aluguel", "ANALITICA");
        long idFornecedor = criarFornecedor(tenant.token(), "LOCADORA DE IMOVEIS");
        LocalDate hoje;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            hoje = hojeNoFusoDaLoja(c);
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacaoComEstoque(c, idTenant, idEmpresa,
                    criarProduto(tenant.token(), "PRODUTO DESPESA"));
            lancarContaPagar(c, idTenant, idEmpresa, idFornecedor, "4.01.001", "10.00", hoje);
        }
        long idCarteira = carteiraDinheiro(tenant.token());
        abrirCaixa(tenant.token(), idCarteira);
        efetivarVenda(tenant.token(), idVariacao, criarCliente(tenant.token(), "Cliente Desp"),
                criarFuncionario(tenant.token(), "Vendedor Desp"), idCarteira, 1, "100.00");

        String json = gerar(tenant.token(), hoje, hoje);

        List<String> contas = JsonPath.read(json, "$.contasPagas[*].idPlanoContas");
        assertThat(contas).contains("4.01.001");
        assertThat(numero(json, "$.totalContasPagas")).isEqualTo(10.00);
        assertThat(numero(json, "$.lucroLiquido")).isEqualTo(30.00);
        // % da despesa sobre a venda (10/100) e sobre o lucro bruto (10/40).
        assertThat(numeroDaConta(json, "4.01.001", "percentualSobreVenda"))
                .isEqualTo(10.00);
        assertThat(numeroDaConta(json, "4.01.001", "percentualSobreLucroBruto"))
                .isEqualTo(25.00);
    }

    /**
     * ⭐ Critério 6 — <b>compra de mercadoria paga no período NÃO entra nas contas pagas</b>.
     *
     * <p>É o critério que justifica o relatório inteiro estar certo: a mercadoria já está contada
     * no CMV, quando sai vendida. Somá-la de novo no desembolso contaria a mesma coisa duas vezes
     * e transformaria em prejuízo um mês que deu lucro — e os dois números são plausíveis isolados,
     * então ninguém perceberia olhando.
     */
    @Test
    void compraDeMercadoriaPagaNaoEntraNasContasPagas() throws Exception {
        TenantNovo tenant = assinarNovoTenant("compra");
        long idTenant = extrairIdTenant(tenant.token());
        long idFornecedor = criarFornecedor(tenant.token(), "DISTRIBUIDORA ATACADO");
        LocalDate hoje;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            hoje = hojeNoFusoDaLoja(c);
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacaoComEstoque(c, idTenant, idEmpresa,
                    criarProduto(tenant.token(), "PRODUTO COMPRADO"));
            // 5.000 de mercadoria paga hoje — muito maior que a venda, justamente para que o
            // lucro líquido virasse prejuízo se ela fosse contada.
            lancarContaPagar(c, idTenant, idEmpresa, idFornecedor, "3.03.001", "5000.00", hoje);
        }
        long idCarteira = carteiraDinheiro(tenant.token());
        abrirCaixa(tenant.token(), idCarteira);
        efetivarVenda(tenant.token(), idVariacao, criarCliente(tenant.token(), "Cliente Compra"),
                criarFuncionario(tenant.token(), "Vendedor Compra"), idCarteira, 1, "100.00");

        String json = gerar(tenant.token(), hoje, hoje);

        List<String> contas = JsonPath.read(json, "$.contasPagas[*].idPlanoContas");
        assertThat(contas).doesNotContain("3.03.001");
        assertThat(numero(json, "$.totalContasPagas")).isZero();
        assertThat(numero(json, "$.lucroLiquido")).isEqualTo(40.00);
    }

    /** Critério 7 — o corte é a data de PAGAMENTO, não a de vencimento nem a de lançamento. */
    @Test
    void oCorteDasContasEhADataDePagamento() throws Exception {
        TenantNovo tenant = assinarNovoTenant("corte");
        long idTenant = extrairIdTenant(tenant.token());
        criarContaDespesa(tenant.token(), "4.01.002", "Energia", "ANALITICA");
        criarContaDespesa(tenant.token(), "4.01.003", "Agua", "ANALITICA");
        long idFornecedor = criarFornecedor(tenant.token(), "CONCESSIONARIA");
        LocalDate hoje;
        try (Connection c = abrirConexao(idTenant)) {
            hoje = hojeNoFusoDaLoja(c);
            long idEmpresa = buscarIdEmpresa(c);
            // Paga HOJE (entra) e paga daqui a 10 dias (não entra) — as duas lançadas hoje.
            lancarContaPagar(c, idTenant, idEmpresa, idFornecedor, "4.01.002", "70.00", hoje);
            lancarContaPagar(c, idTenant, idEmpresa, idFornecedor, "4.01.003", "90.00", hoje.plusDays(10));
        }

        String json = gerar(tenant.token(), hoje, hoje);

        List<String> contas = JsonPath.read(json, "$.contasPagas[*].idPlanoContas");
        assertThat(contas).contains("4.01.002").doesNotContain("4.01.003");
        assertThat(numero(json, "$.totalContasPagas")).isEqualTo(70.00);
    }

    /** Critério 8 — sem venda, todo percentual é `null` (nunca zero) e o lucro é o negativo do pago. */
    @Test
    void periodoSemVendaTemPercentualNuloENaoZero() throws Exception {
        TenantNovo tenant = assinarNovoTenant("semvenda");
        long idTenant = extrairIdTenant(tenant.token());
        criarContaDespesa(tenant.token(), "4.01.004", "Contador", "ANALITICA");
        long idFornecedor = criarFornecedor(tenant.token(), "ESCRITORIO CONTABIL");
        LocalDate hoje;
        try (Connection c = abrirConexao(idTenant)) {
            hoje = hojeNoFusoDaLoja(c);
            lancarContaPagar(c, idTenant, buscarIdEmpresa(c), idFornecedor, "4.01.004", "250.00", hoje);
        }

        mvc.perform(get("/api/v1/relatorios/lucratividade").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje.toString()).param("dataFinal", hoje.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendaLiquida").value(0))
                // ⚠️ null, não 0: um 0% impresso afirmaria "margem zero" onde não houve venda.
                .andExpect(jsonPath("$.percentualLucroBruto").doesNotExist())
                .andExpect(jsonPath("$.percentualSobreVendaBruta").doesNotExist())
                .andExpect(jsonPath("$.percentualSobreVendaLiquida").doesNotExist())
                .andExpect(jsonPath("$.contasPagas[0].percentualSobreVenda").doesNotExist())
                .andExpect(jsonPath("$.lucroLiquido").value(-250.00));
    }

    /** Critério 9 — ADMIN-only. */
    @Test
    void operadorRecebe403() throws Exception {
        TenantNovo tenant = assinarNovoTenant("papel");
        long idTenant = extrairIdTenant(tenant.token());
        LocalDate hoje;
        long idEmpresa;
        try (Connection c = abrirConexao(idTenant)) {
            hoje = hojeNoFusoDaLoja(c);
            idEmpresa = buscarIdEmpresa(c);
        }
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nome":"OPERADOR LUCRO","email":"operador@lojalucro-papel.com",
                                 "senha":"segredo123","ativo":true,"idsEmpresa":[%d]}
                                """.formatted(idEmpresa)))
                .andExpect(status().isCreated());
        String respLogin = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .content("""
                                {"slug":"%s","email":"operador@lojalucro-papel.com","senha":"segredo123"}
                                """.formatted(tenant.slug())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tokenOperador = JsonPath.read(respLogin, "$.token");

        mvc.perform(get("/api/v1/relatorios/lucratividade").header("Authorization", "Bearer " + tokenOperador)
                        .param("dataInicial", hoje.toString()).param("dataFinal", hoje.toString()))
                .andExpect(status().isForbidden());
    }

    /** Critério 10 — P8: o relatório de um tenant nunca enxerga o movimento do outro. */
    @Test
    void isolamentoEntreTenants() throws Exception {
        TenantNovo a = assinarNovoTenant("iso-a");
        TenantNovo b = assinarNovoTenant("iso-b");
        long idTenantA = extrairIdTenant(a.token());
        LocalDate hoje;
        long idVariacaoA;
        try (Connection c = abrirConexao(idTenantA)) {
            hoje = hojeNoFusoDaLoja(c);
            idVariacaoA = criarVariacaoComEstoque(c, idTenantA, buscarIdEmpresa(c),
                    criarProduto(a.token(), "PRODUTO DO TENANT A"));
        }
        long idCarteira = carteiraDinheiro(a.token());
        abrirCaixa(a.token(), idCarteira);
        efetivarVenda(a.token(), idVariacaoA, criarCliente(a.token(), "Cliente A"),
                criarFuncionario(a.token(), "Vendedor A"), idCarteira, 1, "100.00");

        assertThat(numero(gerar(a.token(), hoje, hoje), "$.vendaLiquida")).isEqualTo(100.00);
        assertThat(numero(gerar(b.token(), hoje, hoje), "$.vendaLiquida")).isZero();
    }

    /** Critério 11 — o filtro de empresa vale para venda e para contas pagas ao mesmo tempo. */
    @Test
    void filtroDeEmpresaValeParaVendaEParaContasPagas() throws Exception {
        TenantNovo tenant = assinarNovoTenant("empresa");
        long idTenant = extrairIdTenant(tenant.token());
        criarContaDespesa(tenant.token(), "4.01.005", "Telefone", "ANALITICA");
        long idFornecedor = criarFornecedor(tenant.token(), "OPERADORA TELECOM");

        String respEmpresa = mvc.perform(post("/api/v1/empresas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"FILIAL DOIS\",\"nomeFantasia\":\"FILIAL DOIS\"}"))
                .andReturn().getResponse().getContentAsString();

        LocalDate hoje;
        long idEmpresaPrincipal;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            hoje = hojeNoFusoDaLoja(c);
            idEmpresaPrincipal = buscarIdEmpresa(c);
            idVariacao = criarVariacaoComEstoque(c, idTenant, idEmpresaPrincipal,
                    criarProduto(tenant.token(), "PRODUTO FILIAL"));
            lancarContaPagar(c, idTenant, idEmpresaPrincipal, idFornecedor, "4.01.005", "20.00", hoje);
        }
        long idCarteira = carteiraDinheiro(tenant.token());
        abrirCaixa(tenant.token(), idCarteira);
        efetivarVenda(tenant.token(), idVariacao, criarCliente(tenant.token(), "Cliente Filial"),
                criarFuncionario(tenant.token(), "Vendedor Filial"), idCarteira, 1, "100.00");

        // Filtrando pela empresa que TEM o movimento: tudo aparece.
        String comMovimento = mvc.perform(get("/api/v1/relatorios/lucratividade")
                        .header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje.toString()).param("dataFinal", hoje.toString())
                        .param("idsEmpresa", String.valueOf(idEmpresaPrincipal)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(numero(comMovimento, "$.vendaLiquida")).isEqualTo(100.00);
        assertThat(numero(comMovimento, "$.totalContasPagas")).isEqualTo(20.00);

        // Filtrando pela outra empresa: venda E contas pagas zeram juntas.
        long idOutraEmpresa = ((Number) JsonPath.read(respEmpresa, "$.idEmpresa")).longValue();
        String semMovimento = mvc.perform(get("/api/v1/relatorios/lucratividade")
                        .header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje.toString()).param("dataFinal", hoje.toString())
                        .param("idsEmpresa", String.valueOf(idOutraEmpresa)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(numero(semMovimento, "$.vendaLiquida")).isZero();
        assertThat(numero(semMovimento, "$.totalContasPagas")).isZero();
    }
}
