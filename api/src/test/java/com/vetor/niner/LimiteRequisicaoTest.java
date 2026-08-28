package com.vetor.niner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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
        // Produção roda atrás do nginx, e é lá que o cabeçalho forjado importa.
        registro.add("niner.limite-requisicao.confiar-proxy", () -> true);
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
    void mensagemDo429ChegaEmUtf8() throws Exception {
        // Em produção (2026-08-19) o corpo saía em ISO-8859-1 — o Tomcat assume isso quando o
        // content-type não declara charset. O response.json() do navegador decodifica SEMPRE como
        // UTF-8, então o visitante lia "Muitas requisies" bem no momento em que a mensagem
        // precisava ser compreensível.
        // IP próprio: o balde é por IP e todos os testes desta classe sairiam do mesmo
        // 127.0.0.1, um consumindo a cota do outro conforme a ordem de execução.
        RequestPostProcessor outroIp = req -> {
            req.setRemoteAddr("10.9.9.9");
            return req;
        };
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/publico/leads").with(outroIp)
                            .contentType(APPLICATION_JSON).content(corpoLead()))
                    .andExpect(status().isNoContent());
        }
        var resp = mvc.perform(post("/api/publico/leads").with(outroIp)
                        .contentType(APPLICATION_JSON).content(corpoLead()))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse();

        org.junit.jupiter.api.Assertions.assertEquals("UTF-8", resp.getCharacterEncoding());
        String corpo = new String(resp.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(corpo.contains("Muitas requisições"),
                "corpo do 429 deveria vir legível em UTF-8, veio: " + corpo);
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
    /**
     * ⛔ <b>{@code X-Forwarded-For} forjado não cria balde novo</b> (achado da auditoria de
     * segurança, 2026-08-27).
     *
     * <p>O filtro lia o <b>primeiro</b> elemento do cabeçalho — que é exatamente o que o cliente
     * manda. O nginx usa {@code proxy_add_x_forwarded_for}, que <b>acrescenta</b> o IP real ao fim
     * e preserva o começo. Bastava variar o valor a cada requisição para o limite deixar de
     * existir, em produção, na superfície pública inteira: signup, lead, recuperação de senha e o
     * código de 4 dígitos do login em duas etapas.
     *
     * <p>Hoje vale o {@code X-Real-IP}, que o nginx <b>sobrescreve</b> e o cliente não consegue
     * forjar.
     */
    @Test
    void cabecalhoDeIpForjadoNaoBurlaOLimite() throws Exception {
        RequestPostProcessor comIpReal = req -> {
            req.setRemoteAddr("203.0.113.77");
            req.addHeader("X-Real-IP", "203.0.113.77");
            return req;
        };

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/publico/leads").with(comIpReal)
                            .header("X-Forwarded-For", "10.0.0." + i + ", 203.0.113.77")
                            .contentType(APPLICATION_JSON).content(corpoLead()))
                    .andExpect(status().is2xxSuccessful());
        }

        // A 4ª, com um "IP" novo inventado no cabeçalho, tem de bater no mesmo balde.
        mvc.perform(post("/api/publico/leads").with(comIpReal)
                        .header("X-Forwarded-For", "10.0.0.99, 203.0.113.77")
                        .contentType(APPLICATION_JSON).content(corpoLead()))
                .andExpect(status().isTooManyRequests());
    }
}
