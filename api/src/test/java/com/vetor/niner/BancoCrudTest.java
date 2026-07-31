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
 * Consulta de banco (docs/telas/conta-corrente.md) — {@code cfg_banco} é global (sem RLS,
 * seed dentro de V028), usada pelo autopreenchimento do nome no cadastro de Conta Corrente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BancoCrudTest {

    @Autowired
    MockMvc mvc;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Banco %s","email":"dono%s@lojabanco.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void buscaPorCodigoExistenteTrazONome() throws Exception {
        String token = assinarNovoTenant("existente");

        mvc.perform(get("/api/v1/bancos/341").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigoBanco").value("341"))
                .andExpect(jsonPath("$.nomeBanco").value("ITAU UNIBANCO"));
    }

    @Test
    void buscaPorCodigoInexistenteResponde404() throws Exception {
        String token = assinarNovoTenant("inexistente");

        mvc.perform(get("/api/v1/bancos/888").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
