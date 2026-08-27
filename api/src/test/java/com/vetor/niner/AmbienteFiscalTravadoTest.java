package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trava do ambiente de emissão fiscal (2026-08-27).
 *
 * <p>Decisão do dono do produto: <i>"quando o sistema estiver em produção, o sistema de emissão de
 * notas fiscais não deverá ter a opção homologação ou produção — sempre vai ter que estar em
 * produção, travado nisso"</i>. Hoje a propriedade fica <b>vazia</b>, porque o Nainer está
 * homologando junto às SEFAZ dos estados; esta classe liga a trava para provar o comportamento do
 * dia do go-live.
 *
 * <p>⚠️ <b>O que a trava evita</b> (achado da auditoria de segurança): a série já era imutável
 * depois da primeira nota autorizada, o ambiente não era. Trocá-lo faz as vendas seguintes saírem
 * com {@code tpAmb=2} — sem valor jurídico — <b>enquanto o PDV segue dizendo "Nota autorizada"</b>.
 * E {@code fiscal_numeracao} não separa ambientes: as notas de teste consomem números da sequência
 * de produção, abrindo buracos que depois exigem inutilização formal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "niner.fiscal.ambiente-fixo=PRODUCAO")
class AmbienteFiscalTravadoTest {

    @Autowired
    MockMvc mvc;

    @Test
    void comATravaLigadaOPedidoDeHomologacaoEhIgnorado() throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja Ambiente","email":"dono@lojaambiente.com",
                         "senha":"segredo123","nomeAdmin":"Dono"}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        String eu = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        long idEmpresa = ((Number) JsonPath.read(eu, "$.empresa.idEmpresa")).longValue();

        // A tela precisa saber que a escolha não existe — senão ela oferece um campo que o
        // servidor vai sobrescrever, que é pior do que não oferecer.
        mvc.perform(get("/api/v1/fiscal/config/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ambienteTravado").value(true))
                .andExpect(jsonPath("$.ambiente").value("PRODUCAO"));

        // E um cliente de API pedindo homologação explicitamente é ignorado, não obedecido.
        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("""
                                {"crt":1,"emiteNfce":false,"emiteNfe":false,"ambiente":"HOMOLOGACAO",
                                 "serieNfce":1,"serieNfe":1,"serieContingencia":9}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ambiente").value("PRODUCAO"));

        mvc.perform(get("/api/v1/fiscal/config/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.ambiente").value("PRODUCAO"));
    }
}
