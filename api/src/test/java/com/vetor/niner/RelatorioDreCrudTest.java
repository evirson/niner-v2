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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Relatório de DRE (docs/telas/relatorio-dre.md) — critérios de aceitação da spec: regime de
 * competência × caixa, CMV derivado do ledger, compra de mercadoria fora da DRE, venda cancelada
 * fora, e a permissão ADMIN-only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RelatorioDreCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Dre %s","email":"dono%s@lojadre.com",
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

    /** Produto com custo 40 e venda 100 — números redondos pros critérios da spec. */
    private long criarProduto(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"40.00","percentualVenda":"150","precoVenda":"100.00"}
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
                                 "pcMinima":1,"pcMaxima":12,"permiteReceberCrediario":true}
                                """.formatted(nome, categoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCarteira")).longValue();
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

    private void abrirCaixaDinheiro(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        long idCarteira = carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
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
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long criarVariacaoComEstoque(Connection c, long idTenant, long idEmpresa, long idProduto) throws SQLException {
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

    private long efetivarVenda(String token, long idVariacao, long idCliente, long idFuncionario,
                               long idCarteira, String valorPago, int parcelas) throws Exception {
        String corpo = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":%d}]}
                """.formatted(idVariacao, idCliente, idFuncionario, idCarteira, valorPago, parcelas);
        String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
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

    /** Lançamento direto em `contas_pagar` — a Entrada de Produtos só sabe lançar na conta de
     *  compra, e estes testes precisam de contas de despesa/custo variadas. */
    private void lancarContaPagar(Connection c, long idTenant, long idEmpresa, long idFornecedor,
                                  String idPlanoContas, String valor, boolean paga) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO contas_pagar
                    (id_tenant, id_empresa, id_fornecedor, id_plano_contas, data_lancamento, data_vencimento,
                     data_pagamento, valor_pagar, valor_pago)
                VALUES (?, ?, ?, ?, now(), now(), ?, ?::numeric, ?::numeric)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idFornecedor);
            ps.setString(4, idPlanoContas);
            if (paga) {
                ps.setObject(5, java.time.OffsetDateTime.now());
            } else {
                ps.setObject(5, null);
            }
            ps.setString(6, valor);
            ps.setString(7, paga ? valor : "0");
            ps.execute();
        }
    }

    /** Garante a conta de despesa; 409 é esperado quando ela já vem do plano padrão do signup. */
    private void criarContaDespesaFixa(String token, String codigo, String descricao, String natureza) throws Exception {
        int status = mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"%s","descricao":"%s","tipoMovimento":"DEBITO","natureza":"%s",
                                 "incluiDre":true,"grupoDre":"DESPESA_FIXA","incluiFluxoCaixa":true,
                                 "grupoDfc":"OPERACIONAL"}
                                """.formatted(codigo, descricao, natureza)))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isIn(201, 409);
    }

    private String hoje() {
        return java.time.LocalDate.now().toString();
    }

    /** Valor de uma linha da DRE pela chave (conta analítica ou subtotal). */
    private static double valorDaLinha(String json, String chave) {
        List<Double> valores = JsonPath.read(json, "$.linhas[?(@.chave=='" + chave + "')].valor");
        return valores.isEmpty() ? 0d : valores.get(0);
    }

    private String gerarDre(String token, String regime) throws Exception {
        return mvc.perform(get("/api/v1/relatorios/dre").header("Authorization", "Bearer " + token)
                        .param("dataInicial", hoje()).param("dataFinal", hoje())
                        .param("regime", regime))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void vendaAVistaEmCompetenciaTrazReceitaCmvEMargem() throws Exception {
        TenantNovo tenant = assinarNovoTenant("avista");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto DRE a vista");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO DRE AVISTA", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente DRE a vista");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor DRE a vista");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacaoComEstoque(c, idTenant, idEmpresa, idProduto);
            efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "100.00", 1);
        }

        String json = gerarDre(tenant.token(), "COMPETENCIA");
        // Receita 100, CMV 40 (negativo, já com o sinal do efeito no resultado), margem 60.
        org.assertj.core.api.Assertions.assertThat(valorDaLinha(json, "1.01.001")).isEqualTo(100.00);
        org.assertj.core.api.Assertions.assertThat(valorDaLinha(json, "3.01.001")).isEqualTo(-40.00);
        org.assertj.core.api.Assertions.assertThat(valorDaLinha(json, "RECEITA_LIQUIDA")).isEqualTo(100.00);
        org.assertj.core.api.Assertions.assertThat(valorDaLinha(json, "MARGEM_CONTRIBUICAO")).isEqualTo(60.00);
    }

    /**
     * O critério que justifica os dois regimes existirem: venda no crediário entra integral na
     * competência no dia da venda, e no caixa só quando a parcela é recebida (aqui, nunca).
     */
    @Test
    void vendaNoCrediarioEntraEmCompetenciaMasNaoEmCaixa() throws Exception {
        TenantNovo tenant = assinarNovoTenant("crediario");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto DRE crediario");
        long idCarteira = criarTipoCarteira(tenant.token(), "CREDIARIO DRE", "CREDIARIO");
        long idCliente = criarCliente(tenant.token(), "Cliente DRE crediario");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor DRE crediario");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacaoComEstoque(c, idTenant, idEmpresa, idProduto);
            efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "100.00", 2);
        }

        org.assertj.core.api.Assertions
                .assertThat(valorDaLinha(gerarDre(tenant.token(), "COMPETENCIA"), "1.01.001"))
                .isEqualTo(100.00);
        org.assertj.core.api.Assertions
                .assertThat(valorDaLinha(gerarDre(tenant.token(), "CAIXA"), "1.01.001"))
                .isEqualTo(0d);
    }

    @Test
    void vendaCanceladaNaoAfetaNenhumRegime() throws Exception {
        TenantNovo tenant = assinarNovoTenant("cancelada");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto DRE cancelada");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO DRE CANCELADA", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente DRE cancelada");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor DRE cancelada");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacaoComEstoque(c, idTenant, idEmpresa, idProduto);
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "100.00", 1);
        }

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"TESTE DRE CANCELAMENTO\"}"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions
                .assertThat(valorDaLinha(gerarDre(tenant.token(), "COMPETENCIA"), "1.01.001"))
                .isEqualTo(0d);
        org.assertj.core.api.Assertions
                .assertThat(valorDaLinha(gerarDre(tenant.token(), "CAIXA"), "1.01.001"))
                .isEqualTo(0d);
    }

    /**
     * Compra de mercadoria é estoque, não despesa — a conta 3.03.001 tem `inclui_dre = false` no
     * seed, e é o que impede a DRE de contar o estoque duas vezes (aqui, e de novo no CMV).
     */
    @Test
    void compraDeMercadoriaNaoEntraNaDre() throws Exception {
        TenantNovo tenant = assinarNovoTenant("compra");
        long idTenant = extrairIdTenant(tenant.token());
        long idFornecedor = criarFornecedor(tenant.token(), "FORNECEDOR DRE COMPRA");

        try (Connection c = abrirConexao(idTenant)) {
            lancarContaPagar(c, idTenant, buscarIdEmpresa(c), idFornecedor, "3.03.001", "5000.00", false);
        }

        String json = gerarDre(tenant.token(), "COMPETENCIA");
        org.assertj.core.api.Assertions.assertThat(valorDaLinha(json, "3.03.001")).isEqualTo(0d);
        org.assertj.core.api.Assertions.assertThat(valorDaLinha(json, "RESULTADO_LIQUIDO")).isEqualTo(0d);
    }

    /**
     * Competência reconhece a despesa pelo lançamento (fato gerador); caixa, só quando paga.
     * Uma conta lançada hoje e ainda não paga aparece num regime e não no outro.
     */
    @Test
    void despesaLancadaENaoPagaEntraSoEmCompetencia() throws Exception {
        TenantNovo tenant = assinarNovoTenant("despesa");
        long idTenant = extrairIdTenant(tenant.token());
        long idFornecedor = criarFornecedor(tenant.token(), "FORNECEDOR DRE DESPESA");

        // Desde 2026-08-14 o signup copia o plano padrão completo, então a conta de aluguel
        // (4.01.001) já existe — o teste só garante isso, aceitando 409.
        criarContaDespesaFixa(tenant.token(), "4.00.000", "DESPESAS FIXAS", "SINTETICA");
        criarContaDespesaFixa(tenant.token(), "4.01.000", "Ocupacao", "SINTETICA");
        criarContaDespesaFixa(tenant.token(), "4.01.001", "Aluguel", "ANALITICA");

        try (Connection c = abrirConexao(idTenant)) {
            lancarContaPagar(c, idTenant, buscarIdEmpresa(c), idFornecedor, "4.01.001", "300.00", false);
        }

        org.assertj.core.api.Assertions
                .assertThat(valorDaLinha(gerarDre(tenant.token(), "COMPETENCIA"), "DESPESA_FIXA"))
                .isEqualTo(-300.00);
        org.assertj.core.api.Assertions
                .assertThat(valorDaLinha(gerarDre(tenant.token(), "CAIXA"), "DESPESA_FIXA"))
                .isEqualTo(0d);
    }

    /**
     * Regressão de 2026-08-17. A DRE em regime CAIXA usava {@code COALESCE(valor_pago, valor_pagar)},
     * que <b>nunca</b> caía no fallback: {@code contas_pagar.valor_pago} é
     * {@code numeric(12,2) NOT NULL DEFAULT 0} (V026:21) e {@code ContaPagarService} grava ZERO
     * quando a tela manda o campo vazio — a coluna jamais é NULL.
     *
     * <p>Resultado: a baixa "cheia" (operador informa só a Data de Pagamento, que é o caminho
     * normal) lançava o valor certo em {@code caixa_detalhe} e <b>R$ 0,00</b> na DRE. Fluxo de
     * Caixa e DRE divergiam sobre a mesma baixa e o lucro saía inflado.
     *
     * <p>O helper {@code lancarContaPagar} não pegava isso porque sempre preenche
     * {@code valor_pago = valor_pagar} — este teste existe justamente para o caso que ele não cobre.
     */
    @Test
    void despesaBaixadaSemValorPagoDigitadoEntraNaDreDeCaixaPeloValorDaConta() throws Exception {
        TenantNovo tenant = assinarNovoTenant("baixa-cheia");
        long idTenant = extrairIdTenant(tenant.token());
        long idFornecedor = criarFornecedor(tenant.token(), "FORNECEDOR BAIXA CHEIA");

        criarContaDespesaFixa(tenant.token(), "4.00.000", "DESPESAS FIXAS", "SINTETICA");
        criarContaDespesaFixa(tenant.token(), "4.01.000", "Ocupacao", "SINTETICA");
        criarContaDespesaFixa(tenant.token(), "4.01.001", "Aluguel", "ANALITICA");

        try (Connection c = abrirConexao(idTenant)) {
            lancarContaPagarBaixadaSemValorPago(
                    c, idTenant, buscarIdEmpresa(c), idFornecedor, "4.01.001", "500.00");
        }

        // Competência sempre usou valor_pagar — continua certo.
        org.assertj.core.api.Assertions
                .assertThat(valorDaLinha(gerarDre(tenant.token(), "COMPETENCIA"), "DESPESA_FIXA"))
                .isEqualTo(-500.00);
        // Caixa: antes do fix vinha 0.00. Tem que trazer o valor da conta, igual ao que o
        // movimento de dinheiro grava (ContaPagarService.sincronizarMovimentoDeDinheiro).
        org.assertj.core.api.Assertions
                .assertThat(valorDaLinha(gerarDre(tenant.token(), "CAIXA"), "DESPESA_FIXA"))
                .isEqualTo(-500.00);
    }

    /** Baixa "cheia": {@code data_pagamento} preenchida e {@code valor_pago} em 0 — o estado que a
     *  tela produz quando o operador informa só a data. */
    private void lancarContaPagarBaixadaSemValorPago(Connection c, long idTenant, long idEmpresa,
                                                     long idFornecedor, String idPlanoContas,
                                                     String valor) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO contas_pagar
                    (id_tenant, id_empresa, id_fornecedor, id_plano_contas, data_lancamento, data_vencimento,
                     data_pagamento, valor_pagar, valor_pago)
                VALUES (?, ?, ?, ?, now(), now(), now(), ?::numeric, 0)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idFornecedor);
            ps.setString(4, idPlanoContas);
            ps.setString(5, valor);
            ps.execute();
        }
    }

    @Test
    void operadorRecebe403() throws Exception {
        TenantNovo tenant = assinarNovoTenant("papel");
        long idTenant = extrairIdTenant(tenant.token());
        long idEmpresa;
        try (Connection c = abrirConexao(idTenant)) {
            idEmpresa = buscarIdEmpresa(c);
        }
        String corpoUsuario = """
                {"nome":"OPERADOR DRE","email":"operador@lojadre-papel.com","senha":"segredo123",
                 "ativo":true,"idsEmpresa":[%d]}
                """.formatted(idEmpresa);
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoUsuario))
                .andExpect(status().isCreated());

        String login = """
                {"slug":"%s","email":"operador@lojadre-papel.com","senha":"segredo123"}
                """.formatted(tenant.slug());
        String respLogin = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tokenOperador = JsonPath.read(respLogin, "$.token");

        mvc.perform(get("/api/v1/relatorios/dre").header("Authorization", "Bearer " + tokenOperador)
                        .param("dataInicial", hoje()).param("dataFinal", hoje()))
                .andExpect(status().isForbidden());
    }

    @Test
    void comparacaoPeriodoAnteriorUsaMesmoNumeroDeDias() throws Exception {
        TenantNovo tenant = assinarNovoTenant("comparacao");
        java.time.LocalDate inicio = java.time.LocalDate.of(2026, 3, 1);
        java.time.LocalDate fim = java.time.LocalDate.of(2026, 3, 31);

        mvc.perform(get("/api/v1/relatorios/dre").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", inicio.toString()).param("dataFinal", fim.toString())
                        .param("comparar", "PERIODO_ANTERIOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodoComparado.dataInicial").value("2026-01-29"))
                .andExpect(jsonPath("$.periodoComparado.dataFinal").value("2026-02-28"));
    }
}
