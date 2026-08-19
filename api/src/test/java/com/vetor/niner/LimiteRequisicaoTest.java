package com.vetor.niner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Limite de requisições da superfície pública (bloqueador nº 4 de produção). Cobre o que precisa
 * valer ao mesmo tempo: escrita anônima é contida, o beacon tem folga própria, leitura de catálogo
 * não é estrangulada e o webhook de gateway <b>nunca</b> é recusado (recusar notificação é perder
 * confirmação de pagamento).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LimiteRequisicaoTest {

    @DynamicPropertySource
    static void limitesBaixos(DynamicPropertyRegistry registro) {
        registro.add("niner.limite-requisicao.habilitado", () -> true);
        registro.add("niner.limite-requisicao.escrita-por-minuto", () -> 3);
        registro.add("niner.limite-requisicao.beacon-por-minuto", () -> 5);
    }

    @Autowired
    MockMvc mvc;

    private String corpoLead() {
        return """
                {"nome":"Interessado","email":"lead-%s@teste.com","nomeLoja":"Loja"}
                """.formatted(UUID.randomUUID());
    }

    private String corpoBeacon() {
        return """
                {"visitanteId":"%s","eventos":[{"tipo":"PAGEVIEW","caminho":"/"}]}
                """.formatted(UUID.randomUUID());
    }

    @Test
    void escritaAnonimaEhContidaComProblemDetailE429() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/publico/leads").contentType(APPLICATION_JSON).content(corpoLead()))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(post("/api/publico/leads").contentType(APPLICATION_JSON).content(corpoLead()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("urn:niner:erro:limite-de-requisicoes"));
    }

    @Test
    void beaconTemFolgaPropriaENaoRoubaACotaDaEscrita() throws Exception {
        // 5 beacons passam mesmo com o limite de escrita (3) já pequeno: baldes separados.
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/publico/eventos").contentType(APPLICATION_JSON).content(corpoBeacon()))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(post("/api/publico/eventos").contentType(APPLICATION_JSON).content(corpoBeacon()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void leituraDeCatalogoNaoEhLimitada() throws Exception {
        for (int i = 0; i < 12; i++) {
            mvc.perform(get("/api/publico/planos")).andExpect(status().isOk());
        }
    }

    @Test
    void webhookDeGatewayNuncaEhRecusadoPorLimite() throws Exception {
        // Sem segredo configurado o webhook aceita e só grava; o que importa aqui é que a 12ª
        // notificação seguida NÃO volta 429 — perder notificação é perder confirmação de pagamento.
        for (int i = 0; i < 12; i++) {
            mvc.perform(post("/api/publico/webhooks/mercadopago?data.id=1&type=payment")
                            .contentType(APPLICATION_JSON)
                            .content("{\"id\":%d,\"type\":\"payment\",\"data\":{\"id\":\"1\"}}".formatted(500 + i)))
                    .andExpect(status().isOk());
        }
    }
}
