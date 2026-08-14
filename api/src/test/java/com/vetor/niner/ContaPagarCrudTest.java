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

/** CRUD de Contas a Pagar / Pagas (docs/telas/contas-pagar.md). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ContaPagarCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Contas Pagar %s","email":"dono%s@lojacontaspagar.com",
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

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    private long buscarIdEmpresa(long idTenant) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void criarPlano(String token, String codigo) throws Exception {
        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"%s","descricao":"despesa teste %s","tipoMovimento":"DEBITO","natureza":"ANALITICA",
                                 "incluiDre":false,"incluiFluxoCaixa":false}
                                """.formatted(codigo, codigo)))
                .andExpect(status().isCreated());
    }

    private long criarFornecedor(String token, String razaoSocial) throws Exception {
        criarPlano(token, "2.00.000");
        String resp = mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"%s\",\"idPlanoContas\":\"2.00.000\"}".formatted(razaoSocial)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFornecedor")).longValue();
    }

    private record Base(String token, long idFornecedor, long idEmpresa) {
    }

    /** Cria tenant + fornecedor + plano de contas "1.00.000" prontos pra uso na conta a pagar. */
    private Base prepararBase(String sufixo) throws Exception {
        String token = assinarNovoTenant(sufixo);
        long idFornecedor = criarFornecedor(token, "Fornecedor " + sufixo);
        criarPlano(token, "1.00.000");
        long idEmpresa = buscarIdEmpresa(extrairIdTenant(token));
        return new Base(token, idFornecedor, idEmpresa);
    }

    /** Abre o caixa do usuário na carteira DINHEIRO — pré-requisito da baixa em dinheiro desde
     *  2026-08-23 (o pagamento vira movimento no caixa aberto, docs/telas/fluxo-caixa.md). */
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
                        .content("{\"idCarteira\":%d,\"saldoInicial\":0}".formatted(idCarteira)))
                .andExpect(status().isOk());
    }

    private long criarContaPagar(Base base, String numeroDuplicata, String dataVencimento, String valorPagar) throws Exception {
        String resp = mvc.perform(post("/api/v1/contas-pagar").header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"1.00.000","notaFiscal":100,
                                 "numeroDuplicata":"%s","dataLancamento":"2026-07-30T12:00:00Z",
                                 "dataVencimento":"%sT12:00:00Z","valorPagar":%s}
                                """.formatted(base.idFornecedor(), base.idEmpresa(), numeroDuplicata, dataVencimento, valorPagar)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idContaPagar")).longValue();
    }

    @Test
    void criaContaPagarComDadosCompletos() throws Exception {
        Base base = prepararBase("completo");

        String corpo = """
                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"1.00.000","notaFiscal":321,
                 "numeroDuplicata":"DUP-1","dataLancamento":"2026-07-30T12:00:00Z",
                 "dataVencimento":"2026-08-15T12:00:00Z","valorPagar":1500.50,"observacoes":"compra teste"}
                """.formatted(base.idFornecedor(), base.idEmpresa());

        mvc.perform(post("/api/v1/contas-pagar").header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idFornecedor").value(base.idFornecedor()))
                .andExpect(jsonPath("$.nomeFornecedor").exists())
                .andExpect(jsonPath("$.idEmpresa").value(base.idEmpresa()))
                .andExpect(jsonPath("$.nomeEmpresa").exists())
                .andExpect(jsonPath("$.idPlanoContas").value("1.00.000"))
                .andExpect(jsonPath("$.descricaoPlanoContas").exists())
                .andExpect(jsonPath("$.notaFiscal").value(321))
                .andExpect(jsonPath("$.numeroDuplicata").value("DUP-1"))
                .andExpect(jsonPath("$.valorPagar").value(1500.50))
                .andExpect(jsonPath("$.valorPago").value(0))
                .andExpect(jsonPath("$.documentoPago").value(false))
                .andExpect(jsonPath("$.observacoes").value("compra teste"))
                .andExpect(jsonPath("$.idMovimento").doesNotExist())
                .andExpect(jsonPath("$.criadoEm").exists());
    }

    @Test
    void valorPagarZeroOuNegativoEhRejeitado() throws Exception {
        Base base = prepararBase("valor-invalido");

        mvc.perform(post("/api/v1/contas-pagar").header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"1.00.000",
                                 "dataLancamento":"2026-07-30T12:00:00Z","dataVencimento":"2026-08-15T12:00:00Z","valorPagar":0}
                                """.formatted(base.idFornecedor(), base.idEmpresa())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fornecedorInexistenteEhRejeitado() throws Exception {
        Base base = prepararBase("fornecedor-inexistente");

        mvc.perform(post("/api/v1/contas-pagar").header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":999999,"idEmpresa":%d,"idPlanoContas":"1.00.000",
                                 "dataLancamento":"2026-07-30T12:00:00Z","dataVencimento":"2026-08-15T12:00:00Z","valorPagar":10.00}
                                """.formatted(base.idEmpresa())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void planoDeContasInexistenteEhRejeitado() throws Exception {
        Base base = prepararBase("plano-inexistente");

        mvc.perform(post("/api/v1/contas-pagar").header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"9.9.999",
                                 "dataLancamento":"2026-07-30T12:00:00Z","dataVencimento":"2026-08-15T12:00:00Z","valorPagar":10.00}
                                """.formatted(base.idFornecedor(), base.idEmpresa())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atualizarRegistraPagamento() throws Exception {
        Base base = prepararBase("atualiza");
        abrirCaixaDinheiro(base.token());
        long idContaPagar = criarContaPagar(base, "DUP-ORIGINAL", "2026-08-15", "100.00");

        mvc.perform(put("/api/v1/contas-pagar/" + idContaPagar).header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"1.00.000","notaFiscal":100,
                                 "numeroDuplicata":"DUP-ORIGINAL","dataLancamento":"2026-07-30T12:00:00Z",
                                 "dataVencimento":"2026-08-15T12:00:00Z","dataPagamento":"2026-08-10T12:00:00Z",
                                 "valorPagar":100.00,"valorPago":100.00,"documentoPago":true,
                                 "origemPagamento":"CAIXA"}
                                """.formatted(base.idFornecedor(), base.idEmpresa())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataPagamento").exists())
                .andExpect(jsonPath("$.valorPago").value(100.00))
                .andExpect(jsonPath("$.documentoPago").value(true));
    }

    /** O ciclo que faz o Fluxo de Caixa existir: baixar gera movimento de dinheiro, desfazer
     *  apaga. Sem o "desfazer", sobraria dinheiro fantasma saindo do caixa. */
    @Test
    void baixaEmDinheiroGeraMovimentoNoCaixaEDesfazerApaga() throws Exception {
        Base base = prepararBase("movimento-caixa");
        abrirCaixaDinheiro(base.token());
        long idTenant = extrairIdTenant(base.token());
        long idContaPagar = criarContaPagar(base, "DUP-CAIXA", "2026-08-15", "250.00");

        String corpoPago = """
                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"1.00.000",
                 "dataLancamento":"2026-07-30T12:00:00Z","dataVencimento":"2026-08-15T12:00:00Z",
                 "dataPagamento":"2026-08-10T12:00:00Z","valorPagar":250.00,"valorPago":250.00,
                 "origemPagamento":"CAIXA"}
                """.formatted(base.idFornecedor(), base.idEmpresa());
        mvc.perform(put("/api/v1/contas-pagar/" + idContaPagar).header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON).content(corpoPago))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origemPagamento").value("CAIXA"));

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(somaMovimentoCaixa(c, idContaPagar)).isEqualByComparingTo("250.00");
        }

        // Desfaz a baixa (sem data de pagamento) — o movimento tem de sumir junto.
        String corpoSemPagamento = """
                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"1.00.000",
                 "dataLancamento":"2026-07-30T12:00:00Z","dataVencimento":"2026-08-15T12:00:00Z",
                 "valorPagar":250.00}
                """.formatted(base.idFornecedor(), base.idEmpresa());
        mvc.perform(put("/api/v1/contas-pagar/" + idContaPagar).header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON).content(corpoSemPagamento))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origemPagamento").doesNotExist());

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(somaMovimentoCaixa(c, idContaPagar)).isEqualByComparingTo("0.00");
        }
    }

    /** Sem caixa aberto não dá pra pagar em dinheiro — mesma convenção do PDV/Recebimento. */
    @Test
    void baixaEmDinheiroSemCaixaAbertoEhRejeitada() throws Exception {
        Base base = prepararBase("sem-caixa");
        long idContaPagar = criarContaPagar(base, "DUP-SEM-CAIXA", "2026-08-15", "80.00");

        mvc.perform(put("/api/v1/contas-pagar/" + idContaPagar).header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"1.00.000",
                                 "dataLancamento":"2026-07-30T12:00:00Z","dataVencimento":"2026-08-15T12:00:00Z",
                                 "dataPagamento":"2026-08-10T12:00:00Z","valorPagar":80.00,"valorPago":80.00,
                                 "origemPagamento":"CAIXA"}
                                """.formatted(base.idFornecedor(), base.idEmpresa())))
                .andExpect(status().isBadRequest());
    }

    /** Baixa nova sem dizer de onde saiu o dinheiro é rejeitada — é o que garante que o fluxo de
     *  caixa realizado não fique sem as saídas. */
    @Test
    void baixaNovaSemOrigemEhRejeitada() throws Exception {
        Base base = prepararBase("sem-origem");
        long idContaPagar = criarContaPagar(base, "DUP-SEM-ORIGEM", "2026-08-15", "40.00");

        mvc.perform(put("/api/v1/contas-pagar/" + idContaPagar).header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"1.00.000",
                                 "dataLancamento":"2026-07-30T12:00:00Z","dataVencimento":"2026-08-15T12:00:00Z",
                                 "dataPagamento":"2026-08-10T12:00:00Z","valorPagar":40.00,"valorPago":40.00}
                                """.formatted(base.idFornecedor(), base.idEmpresa())))
                .andExpect(status().isBadRequest());
    }

    private BigDecimal somaMovimentoCaixa(Connection c, long idContaPagar) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(valor), 0) FROM caixa_detalhe WHERE id_conta_pagar = ?")) {
            ps.setLong(1, idContaPagar);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    @Test
    void excluirContaPagarApagaDeVerdade() throws Exception {
        Base base = prepararBase("exclusao");
        long idContaPagar = criarContaPagar(base, "DUP-EXCLUIR", "2026-08-15", "20.00");

        mvc.perform(delete("/api/v1/contas-pagar/" + idContaPagar).header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/contas-pagar/" + idContaPagar).header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listagemFiltraPorFornecedorEPorEmpresa() throws Exception {
        Base base = prepararBase("filtro-fornecedor-empresa");
        criarContaPagar(base, "DUP-A", "2026-08-15", "10.00");

        mvc.perform(get("/api/v1/contas-pagar").param("idFornecedor", String.valueOf(base.idFornecedor()))
                        .header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(1));

        mvc.perform(get("/api/v1/contas-pagar").param("idFornecedor", "999999")
                        .header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));

        mvc.perform(get("/api/v1/contas-pagar").param("idEmpresa", String.valueOf(base.idEmpresa()))
                        .header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(1));
    }

    @Test
    void listagemFiltraPorNotaFiscalEPorDuplicata() throws Exception {
        Base base = prepararBase("filtro-nota-duplicata");
        criarContaPagar(base, "DUP-ESPECIAL-123", "2026-08-15", "10.00");

        mvc.perform(get("/api/v1/contas-pagar").param("notaFiscal", "100")
                        .header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(1));

        mvc.perform(get("/api/v1/contas-pagar").param("notaFiscal", "999")
                        .header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));

        mvc.perform(get("/api/v1/contas-pagar").param("numeroDuplicata", "ESPECIAL")
                        .header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].numeroDuplicata").value("DUP-ESPECIAL-123"));
    }

    @Test
    void listagemFiltraPorIntervaloDeVencimento() throws Exception {
        Base base = prepararBase("filtro-vencimento");
        criarContaPagar(base, "DUP-VENC", "2026-08-15", "10.00");

        mvc.perform(get("/api/v1/contas-pagar")
                        .param("dataVencimentoInicial", "2026-08-15").param("dataVencimentoFinal", "2026-08-15")
                        .header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(1));

        mvc.perform(get("/api/v1/contas-pagar")
                        .param("dataVencimentoInicial", "2026-08-16")
                        .header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));

        mvc.perform(get("/api/v1/contas-pagar")
                        .param("dataVencimentoFinal", "2026-08-14")
                        .header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));
    }

    @Test
    void listagemFiltraPorIntervaloDePagamento() throws Exception {
        Base base = prepararBase("filtro-pagamento");
        abrirCaixaDinheiro(base.token());
        long idContaPagar = criarContaPagar(base, "DUP-PAG", "2026-08-15", "10.00");

        mvc.perform(put("/api/v1/contas-pagar/" + idContaPagar).header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"1.00.000",
                                 "numeroDuplicata":"DUP-PAG","dataLancamento":"2026-07-30T12:00:00Z",
                                 "dataVencimento":"2026-08-15T12:00:00Z","dataPagamento":"2026-08-12T12:00:00Z",
                                 "valorPagar":10.00,"valorPago":10.00,"documentoPago":true,
                                 "origemPagamento":"CAIXA"}
                                """.formatted(base.idFornecedor(), base.idEmpresa())))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/contas-pagar")
                        .param("dataPagamentoInicial", "2026-08-12").param("dataPagamentoFinal", "2026-08-12")
                        .header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(1));

        mvc.perform(get("/api/v1/contas-pagar")
                        .param("dataPagamentoInicial", "2026-08-13")
                        .header("Authorization", "Bearer " + base.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));
    }

    @Test
    void contaPagarDeOutroTenantNaoApareceNaListagemNemPodeSerBuscada() throws Exception {
        Base baseA = prepararBase("isolamento-a");
        long idContaPagarA = criarContaPagar(baseA, "DUP-ISOLAMENTO-A", "2026-08-15", "40.00");

        Base baseB = prepararBase("isolamento-b");
        mvc.perform(get("/api/v1/contas-pagar/" + idContaPagarA).header("Authorization", "Bearer " + baseB.token()))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/contas-pagar").header("Authorization", "Bearer " + baseB.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));
    }
}
