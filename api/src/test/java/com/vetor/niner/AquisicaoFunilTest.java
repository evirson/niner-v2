package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Funil de aquisição first-party (ADR-017): beacon → lead → conta, com atribuição de
 * <b>primeiro toque</b> e a garantia de que medição nunca atrapalha o cadastro.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AquisicaoFunilTest {

    @Autowired
    MockMvc mvc;

    private void beacon(String visitanteId, String utmSource, String campanha, String tipo, String rotulo)
            throws Exception {
        String corpo = """
                {"visitanteId":"%s","sessaoId":"%s",
                 "origem":{"utmSource":%s,"utmMedium":"cpc","utmCampaign":%s,"referrer":"https://instagram.com/",
                           "paginaEntrada":"/"},
                 "eventos":[{"tipo":"%s","rotulo":"%s","caminho":"/"}]}
                """.formatted(visitanteId, UUID.randomUUID(),
                utmSource == null ? "null" : "\"" + utmSource + "\"",
                campanha == null ? "null" : "\"" + campanha + "\"", tipo, rotulo);
        mvc.perform(post("/api/publico/eventos").contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isNoContent());
    }

    @Test
    void beaconGravaVisitaEEventoSemPii() throws Exception {
        String visitante = UUID.randomUUID().toString();
        beacon(visitante, "instagram", "lancamento", "PAGEVIEW", "Niner");
        beacon(visitante, "instagram", "lancamento", "CLIQUE_WHATSAPP", "heroi");

        mvc.perform(get("/api/admin/marketing/funil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitantes").isNumber())
                .andExpect(jsonPath("$.porOrigem[?(@.origem == 'instagram')]").exists());
    }

    @Test
    void loteMalformadoNaoDerrubaAMedicao() throws Exception {
        // Beacon com visitante inválido e evento sem tipo: descarta em silêncio, responde 204.
        mvc.perform(post("/api/publico/eventos").contentType(APPLICATION_JSON)
                        .content("""
                                {"visitanteId":"nao-e-uuid","eventos":[{"rotulo":"sem tipo"}]}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void signupFechaOFunilComOrigemDaPrimeiraVisita() throws Exception {
        String visitante = UUID.randomUUID().toString();
        beacon(visitante, "instagram", "lancamento", "PAGEVIEW", "Niner");
        // Segunda visita, direta e dias depois na vida real: NÃO pode roubar a atribuição.
        beacon(visitante, null, null, "PAGEVIEW", "Niner");

        String email = "dono-" + visitante.substring(0, 8) + "@lojafunil.com";
        mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeLoja":"Loja Funil %s","email":"%s","senha":"segredo123",
                                 "nomeAdmin":"Dono do Funil","visitanteId":"%s"}
                                """.formatted(visitante.substring(0, 6), email, visitante)))
                .andExpect(status().isCreated());

        String resp = mvc.perform(get("/api/admin/marketing/leads?status=CONVERTIDO"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // O lead nasce convertido, ligado ao tenant e com a origem do PRIMEIRO toque.
        String filtro = "$.itens[?(@.email == '" + email + "')]";
        org.assertj.core.api.Assertions.assertThat(JsonPath.<java.util.List<Object>>read(resp, filtro)).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(
                        JsonPath.<java.util.List<String>>read(resp, filtro + ".origem"))
                .containsExactly("instagram");
        org.assertj.core.api.Assertions.assertThat(
                        JsonPath.<java.util.List<Object>>read(resp, filtro + ".idTenant").get(0))
                .isNotNull();
    }

    @Test
    void signupSobreviveAFalhaDeMedicao() throws Exception {
        // visitanteId inválido: a medição falha/ignora, o cadastro TEM que continuar de pé.
        // (Regressão do bug de 2026-08-18: exceção engolida dentro da transação do signup fazia o
        // Postgres abortar tudo e o COMMIT virar ROLLBACK silencioso — 201 com a conta inexistente.)
        String email = "sobrevive-" + UUID.randomUUID().toString().substring(0, 8) + "@lojafunil.com";
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeLoja":"Loja Sobrevive","email":"%s","senha":"segredo123",
                                 "nomeAdmin":"Dono","visitanteId":"isto-nao-e-um-uuid"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(resp, "$.token");
        // A conta tem que existir DE VERDADE depois do 201 — é o que o bug quebrava.
        mvc.perform(get("/api/v1/minha-conta").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano.nome").value("Gratuito"));
    }

    @Test
    void formularioDeLeadGuardaConsentimentoENaoDuplica() throws Exception {
        String visitante = UUID.randomUUID().toString();
        beacon(visitante, "google", "busca-marca", "PAGEVIEW", "Niner");
        String email = "interessado-" + visitante.substring(0, 8) + "@lojafunil.com";
        String corpo = """
                {"nome":"Interessado","email":"%s","telefoneWhatsapp":"41999998888",
                 "nomeLoja":"Loja Interessada","visitanteId":"%s"}
                """.formatted(email, visitante);

        mvc.perform(post("/api/publico/leads").contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/publico/leads").contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isNoContent());

        String resp = mvc.perform(get("/api/admin/marketing/leads")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(
                        JsonPath.<java.util.List<Object>>read(resp, "$.itens[?(@.email == '" + email + "')]"))
                .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(
                        JsonPath.<java.util.List<String>>read(resp, "$.itens[?(@.email == '" + email + "')].origem"))
                .containsExactly("google");
    }
}
