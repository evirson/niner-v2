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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Servidor <b>sem</b> credencial do Mercado Livre.
 *
 * <p>⭐ O que este teste prende não é o 503 — é o fato de a <b>aplicação subir inteira</b> sem a
 * credencial. Um servidor de desenvolvimento, de CI ou de um cliente que não usa marketplace não
 * pode deixar de funcionar por falta de uma chave de terceiro; é a mesma promessa que a cobrança
 * faz com {@code NINER_MP_ACCESS_TOKEN} vazio.
 *
 * <p>Se o bean do OAuth passasse a exigir a credencial para existir, este teste falharia <b>na
 * subida do contexto</b>, e não na asserção — que é exatamente o alarme desejado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MercadoLivreOAuthDesligadoTest {


    /** A empresa em que o usuário entrou — o canal precisa dela desde a V067 (estoque é por empresa). */
    private static long idEmpresaDo(String token) {
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.substring(token.indexOf('.') + 1, token.lastIndexOf('.'))));
        return ((Number) com.jayway.jsonpath.JsonPath.read(payload, "$.eid")).longValue();
    }
    @Autowired
    MockMvc mvc;

    @Test
    void semCredencialAApiSobeEOAutorizarResponde503() throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja Sem ML","email":"dono@lojasemml.com",
                         "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");

        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("""
                        {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                         "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":false,
                         "cfgPermiteQtdDecimal":false,"cfgPermiteEstoqueNegativo":false,
                         "cfgDiasValidadeOrcamento":15,"cfgExigeNumeroVendaDevolucao":false,
                         "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,
                         "cfgConsisteValorContasPagar":false,
                         "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":false}
                        """))
                .andExpect(status().isOk());

        String canal = mvc.perform(post("/api/v1/canais/MERCADO_LIVRE")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"ML DESLIGADO\",\"percPreco\":0,\"idEmpresa\":%d}"
                                .formatted(idEmpresaDo(token))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idCanal = ((Number) JsonPath.read(canal, "$.idCanal")).longValue();

        // O canal cadastra normalmente; só CONECTAR é que não dá — e a mensagem diz por quê.
        mvc.perform(get("/api/v1/canais/%d/mercadolivre/autorizar".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable());
    }
}
