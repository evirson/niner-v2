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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRUD de lançamentos (extrato manual) de conta corrente (docs/telas/conta-corrente-
 * movimento.md) — diferente do resto do módulo financeiro, esta tela permite editar/excluir um
 * lançamento já gravado (não é ledger imutável tipo {@code caixa_detalhe}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ContaCorrenteMovimentoCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Movimento CC %s","email":"dono%s@lojamovimentocc.com",
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

    /** Cria uma conta corrente ("CC-1") e um plano de contas ("1.1.001") prontos pra uso. */
    private void prepararContaEPlano(String token) throws Exception {
        long idEmpresa = buscarIdEmpresa(extrairIdTenant(token));
        mvc.perform(post("/api/v1/contas-corrente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"CC-1","idEmpresa":%d,"idBanco":"341","idAgencia":"1234",
                                 "descricao":"conta teste movimento","ativo":true}
                                """.formatted(idEmpresa)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"1.1.001","descricao":"receita teste","tipoMovimento":"CRÉDITO",
                                 "incluiDre":false,"incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isCreated());
    }

    private long criarMovimento(String token, String numeroDocumento, String creditoDebito, String valor) throws Exception {
        String resp = mvc.perform(post("/api/v1/contas-corrente-movimento").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"CC-1","idPlanoContas":"1.1.001","dataMovimento":"2026-07-30T10:00:00Z",
                                 "numeroDocumento":"%s","creditoDebito":"%s","valor":%s,"observacao":"obs teste"}
                                """.formatted(numeroDocumento, creditoDebito, valor)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.localizador")).longValue();
    }

    @Test
    void criaMovimentoComDadosCompletos() throws Exception {
        String token = assinarNovoTenant("completo");
        prepararContaEPlano(token);

        String corpo = """
                {"idContaCorrente":"CC-1","idPlanoContas":"1.1.001","dataMovimento":"2026-07-30T10:00:00Z",
                 "numeroDocumento":"DOC-100","creditoDebito":"C","compensado":false,"valor":1500.50,
                 "observacao":"deposito"}
                """;

        mvc.perform(post("/api/v1/contas-corrente-movimento").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idContaCorrente").value("CC-1"))
                .andExpect(jsonPath("$.descricaoContaCorrente").exists())
                .andExpect(jsonPath("$.idPlanoContas").value("1.1.001"))
                .andExpect(jsonPath("$.descricaoPlanoContas").exists())
                .andExpect(jsonPath("$.numeroDocumento").value("DOC-100"))
                .andExpect(jsonPath("$.creditoDebito").value("C"))
                .andExpect(jsonPath("$.compensado").value(false))
                .andExpect(jsonPath("$.valor").value(1500.50))
                .andExpect(jsonPath("$.observacao").value("deposito"))
                .andExpect(jsonPath("$.criadoEm").exists());
    }

    @Test
    void creditoDebitoInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("cd-invalido");
        prepararContaEPlano(token);

        mvc.perform(post("/api/v1/contas-corrente-movimento").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"CC-1","idPlanoContas":"1.1.001","dataMovimento":"2026-07-30T10:00:00Z",
                                 "numeroDocumento":"DOC-1","creditoDebito":"X","valor":10.00}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void valorZeroOuNegativoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("valor-invalido");
        prepararContaEPlano(token);

        mvc.perform(post("/api/v1/contas-corrente-movimento").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"CC-1","idPlanoContas":"1.1.001","dataMovimento":"2026-07-30T10:00:00Z",
                                 "numeroDocumento":"DOC-1","creditoDebito":"D","valor":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contaCorrenteInexistenteEhRejeitada() throws Exception {
        String token = assinarNovoTenant("conta-inexistente");
        prepararContaEPlano(token);

        mvc.perform(post("/api/v1/contas-corrente-movimento").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"NAO-EXISTE","idPlanoContas":"1.1.001","dataMovimento":"2026-07-30T10:00:00Z",
                                 "numeroDocumento":"DOC-1","creditoDebito":"D","valor":10.00}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void planoDeContasInexistenteEhRejeitado() throws Exception {
        String token = assinarNovoTenant("plano-inexistente");
        prepararContaEPlano(token);

        mvc.perform(post("/api/v1/contas-corrente-movimento").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"CC-1","idPlanoContas":"9.9.999","dataMovimento":"2026-07-30T10:00:00Z",
                                 "numeroDocumento":"DOC-1","creditoDebito":"D","valor":10.00}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atualizarMudaValorECompensado() throws Exception {
        String token = assinarNovoTenant("atualiza");
        prepararContaEPlano(token);
        long localizador = criarMovimento(token, "DOC-ORIGINAL", "D", "50.00");

        mvc.perform(put("/api/v1/contas-corrente-movimento/" + localizador).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"CC-1","idPlanoContas":"1.1.001","dataMovimento":"2026-07-30T10:00:00Z",
                                 "numeroDocumento":"DOC-ORIGINAL","creditoDebito":"D","compensado":true,"valor":75.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valor").value(75.00))
                .andExpect(jsonPath("$.compensado").value(true));
    }

    @Test
    void excluirMovimentoApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao");
        prepararContaEPlano(token);
        long localizador = criarMovimento(token, "DOC-EXCLUIR", "C", "20.00");

        mvc.perform(delete("/api/v1/contas-corrente-movimento/" + localizador).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/contas-corrente-movimento/" + localizador).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void listagemFiltraPorContaEPorCompensado() throws Exception {
        String token = assinarNovoTenant("filtros");
        prepararContaEPlano(token);
        criarMovimento(token, "DOC-A", "C", "10.00");
        long idComp = criarMovimento(token, "DOC-B", "D", "20.00");
        mvc.perform(put("/api/v1/contas-corrente-movimento/" + idComp).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"CC-1","idPlanoContas":"1.1.001","dataMovimento":"2026-07-30T10:00:00Z",
                                 "numeroDocumento":"DOC-B","creditoDebito":"D","compensado":true,"valor":20.00}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/contas-corrente-movimento").param("idContaCorrente", "CC-1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(2));

        mvc.perform(get("/api/v1/contas-corrente-movimento").param("compensado", "COMPENSADOS")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].numeroDocumento").value("DOC-B"));
    }

    @Test
    void listagemFiltraPorIntervaloDeData() throws Exception {
        // criarMovimento sempre grava dataMovimento = 2026-07-30T10:00:00Z (helper fixo).
        String token = assinarNovoTenant("filtro-data");
        prepararContaEPlano(token);
        criarMovimento(token, "DOC-DATA", "C", "10.00");

        mvc.perform(get("/api/v1/contas-corrente-movimento")
                        .param("dataInicial", "2026-07-30").param("dataFinal", "2026-07-30")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(1));

        mvc.perform(get("/api/v1/contas-corrente-movimento")
                        .param("dataInicial", "2026-08-01")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));

        mvc.perform(get("/api/v1/contas-corrente-movimento")
                        .param("dataFinal", "2026-07-29")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));
    }

    @Test
    void listagemFiltraPorEmpresaEPorPlanoDeContas() throws Exception {
        String token = assinarNovoTenant("filtro-empresa-plano");
        long idEmpresa = buscarIdEmpresa(extrairIdTenant(token));
        prepararContaEPlano(token);
        criarMovimento(token, "DOC-EMPRESA-PLANO", "C", "10.00");

        mvc.perform(get("/api/v1/contas-corrente-movimento").param("idEmpresa", String.valueOf(idEmpresa))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(1));

        mvc.perform(get("/api/v1/contas-corrente-movimento").param("idEmpresa", "999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));

        mvc.perform(get("/api/v1/contas-corrente-movimento").param("idPlanoContas", "1.1.001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(1));

        mvc.perform(get("/api/v1/contas-corrente-movimento").param("idPlanoContas", "9.9.999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));
    }

    @Test
    void buscaEncontraPorNumeroDocumento() throws Exception {
        String token = assinarNovoTenant("busca");
        prepararContaEPlano(token);
        criarMovimento(token, "DOC-ESPECIAL-123", "C", "30.00");

        mvc.perform(get("/api/v1/contas-corrente-movimento").param("busca", "ESPECIAL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].numeroDocumento").value("DOC-ESPECIAL-123"));
    }

    @Test
    void movimentoDeOutroTenantNaoApareceNaListagemNemPodeSerBuscado() throws Exception {
        String tokenA = assinarNovoTenant("isolamento-a");
        prepararContaEPlano(tokenA);
        long localizadorA = criarMovimento(tokenA, "DOC-ISOLAMENTO-A", "C", "40.00");

        String tokenB = assinarNovoTenant("isolamento-b");
        mvc.perform(get("/api/v1/contas-corrente-movimento/" + localizadorA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/contas-corrente-movimento").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));
    }
}
