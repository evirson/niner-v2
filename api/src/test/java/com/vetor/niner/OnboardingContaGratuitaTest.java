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
    void mesmoEmailNaoCriaUmaSegundaLoja() throws Exception {
        // Achado na validação de produção (2026-08-19): o segundo cadastro com o mesmo e-mail
        // devolvia 201 e criava OUTRA loja — o lojista que clica duas vezes ficava com os dados
        // divididos entre duas contas, e o lead de marketing migrava para a segunda (apagando a
        // primeira do funil). Mais de um CNPJ é EMPRESA dentro da mesma conta, não conta nova.
        String primeiro = """
                {"nomeLoja":"Loja Do Zé","email":"ze@lojadoze.com",
                 "senha":"segredo123","nomeAdmin":"Zé"}
                """;
        mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(primeiro))
                .andExpect(status().isCreated());

        // Nome de loja diferente (slug diferente, então não é a unicidade do slug que barra) e
        // e-mail em outra caixa — a comparação tem que ser insensível a maiúscula.
        String segundo = """
                {"nomeLoja":"Outra Loja Do Ze","email":"ZE@LojaDoZe.com",
                 "senha":"outrasenha123","nomeAdmin":"Zé"}
                """;
        mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(segundo))
                .andExpect(status().isConflict());

        // E a loja original continua de pé, com o login funcionando.
        String login = """
                {"email":"ze@lojadoze.com","senha":"segredo123"}
                """;
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void euDevolvePlanoECotaEmVezDeDataDeTrial() throws Exception {
        // O /eu devolvia `trial_expira_em`, e o painel do lojista mostrava "Teste até <data>" —
        // cópia de dois modelos comerciais atrás. Agora devolve plano + cota, que é o que a conta
        // gratuita realmente tem. Lido por QUALQUER papel (Minha Conta é ADMIN-only).
        String body = """
                {"nomeLoja":"Loja Plano No Eu","email":"dono@lojaplanoeu.com",
                 "senha":"segredo123","nomeAdmin":"Dono"}
                """;
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + JsonPath.read(resp, "$.token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano.nome").value("Gratuito"))
                .andExpect(jsonPath("$.plano.gratuito").value(true))
                .andExpect(jsonPath("$.plano.limite_vendas_mes").value(100))
                .andExpect(jsonPath("$.plano.vendas_no_mes").value(0))
                .andExpect(jsonPath("$.trial_expira_em").doesNotExist());
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
                {"email":"admin@lojalogin.com","senha":"segredo123"}
                """;
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.slug").value("loja-login"));

        String senhaErrada = """
                {"email":"admin@lojalogin.com","senha":"errada"}
                """;
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(senhaErrada))
                .andExpect(status().isUnauthorized());
    }
}
