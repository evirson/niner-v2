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
 * CRUD de cor ({@code cfg_cor}), tamanho ({@code cfg_tamanho}) e grade ({@code cfg_grade},
 * 2026-08-08) — catálogos geridos embutidos (cor na Emissão de Etiqueta, tamanho/grade na tela
 * de Produto), sem tela própria, mas com endpoints REST completos. Mesmo padrão de
 * {@link ProdutoCrudTest}: cada teste assina um tenant novo (self-service, R12).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorGradeTamanhoCrudTest {

    @Autowired
    MockMvc mvc;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Grade %s","email":"dono%s@lojagrade.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private long criarTamanho(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/tamanhos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"descricao\":\"%s\"}".formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idTamanho")).longValue();
    }

    @Test
    void criaListaERenomeiaCor() throws Exception {
        String token = assinarNovoTenant("cor-crud");

        String resp = mvc.perform(post("/api/v1/cores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"descricao\":\"azul\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.descricao").value("AZUL"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(resp, "$.idCor")).longValue();

        mvc.perform(get("/api/v1/cores").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(put("/api/v1/cores/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"descricao\":\"azul marinho\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descricao").value("AZUL MARINHO"));
    }

    @Test
    void corComNomeDuplicadoEhRejeitada() throws Exception {
        String token = assinarNovoTenant("cor-duplicada");
        mvc.perform(post("/api/v1/cores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"descricao\":\"Preto\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/cores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"descricao\":\"preto\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void criaListaERenomeiaTamanho() throws Exception {
        String token = assinarNovoTenant("tamanho-crud");
        long id = criarTamanho(token, "36");

        mvc.perform(get("/api/v1/tamanhos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].descricao").value("36"));

        mvc.perform(put("/api/v1/tamanhos/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"descricao\":\"37\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descricao").value("37"));
    }

    @Test
    void criaGradeComTamanhosOrdenados() throws Exception {
        String token = assinarNovoTenant("grade-ordem");
        long id36 = criarTamanho(token, "36");
        long id37 = criarTamanho(token, "37");
        long id38 = criarTamanho(token, "38");

        mvc.perform(post("/api/v1/grades").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"descricao\":\"Grade 36-38\",\"idsTamanho\":[%d,%d,%d]}".formatted(id38, id36, id37)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.descricao").value("GRADE 36-38"))
                .andExpect(jsonPath("$.tamanhos.length()").value(3))
                .andExpect(jsonPath("$.tamanhos[0].idTamanho").value(id38))
                .andExpect(jsonPath("$.tamanhos[1].idTamanho").value(id36))
                .andExpect(jsonPath("$.tamanhos[2].idTamanho").value(id37));
    }

    @Test
    void gradeComNomeDuplicadoEhRejeitada() throws Exception {
        String token = assinarNovoTenant("grade-duplicada");
        long idTamanho = criarTamanho(token, "M");
        mvc.perform(post("/api/v1/grades").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"descricao\":\"Grade PP-GG\",\"idsTamanho\":[%d]}".formatted(idTamanho)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/grades").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"descricao\":\"grade pp-gg\",\"idsTamanho\":[%d]}".formatted(idTamanho)))
                .andExpect(status().isConflict());
    }

    @Test
    void gradeComMaisDe20TamanhosEhRejeitada() throws Exception {
        String token = assinarNovoTenant("grade-limite");
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            if (i > 0) ids.append(",");
            ids.append(criarTamanho(token, "T" + i));
        }

        mvc.perform(post("/api/v1/grades").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"descricao\":\"Grade Estourada\",\"idsTamanho\":[%s]}".formatted(ids)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atualizarGradeSubstituiListaDeTamanhos() throws Exception {
        String token = assinarNovoTenant("grade-atualiza");
        long id36 = criarTamanho(token, "36");
        long id37 = criarTamanho(token, "37");

        String resp = mvc.perform(post("/api/v1/grades").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"descricao\":\"Grade Original\",\"idsTamanho\":[%d]}".formatted(id36)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idGrade = ((Number) JsonPath.read(resp, "$.idGrade")).longValue();

        mvc.perform(put("/api/v1/grades/" + idGrade).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"descricao\":\"Grade Original\",\"idsTamanho\":[%d,%d]}".formatted(id37, id36)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tamanhos.length()").value(2))
                .andExpect(jsonPath("$.tamanhos[0].idTamanho").value(id37))
                .andExpect(jsonPath("$.tamanhos[1].idTamanho").value(id36));
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("tenant-a-grade");
        String tokenB = assinarNovoTenant("tenant-b-grade");
        mvc.perform(post("/api/v1/cores").header("Authorization", "Bearer " + tokenA)
                        .contentType(APPLICATION_JSON).content("{\"descricao\":\"Vermelho\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/cores").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
