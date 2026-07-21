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
 * Parâmetros do sistema (docs/telas/configuracao-geral.md) — `cfg_geral` é singleton por
 * tenant, criado com valores padrão no signup. Só ADMIN acessa (leitura e escrita) —
 * diferente de {@code ConfiguracaoTelaTest}, onde a leitura é liberada a qualquer papel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConfiguracaoGeralTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Geral %s","email":"dono%s@lojageral.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private String comoOperador(String tokenAdmin) {
        String[] partes = tokenAdmin.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        long idUsuario = Long.parseLong(JsonPath.read(payload, "$.sub").toString());
        long idTenant = ((Number) JsonPath.read(payload, "$.tid")).longValue();
        String email = JsonPath.read(payload, "$.email");
        return tokens.emitir(idUsuario, idTenant, email, List.of("OPERADOR"));
    }

    @Test
    void signupJaCriaConfiguracaoComDefaultsDoBanco() throws Exception {
        String token = assinarNovoTenant("defaults");

        mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percentualDescontoVenda").value(0))
                .andExpect(jsonPath("$.cfgUsaVarianteLinha").value(true))
                .andExpect(jsonPath("$.cfgUsaVarianteColuna").value(true));
    }

    @Test
    void adminAtualizaEGetReflete() throws Exception {
        String token = assinarNovoTenant("atualiza");

        String corpo = """
                {"percentualDescontoVenda":15.5,"jurosCrediarioDias":5,"jurosCrediario":2.5,
                 "multaCrediarioDias":10,"multaCrediario":3.0,"cfgUsaVarianteLinha":false,
                 "cfgUsaVarianteColuna":true}
                """;

        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percentualDescontoVenda").value(15.5))
                .andExpect(jsonPath("$.jurosCrediarioDias").value(5))
                .andExpect(jsonPath("$.cfgUsaVarianteLinha").value(false));

        mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percentualDescontoVenda").value(15.5))
                .andExpect(jsonPath("$.multaCrediario").value(3.0));
    }

    @Test
    void operadorNaoPodeLerNemAtualizar() throws Exception {
        String tokenAdmin = assinarNovoTenant("operador");
        String tokenOperador = comoOperador(tokenAdmin);

        mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden());

        String corpo = """
                {"percentualDescontoVenda":10,"jurosCrediarioDias":0,"jurosCrediario":0,
                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaVarianteLinha":true,
                 "cfgUsaVarianteColuna":true}
                """;
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isForbidden());
    }

    @Test
    void percentualForaDoIntervaloEhRejeitado() throws Exception {
        String token = assinarNovoTenant("intervalo");

        String corpo = """
                {"percentualDescontoVenda":150,"jurosCrediarioDias":0,"jurosCrediario":0,
                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaVarianteLinha":true,
                 "cfgUsaVarianteColuna":true}
                """;
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void diasNegativosEhRejeitado() throws Exception {
        String token = assinarNovoTenant("dias-negativos");

        String corpo = """
                {"percentualDescontoVenda":10,"jurosCrediarioDias":-1,"jurosCrediario":0,
                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaVarianteLinha":true,
                 "cfgUsaVarianteColuna":true}
                """;
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("tenant-a");
        String tokenB = assinarNovoTenant("tenant-b");

        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + tokenA)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"percentualDescontoVenda":20,"jurosCrediarioDias":0,"jurosCrediario":0,
                                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaVarianteLinha":true,
                                 "cfgUsaVarianteColuna":true}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percentualDescontoVenda").value(0));
    }
}
