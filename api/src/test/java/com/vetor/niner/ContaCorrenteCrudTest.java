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
 * CRUD de conta corrente (docs/telas/conta-corrente.md) — mesmo padrão de
 * {@link PlanoContasCrudTest} (PK de negócio, código imutável na atualização), mas com {@code
 * ativo}: exclusão com vínculo em {@code conta_corrente_movimento} inativa em vez de excluir
 * (mesmo padrão de {@link FornecedorCrudTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ContaCorrenteCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Conta Corrente %s","email":"dono%s@lojacontacorrente.com",
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

    private void criarContaSimples(String token, long idEmpresa, String codigo, String descricao) throws Exception {
        mvc.perform(post("/api/v1/contas-corrente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"%s","idEmpresa":%d,"idBanco":"341","idAgencia":"1234",
                                 "descricao":"%s","ativo":true}
                                """.formatted(codigo, idEmpresa, descricao)))
                .andExpect(status().isCreated());
    }

    @Test
    void criaContaCorrenteComDadosCompletos() throws Exception {
        String token = assinarNovoTenant("completo");
        long idEmpresa = buscarIdEmpresa(extrairIdTenant(token));

        String corpo = """
                {"idContaCorrente":"001-12345-6","idEmpresa":%d,"idBanco":"341","idAgencia":"1234",
                 "descricao":"conta movimento itau","ativo":true,"dataAbertura":"2020-01-15"}
                """.formatted(idEmpresa);

        mvc.perform(post("/api/v1/contas-corrente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idContaCorrente").value("001-12345-6"))
                .andExpect(jsonPath("$.descricao").value("CONTA MOVIMENTO ITAU"))
                .andExpect(jsonPath("$.idBanco").value("341"))
                .andExpect(jsonPath("$.nomeEmpresa").exists())
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.dataAbertura").value("2020-01-15"))
                .andExpect(jsonPath("$.criadoEm").exists());
    }

    @Test
    void codigoDuplicadoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("duplicado");
        long idEmpresa = buscarIdEmpresa(extrairIdTenant(token));
        criarContaSimples(token, idEmpresa, "111", "CONTA UM");

        mvc.perform(post("/api/v1/contas-corrente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"111","idEmpresa":%d,"idBanco":"001","idAgencia":"0001",
                                 "descricao":"outra conta","ativo":true}
                                """.formatted(idEmpresa)))
                .andExpect(status().isConflict());
    }

    @Test
    void empresaInexistenteEhRejeitada() throws Exception {
        String token = assinarNovoTenant("empresa-inexistente");

        mvc.perform(post("/api/v1/contas-corrente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"222","idEmpresa":999999,"idBanco":"001","idAgencia":"0001",
                                 "descricao":"conta invalida","ativo":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bancoInexistenteEhRejeitado() throws Exception {
        String token = assinarNovoTenant("banco-inexistente");
        long idEmpresa = buscarIdEmpresa(extrairIdTenant(token));

        mvc.perform(post("/api/v1/contas-corrente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"333-banco","idEmpresa":%d,"idBanco":"888","idAgencia":"0001",
                                 "descricao":"conta banco invalido","ativo":true}
                                """.formatted(idEmpresa)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atualizarMudaDescricaoMasNaoOCodigo() throws Exception {
        String token = assinarNovoTenant("atualiza");
        long idEmpresa = buscarIdEmpresa(extrairIdTenant(token));
        criarContaSimples(token, idEmpresa, "333", "DESCRICAO ORIGINAL");

        mvc.perform(put("/api/v1/contas-corrente/333").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"999-nao-muda","idEmpresa":%d,"idBanco":"341","idAgencia":"1234",
                                 "descricao":"descricao corrigida","ativo":true}
                                """.formatted(idEmpresa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idContaCorrente").value("333"))
                .andExpect(jsonPath("$.descricao").value("DESCRICAO CORRIGIDA"));

        mvc.perform(get("/api/v1/contas-corrente/999-NAO-MUDA").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirContaSemVinculoApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao-simples");
        long idEmpresa = buscarIdEmpresa(extrairIdTenant(token));
        criarContaSimples(token, idEmpresa, "444", "SEM VINCULO");

        mvc.perform(delete("/api/v1/contas-corrente/444").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/contas-corrente/444").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirContaComMovimentoVinculadoInativaEmVezDeExcluir() throws Exception {
        String token = assinarNovoTenant("exclusao-vinculo");
        long idTenant = extrairIdTenant(token);
        long idEmpresa = buscarIdEmpresa(idTenant);
        criarContaSimples(token, idEmpresa, "555", "COM MOVIMENTO");
        criarPlanoESeuCodigo(token);

        mvc.perform(post("/api/v1/contas-corrente-movimento").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"555","idPlanoContas":"1.00.000","dataMovimento":"2026-07-30T10:00:00Z",
                                 "numeroDocumento":"DOC-1","creditoDebito":"C","valor":100.00}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/v1/contas-corrente/555").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"));

        mvc.perform(get("/api/v1/contas-corrente/555").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
    }

    @Test
    void listagemFiltraPorStatusEExcluiInativosPorPadrao() throws Exception {
        String token = assinarNovoTenant("status");
        long idEmpresa = buscarIdEmpresa(extrairIdTenant(token));
        criarContaSimples(token, idEmpresa, "666", "CONTA ATIVA STATUS");
        mvc.perform(post("/api/v1/contas-corrente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"777","idEmpresa":%d,"idBanco":"001","idAgencia":"0001",
                                 "descricao":"conta inativa status","ativo":false}
                                """.formatted(idEmpresa)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/contas-corrente").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[*].idContaCorrente")
                        .value(org.hamcrest.Matchers.hasItem("666")))
                .andExpect(jsonPath("$.itens[*].idContaCorrente")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("777"))));

        mvc.perform(get("/api/v1/contas-corrente").param("status", "INATIVOS").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[*].idContaCorrente").value(org.hamcrest.Matchers.hasItem("777")));
    }

    @Test
    void buscaEncontraPorDescricao() throws Exception {
        String token = assinarNovoTenant("busca");
        long idEmpresa = buscarIdEmpresa(extrairIdTenant(token));
        criarContaSimples(token, idEmpresa, "888", "CONTA MOVIMENTO BRADESCO");

        mvc.perform(get("/api/v1/contas-corrente").param("busca", "BRADESCO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].idContaCorrente").value("888"));
    }

    @Test
    void opcoesTrazSoContasAtivas() throws Exception {
        String token = assinarNovoTenant("opcoes");
        long idEmpresa = buscarIdEmpresa(extrairIdTenant(token));
        criarContaSimples(token, idEmpresa, "999", "CONTA OPCAO ATIVA");
        mvc.perform(post("/api/v1/contas-corrente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"1000","idEmpresa":%d,"idBanco":"001","idAgencia":"0001",
                                 "descricao":"conta opcao inativa","ativo":false}
                                """.formatted(idEmpresa)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/contas-corrente/opcoes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].idContaCorrente").value(org.hamcrest.Matchers.hasItem("999")))
                .andExpect(jsonPath("$[*].idContaCorrente").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("1000"))));
    }

    @Test
    void contaDeOutroTenantNaoApareceNaBuscaNemPodeSerBuscada() throws Exception {
        String tokenA = assinarNovoTenant("isolamento-a");
        long idEmpresaA = buscarIdEmpresa(extrairIdTenant(tokenA));
        criarContaSimples(tokenA, idEmpresaA, "2000", "CONTA ISOLAMENTO A");

        String tokenB = assinarNovoTenant("isolamento-b");
        mvc.perform(get("/api/v1/contas-corrente/2000").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    /** Cria um plano de contas simples reaproveitável pelos testes de movimento. */
    private void criarPlanoESeuCodigo(String token) throws Exception {
        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"1.00.000","descricao":"receita teste","tipoMovimento":"CREDITO","natureza":"ANALITICA",
                                 "incluiDre":false,"incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isCreated());
    }
}
