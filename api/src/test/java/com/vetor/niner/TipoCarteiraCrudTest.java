package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRUD de tipo de carteira — mesmo padrão de {@link PlanoContasCrudTest}. Particularidade
 * própria desta tela (2026-07-23, docs/PROGRESSO.md): gerencia embutido o N:N com moeda
 * ({@code moeda_detalhe}) — o fluxo é "criar um tipo de carteira e escolher em quais moedas
 * ele vale", não o inverso. Sem coluna {@code ativo}: exclusão sem fallback de inativar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TipoCarteiraCrudTest {

    @Autowired
    MockMvc mvc;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Carteira %s","email":"dono%s@lojacarteira.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void criaTipoCarteiraSemMoedaVinculada() throws Exception {
        String token = assinarNovoTenant("sem-moeda");

        String carteira = """
                {"nomeCarteira":"crediario 30/60/90","prazoPagamento":30,
                 "pcMinima":1,"pcMaxima":3,"taxaAdministradora":2.5}
                """;

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(carteira))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nomeCarteira").value("CREDIARIO 30/60/90"))
                .andExpect(jsonPath("$.pcMinima").value(1))
                .andExpect(jsonPath("$.pcMaxima").value(3))
                .andExpect(jsonPath("$.moedas").isEmpty())
                .andExpect(jsonPath("$.criadoEm").exists());
    }

    @Test
    void criaTipoCarteiraComMoedasVinculadasENuncaAlteraOutroTenant() throws Exception {
        String token = assinarNovoTenant("com-moedas");
        long idCartao = buscarIdMoedaPorNome(token, "CARTAO CREDITO");
        long idCrediario = buscarIdMoedaPorNome(token, "CREDIARIO");

        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTAO 3X","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":3,"taxaAdministradora":0,"moedas":[%d,%d]}
                                """.formatted(idCartao, idCrediario)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        int qtdMoedas = JsonPath.read(resp, "$.moedas.length()");
        assertThat(qtdMoedas).isEqualTo(2);
    }

    @Test
    void atualizarSubstituiListaDeMoedas() throws Exception {
        String token = assinarNovoTenant("atualiza-moedas");
        long idPix = buscarIdMoedaPorNome(token, "PIX");
        long idDinheiro = buscarIdMoedaPorNome(token, "DINHEIRO");
        long id = criarCarteira(token, "CARTEIRA ATUALIZA", 30, 1, 1, "0", java.util.List.of(idPix));

        mvc.perform(put("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA ATUALIZA","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":0,"moedas":[%d]}
                                """.formatted(idDinheiro)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moedas[0].idMoeda").value(idDinheiro))
                .andExpect(jsonPath("$.moedas.length()").value(1));
    }

    @Test
    void parcelaMaximaMenorQueMinimaEhRejeitada() throws Exception {
        String token = assinarNovoTenant("parcela-invalida");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA PARCELA INVALIDA","prazoPagamento":30,
                                 "pcMinima":5,"pcMaxima":2,"taxaAdministradora":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taxaAdministradoraNegativaEhRejeitada() throws Exception {
        String token = assinarNovoTenant("taxa-negativa");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA TAXA INVALIDA","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":-1}
                                """))
                .andExpect(status().isBadRequest());
    }

    /** Opcional (2026-07-23) — nem todo tipo de carteira cobra taxa administradora. */
    @Test
    void taxaAdministradoraPodeFicarEmBrancoEPrazoPodeSerZero() throws Exception {
        String token = assinarNovoTenant("taxa-em-branco");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA SEM TAXA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxaAdministradora").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.prazoPagamento").value(0));
    }

    @Test
    void moedaDuplicadaNaListaEhRejeitada() throws Exception {
        String token = assinarNovoTenant("moeda-duplicada");
        long idPix = buscarIdMoedaPorNome(token, "PIX");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA MOEDA DUPLICADA","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":0,"moedas":[%d,%d]}
                                """.formatted(idPix, idPix)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void moedaInexistenteNaListaEhRejeitada() throws Exception {
        String token = assinarNovoTenant("moeda-inexistente");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA MOEDA INEXISTENTE","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":0,"moedas":[999999]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nomeDuplicadoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("nome-duplicado");
        criarCarteira(token, "CARTEIRA UNICA", 30, 1, 1, "0", java.util.List.of());

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"carteira unica","prazoPagamento":15,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":0}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void excluirCarteiraSemVinculoApagaDeVerdadeERemoveVinculoDeMoeda() throws Exception {
        String token = assinarNovoTenant("exclusao-simples");
        long idPix = buscarIdMoedaPorNome(token, "PIX");
        long id = criarCarteira(token, "CARTEIRA SEM VINCULO", 30, 1, 1, "0", java.util.List.of(idPix));

        mvc.perform(delete("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        // A moeda continua existindo — só o vínculo (moeda_detalhe) foi removido junto.
        mvc.perform(get("/api/v1/moedas/" + idPix).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void listagemOrdenaPorColunaEDirecaoPedidas() throws Exception {
        String token = assinarNovoTenant("ordenacao");
        criarCarteira(token, "ORDCARTEIRA BETA", 30, 1, 1, "0", java.util.List.of());
        criarCarteira(token, "ORDCARTEIRA ALFA", 30, 1, 1, "0", java.util.List.of());
        criarCarteira(token, "ORDCARTEIRA GAMA", 30, 1, 1, "0", java.util.List.of());

        mvc.perform(get("/api/v1/tipos-carteira").param("busca", "ORDCARTEIRA")
                        .param("ordenarPor", "nomeCarteira").param("direcao", "DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nomeCarteira").value("ORDCARTEIRA GAMA"));
    }

    private long buscarIdMoedaPorNome(String token, String nomeMoeda) throws Exception {
        String resp = mvc.perform(get("/api/v1/moedas").param("busca", nomeMoeda).param("limite", "20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.itens[0].idMoeda")).longValue();
    }

    private long criarCarteira(String token, String nome, int prazoPagamento, int pcMinima, int pcMaxima,
                                String taxaAdministradora, java.util.List<Long> moedas) throws Exception {
        String moedasJson = moedas.isEmpty() ? "[]"
                : moedas.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"%s","prazoPagamento":%d,"pcMinima":%d,"pcMaxima":%d,
                                 "taxaAdministradora":%s,"moedas":%s}
                                """.formatted(nome, prazoPagamento, pcMinima, pcMaxima, taxaAdministradora, moedasJson)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCarteira")).longValue();
    }
}
