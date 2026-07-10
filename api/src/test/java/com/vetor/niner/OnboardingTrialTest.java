package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo do trial self-service (R12): assinatura-teste → cria tenant + libera o sistema
 * + token de 1º acesso → primeiro uso autenticado. E o login de usuário do tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OnboardingTrialTest {

    @Autowired
    MockMvc mvc;

    @Test
    void assinarCriaTenantELiberaPrimeiroUso() throws Exception {
        String body = """
                {"nomeLoja":"Loja Teste Trial","email":"dono@lojateste.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """;
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.idTenant").isNumber())
                .andExpect(jsonPath("$.slug").value("loja-teste-trial"))
                .andExpect(jsonPath("$.plano").value("Profissional"))
                .andExpect(jsonPath("$.trialExpiraEm").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(resp, "$.token");

        // Primeiro uso: com o token do trial, o cliente já enxerga a própria conta.
        mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conta.nomeConta").value("Loja Teste Trial"))
                .andExpect(jsonPath("$.conta.status").value("TRIAL"))
                .andExpect(jsonPath("$.usuario.papel").value("ADMIN"));
    }

    @Test
    void euSemTokenEhNaoAutorizado() throws Exception {
        mvc.perform(get("/api/v1/eu")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginAposAssinar() throws Exception {
        String assinar = """
                {"nomeLoja":"Loja Login","email":"admin@lojalogin.com",
                 "senha":"segredo123","nomeAdmin":"Admin"}
                """;
        mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(assinar))
                .andExpect(status().isCreated());

        String login = """
                {"slug":"loja-login","email":"admin@lojalogin.com","senha":"segredo123"}
                """;
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.slug").value("loja-login"));

        String senhaErrada = """
                {"slug":"loja-login","email":"admin@lojalogin.com","senha":"errada"}
                """;
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(senhaErrada))
                .andExpect(status().isUnauthorized());
    }
}
