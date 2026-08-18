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
 * Signup self-service (R12): cria a conta → libera o sistema → token de 1º acesso → primeiro uso
 * autenticado. E o login de usuário do tenant.
 *
 * <p>Era {@code OnboardingTrialTest} até 2026-08-18: o trial de 60 dias saiu (ADR-015) e a conta
 * passa a nascer <b>ATIVA no plano Gratuito</b>, sem data de expiração — o que limita é a cota de
 * vendas do mês.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OnboardingContaGratuitaTest {

    @Autowired
    MockMvc mvc;

    @Test
    void assinarCriaTenantGratuitoELiberaPrimeiroUso() throws Exception {
        String body = """
                {"nomeLoja":"Loja Teste Gratuito","email":"dono@lojateste.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """;
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.idTenant").isNumber())
                .andExpect(jsonPath("$.slug").value("loja-teste-gratuito"))
                .andExpect(jsonPath("$.plano").value("Gratuito"))
                .andExpect(jsonPath("$.limiteVendasMes").value(100))
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(resp, "$.token");

        // Primeiro uso: com o token do signup, o cliente já enxerga a própria conta.
        mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conta.nomeConta").value("Loja Teste Gratuito"))
                .andExpect(jsonPath("$.conta.status").value("ATIVA"))
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
