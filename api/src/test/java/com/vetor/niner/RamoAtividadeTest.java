package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.ramo.RamoAtividadeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ramo de atividade da empresa (V072, 2026-08-27) — a lista curta que o lojista escolhe e o mapa
 * CNAE→ramo que permite <b>sugerir</b> a partir do CNPJ.
 *
 * <p>⚠️ Estes testes não tocam a rede. A consulta de CNPJ em si depende de um serviço externo
 * (BrasilAPI) e foi verificada ao vivo; o que se prende aqui é a parte que é nossa e que quebra
 * em silêncio se alguém mexer no seed: o mapa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RamoAtividadeTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    RamoAtividadeService ramos;

    @Test
    void listaOs28RamosNaOrdemDefinida() throws Exception {
        var lista = ramos.listar();
        assertEquals(28, lista.size());
        assertEquals("Açougue e peixaria", lista.get(0).nome());
        assertEquals("Outros", lista.get(lista.size() - 1).nome(),
                "\"Outros\" é o fallback manual e fica sempre por último");
    }

    @Test
    void cnaeDeVarejoSugereORamoCerto() {
        assertEquals("Calçados", ramos.sugerirPorCnae("4782201").orElseThrow().nome());
        assertEquals("Ótica", ramos.sugerirPorCnae("4774100").orElseThrow().nome());
        assertEquals("Pet shop", ramos.sugerirPorCnae("4789004").orElseThrow().nome());
        assertEquals("Autopeças", ramos.sugerirPorCnae("4530703").orElseThrow().nome());
    }

    /** A máscara é como o CNAE aparece na tela; o mapa guarda só dígitos. */
    @Test
    void cnaeComMascaraTambemEncontra() {
        assertEquals("Calçados", ramos.sugerirPorCnae("4782-2/01").orElseThrow().nome());
    }

    /**
     * ⚠️ O coração da decisão do dono do produto: sugerir a partir de um código genérico seria
     * chutar com cara de certeza. 4789-0/99 ("outros produtos não especificados") é onde se
     * registram artigos religiosos, artigos de festa e dezenas de atividades diferentes — dele
     * não sai palpite nenhum.
     */
    @Test
    void cnaeGenericoNaoSugereNada() {
        assertTrue(ramos.sugerirPorCnae("4789099").isEmpty(), "4789-0/99 serve a dezenas de atividades");
        assertTrue(ramos.sugerirPorCnae("4729699").isEmpty(), "4729-6/99 idem, no ramo alimentício");
        assertTrue(ramos.sugerirPorCnae("6422100").isEmpty(), "banco não é varejo");
        assertTrue(ramos.sugerirPorCnae("").isEmpty());
        assertTrue(ramos.sugerirPorCnae(null).isEmpty());
        assertTrue(ramos.sugerirPorCnae("123").isEmpty(), "CNAE incompleto não vira consulta");
    }

    @Test
    void listaDeRamosEhPublica() throws Exception {
        mvc.perform(get("/api/publico/ramos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(28));
    }

    @Test
    void signupGravaORamoEscolhido() throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Sapataria do Ramo","email":"dono@sapatariadoramo.com",
                         "senha":"segredo123","nomeAdmin":"Dono","idRamo":10}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");

        String empresa = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long idEmpresa = ((Number) JsonPath.read(empresa, "$.empresa.idEmpresa")).longValue();

        mvc.perform(get("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idRamo").value(10));
    }

    /**
     * ⚠️ Ramo é dado de segmentação, não requisito do ERP: no signup, um id inválido vira "não
     * informado" em vez de derrubar a criação da conta. Alguém batendo na API com lixo não pode
     * ser o motivo de um cliente não conseguir se cadastrar.
     */
    @Test
    void signupComRamoInvalidoCriaAContaSemRamo() throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja Ramo Invalido","email":"dono@ramoinvalido.com",
                         "senha":"segredo123","nomeAdmin":"Dono","idRamo":9999}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");

        String empresa = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        long idEmpresa = ((Number) JsonPath.read(empresa, "$.empresa.idEmpresa")).longValue();

        mvc.perform(get("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.idRamo").doesNotExist());
    }

    /**
     * Dentro do sistema a régua é outra: o usuário escolhe numa lista, então id fora dela é lixo
     * de cliente de API e é recusado — não engolido.
     */
    @Test
    void empresaRecusaRamoInexistente() throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja Ramo Recusa","email":"dono@ramorecusa.com",
                         "senha":"segredo123","nomeAdmin":"Dono"}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        String empresa = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        long idEmpresa = ((Number) JsonPath.read(empresa, "$.empresa.idEmpresa")).longValue();

        mvc.perform(put("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("""
                                {"nomeFantasia":"LOJA","idRamo":9999}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("""
                                {"nomeFantasia":"LOJA","idRamo":21}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idRamo").value(21));
    }
}
