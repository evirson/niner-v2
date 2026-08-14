package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo de Caixa (docs/telas/fluxo-caixa.md) — realizado lê só movimento de dinheiro, e a
 * projeção usa compromissos em aberto. O teste da baixa é o que prova a Parte 1 da feature: sem
 * ela, o realizado ficaria sem saídas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FluxoCaixaCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record Tenant(String token, long idTenant, long idEmpresa, long idFornecedor) {
    }

    private Tenant prepararTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Fluxo %s","email":"dono%s@lojafluxo.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        long idTenant = ((Number) JsonPath.read(payload, "$.tid")).longValue();

        String respForn = mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"FORNECEDOR FLUXO %s\",\"idPlanoContas\":\"3.03.001\",\"ativo\":true}"
                                .formatted(sufixo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idFornecedor = ((Number) JsonPath.read(respForn, "$.idFornecedor")).longValue();

        long idEmpresa;
        try (Connection c = abrirConexao(idTenant);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
            rs.next();
            idEmpresa = rs.getLong(1);
        }
        return new Tenant(token, idTenant, idEmpresa, idFornecedor);
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
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
                        .content("{\"idCarteira\":%d,\"saldoInicial\":1000.00}".formatted(idCarteira)))
                .andExpect(status().isOk());
    }

    private long criarContaPagar(Tenant t, String vencimento, String valor) throws Exception {
        String resp = mvc.perform(post("/api/v1/contas-pagar").header("Authorization", "Bearer " + t.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"3.03.001",
                                 "dataLancamento":"%sT12:00:00Z","dataVencimento":"%sT12:00:00Z","valorPagar":%s}
                                """.formatted(t.idFornecedor(), t.idEmpresa(), LocalDate.now(), vencimento, valor)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idContaPagar")).longValue();
    }

    private String hoje() {
        return LocalDate.now().toString();
    }

    /** O teste que justifica a Parte 1: a baixa vira saída de dinheiro no realizado. */
    @Test
    void baixaDeContaPagarApareceComoSaidaNoRealizado() throws Exception {
        Tenant t = prepararTenant("saida");
        abrirCaixaDinheiro(t.token());
        long idContaPagar = criarContaPagar(t, hoje(), "150.00");

        mvc.perform(put("/api/v1/contas-pagar/" + idContaPagar).header("Authorization", "Bearer " + t.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"3.03.001",
                                 "dataLancamento":"%sT12:00:00Z","dataVencimento":"%sT12:00:00Z",
                                 "dataPagamento":"%sT12:00:00Z","valorPagar":150.00,"valorPago":150.00,
                                 "origemPagamento":"CAIXA"}
                                """.formatted(t.idFornecedor(), t.idEmpresa(), hoje(), hoje(), hoje())))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/relatorios/fluxo-caixa/realizado").header("Authorization", "Bearer " + t.token())
                        .param("dataInicial", hoje()).param("dataFinal", hoje()))
                .andExpect(status().isOk())
                // Saldo inicial do caixa (1000) entra no saldo do período; a saída é 150.
                .andExpect(jsonPath("$.totalSaidas").value(150.00))
                .andExpect(jsonPath("$.saldoFinal").value(850.00))
                // Conciliação: o período termina hoje, então o calculado tem de bater com o real.
                .andExpect(jsonPath("$.diferencaConciliacao").value(0.00));
    }

    /** Período sem nenhum movimento: saldo inicial = saldo final, sem inventar linha. */
    @Test
    void periodoSemMovimentoMantemSaldo() throws Exception {
        Tenant t = prepararTenant("vazio");

        mvc.perform(get("/api/v1/relatorios/fluxo-caixa/realizado").header("Authorization", "Bearer " + t.token())
                        .param("dataInicial", hoje()).param("dataFinal", hoje()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoInicial").value(0.00))
                .andExpect(jsonPath("$.saldoFinal").value(0.00))
                .andExpect(jsonPath("$.totalEntradas").value(0.00))
                .andExpect(jsonPath("$.totalSaidas").value(0.00));
    }

    /** O alerta que justifica a aba de projeção existir. */
    @Test
    void projecaoApontaDataEmQueOSaldoFicaNegativo() throws Exception {
        Tenant t = prepararTenant("negativo");
        abrirCaixaDinheiro(t.token());   // saldo atual = 1000
        String daquiA10Dias = LocalDate.now().plusDays(10).toString();
        criarContaPagar(t, daquiA10Dias, "1500.00");

        mvc.perform(get("/api/v1/relatorios/fluxo-caixa/projecao").header("Authorization", "Bearer " + t.token())
                        .param("dataInicial", hoje())
                        .param("dataFinal", LocalDate.now().plusDays(30).toString())
                        .param("agrupamento", "DIA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoAtual").value(1000.00))
                .andExpect(jsonPath("$.totalSaidasPrevistas").value(1500.00))
                .andExpect(jsonPath("$.primeiraDataNegativa").value(daquiA10Dias))
                .andExpect(jsonPath("$.valorFaltante").value(500.00));
    }

    /** Conta vencida entra no primeiro balde, marcada — não na data original. */
    @Test
    void contaVencidaEntraNoPrimeiroBaldeComoEmAtraso() throws Exception {
        Tenant t = prepararTenant("vencida");
        criarContaPagar(t, LocalDate.now().minusDays(20).toString(), "90.00");

        mvc.perform(get("/api/v1/relatorios/fluxo-caixa/projecao").header("Authorization", "Bearer " + t.token())
                        .param("dataInicial", hoje())
                        .param("dataFinal", LocalDate.now().plusDays(30).toString())
                        .param("agrupamento", "DIA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas[0].data").value(hoje()))
                .andExpect(jsonPath("$.linhas[0].emAtraso").value(true))
                .andExpect(jsonPath("$.linhas[0].saidas").value(90.00));
    }

    /** Isolamento: movimento de outro tenant nunca aparece. */
    @Test
    void naoVazaMovimentoDeOutroTenant() throws Exception {
        Tenant a = prepararTenant("iso-a");
        Tenant b = prepararTenant("iso-b");
        abrirCaixaDinheiro(b.token());   // 1000 no caixa do tenant B

        mvc.perform(get("/api/v1/relatorios/fluxo-caixa/realizado").header("Authorization", "Bearer " + a.token())
                        .param("dataInicial", hoje()).param("dataFinal", hoje()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoFinal").value(0.00));
    }

}
