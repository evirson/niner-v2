package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.seguranca.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Configuração de tela (docs/telas/configuracao-tela.md): quais campos aparecem/são
 * obrigatórios em cada tela do produto, por tenant. Só ADMIN grava; qualquer usuário lê.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConfiguracaoTelaTest {

    private static final String CHAVE_TELA = "cadastros.cliente.form";

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Config %s","email":"dono%s@lojaconfig.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    /** Token de OPERADOR para o mesmo usuário/tenant do admin — só pra exercitar o papel. */
    private String comoOperador(String tokenAdmin) {
        String[] partes = tokenAdmin.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        long idUsuario = Long.parseLong(JsonPath.read(payload, "$.sub").toString());
        long idTenant = ((Number) JsonPath.read(payload, "$.tid")).longValue();
        String email = JsonPath.read(payload, "$.email");
        return tokens.emitir(idUsuario, idTenant, email, List.of("OPERADOR"));
    }

    @Test
    void telaSemConfiguracaoUsaDefaultVisivelNaoObrigatorio() throws Exception {
        String token = assinarNovoTenant("default");

        mvc.perform(get("/api/v1/config-tela/" + CHAVE_TELA).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.campo=='email')].visivel").value(true))
                .andExpect(jsonPath("$[?(@.campo=='email')].obrigatorio").value(false));
    }

    @Test
    void adminSalvaConfiguracaoEGetReflete() throws Exception {
        String token = assinarNovoTenant("salva");

        String corpo = """
                [{"campo":"email","visivel":true,"obrigatorio":true},
                 {"campo":"cep","visivel":false,"obrigatorio":false}]
                """;

        mvc.perform(put("/api/v1/config-tela/" + CHAVE_TELA).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.campo=='email')].obrigatorio").value(true))
                .andExpect(jsonPath("$[?(@.campo=='cep')].visivel").value(false));

        mvc.perform(get("/api/v1/config-tela/" + CHAVE_TELA).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.campo=='email')].obrigatorio").value(true))
                .andExpect(jsonPath("$[?(@.campo=='cep')].visivel").value(false));
    }

    @Test
    void operadorNaoPodeSalvarConfiguracao() throws Exception {
        String tokenAdmin = assinarNovoTenant("operador");
        String tokenOperador = comoOperador(tokenAdmin);

        String corpo = """
                [{"campo":"email","visivel":true,"obrigatorio":true}]
                """;

        mvc.perform(put("/api/v1/config-tela/" + CHAVE_TELA).header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isForbidden());
    }

    @Test
    void campoNaoConfiguravelEhRejeitado() throws Exception {
        String token = assinarNovoTenant("campo-invalido");

        String corpo = """
                [{"campo":"nome","visivel":false,"obrigatorio":false}]
                """;

        mvc.perform(put("/api/v1/config-tela/" + CHAVE_TELA).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void obrigatorioSemVisivelEhRejeitado() throws Exception {
        String token = assinarNovoTenant("obrig-oculto");

        String corpo = """
                [{"campo":"email","visivel":false,"obrigatorio":true}]
                """;

        mvc.perform(put("/api/v1/config-tela/" + CHAVE_TELA).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }
}
