package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRUD de moeda (forma de recebimento) — mesmo padrão de {@link PlanoContasCrudTest}: cada
 * teste assina um tenant novo (que já nasce com 7 moedas semeadas pelo signup) e usa o token
 * real emitido. Particularidades cobertas: sem coluna {@code ativo} (409 quando há vínculo em
 * {@code moeda_detalhe}, sem fallback de inativar) e faixa válida dos percentuais.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MoedaCrudTest {

    @Autowired
    MockMvc mvc;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Moeda %s","email":"dono%s@lojamoeda.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void tenantNovoJaNasceComSeteMoedasSemeadas() throws Exception {
        String token = assinarNovoTenant("seed");

        mvc.perform(get("/api/v1/moedas").param("limite", "20").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(7));
    }

    @Test
    void criaMoedaComDadosCompletos() throws Exception {
        String token = assinarNovoTenant("completa");

        String moeda = """
                {"nomeMoeda":"pix parcelado","percDesconto":0,"percAcrescimo":2.5}
                """;

        mvc.perform(post("/api/v1/moedas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(moeda))
                .andExpect(status().isCreated())
                // Nome normalizado para MAIÚSCULAS no servidor, convenção do projeto.
                .andExpect(jsonPath("$.nomeMoeda").value("PIX PARCELADO"))
                .andExpect(jsonPath("$.percAcrescimo").value(2.5))
                .andExpect(jsonPath("$.criadoEm").exists())
                .andExpect(jsonPath("$.atualizadoEm").exists());
    }

    @Test
    void nomeDuplicadoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("duplicada");

        // "DINHEIRO" já existe (seed do signup) — criar de novo tem que ser rejeitado.
        mvc.perform(post("/api/v1/moedas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeMoeda":"dinheiro","percDesconto":0,"percAcrescimo":0}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void percentualNegativoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("percentual-negativo");

        mvc.perform(post("/api/v1/moedas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeMoeda":"moeda invalida","percDesconto":-1}
                                """))
                .andExpect(status().isBadRequest());
    }

    /** Sem limite superior (2026-07-23) — só não pode ser negativo. */
    @Test
    void percentualAcimaDeCemAgoraEhAceito() throws Exception {
        String token = assinarNovoTenant("percentual-alto");

        mvc.perform(post("/api/v1/moedas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeMoeda":"moeda acrescimo alto","percAcrescimo":150}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.percAcrescimo").value(150));
    }

    /**
     * A checagem é por valor positivo, não por presença (2026-07-23) — 0/0 é o estado neutro
     * normal (toda moeda semeada no signup nasce assim) e não pode ser rejeitado.
     */
    @Test
    void desconstoEAcrescimoZeradosJuntosSaoAceitos() throws Exception {
        String token = assinarNovoTenant("zerados-juntos");

        mvc.perform(post("/api/v1/moedas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeMoeda":"moeda neutra","percDesconto":0,"percAcrescimo":0}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void descontoEAcrescimoPositivosJuntosSaoRejeitados() throws Exception {
        String token = assinarNovoTenant("ambos-positivos");

        mvc.perform(post("/api/v1/moedas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeMoeda":"moeda invalida","percDesconto":5,"percAcrescimo":3}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atualizarMudaPercentuais() throws Exception {
        String token = assinarNovoTenant("atualiza");
        long id = criarMoeda(token, "MOEDA ATUALIZAR", "0", "0");

        mvc.perform(put("/api/v1/moedas/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeMoeda":"moeda atualizar","percDesconto":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percDesconto").value(5));
    }

    @Test
    void excluirMoedaSemVinculoApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao-simples");
        long id = criarMoeda(token, "MOEDA SEM VINCULO", "0", "0");

        mvc.perform(delete("/api/v1/moedas/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/moedas/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirMoedaVinculadaATipoDeCarteiraRespondeConflito() throws Exception {
        // Sem coluna `ativo` não há fallback de inativar: com vínculo em moeda_detalhe, 409.
        String token = assinarNovoTenant("exclusao-vinculo");
        long idMoeda = criarMoeda(token, "MOEDA COM CARTEIRA", "0", "0");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA VINCULA MOEDA","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":3,"taxaAdministradora":0,"moedas":[%d]}
                                """.formatted(idMoeda)))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/v1/moedas/" + idMoeda).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/moedas/" + idMoeda).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void listagemOrdenaPorColunaEDirecaoPedidas() throws Exception {
        String token = assinarNovoTenant("ordenacao");
        criarMoeda(token, "ORDMOEDA BETA", "0", "0");
        criarMoeda(token, "ORDMOEDA ALFA", "0", "0");
        criarMoeda(token, "ORDMOEDA GAMA", "0", "0");

        mvc.perform(get("/api/v1/moedas").param("busca", "ORDMOEDA")
                        .param("ordenarPor", "nomeMoeda").param("direcao", "DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nomeMoeda").value("ORDMOEDA GAMA"));
    }

    private long criarMoeda(String token, String nome, String percDesconto, String percAcrescimo) throws Exception {
        String resp = mvc.perform(post("/api/v1/moedas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeMoeda":"%s","percDesconto":%s,"percAcrescimo":%s}
                                """.formatted(nome, percDesconto, percAcrescimo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idMoeda")).longValue();
    }
}
