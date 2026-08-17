package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.seguranca.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Painel de Contingência (§9.7, bloco B7) — a superfície HTTP sobre {@code FiscalContingenciaService},
 * já coberto nos fundamentos (DF19, P8) por {@code FiscalContingenciaTest}. Aqui é só a casca:
 * ADMIN-only, entrar/sair manual refletindo no GET, isolamento entre tenants.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FiscalContingenciaControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    private static long idTenantDo(String token) {
        return ((Number) JsonPath.read(payload(token), "$.tid")).longValue();
    }

    /** {@code fiscal_config_empresa} só nasce no primeiro PUT de Configuração Fiscal — entrar/sair
     *  em contingência de uma empresa sem essa linha é o caso do 409 (ver o achado no serviço). */
    private void configurarFiscalMinimo(long idTenant, long idEmpresa) {
        jdbc.sql("""
                        INSERT INTO fiscal_config_empresa (id_tenant, id_empresa, crt, ambiente, emite_nfce)
                        VALUES (?, ?, 1, 'HOMOLOGACAO', true)
                        """)
                .params(idTenant, idEmpresa).update();
    }

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Painel Conting %s","email":"dono%s@lojapainel.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static String payload(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static long idEmpresaDo(String token) {
        return ((Number) JsonPath.read(payload(token), "$.eid")).longValue();
    }

    private String comoOperador(String tokenAdmin) {
        String p = payload(tokenAdmin);
        return tokens.emitir(
                Long.parseLong(JsonPath.read(p, "$.sub").toString()),
                ((Number) JsonPath.read(p, "$.tid")).longValue(),
                ((Number) JsonPath.read(p, "$.eid")).longValue(),
                JsonPath.read(p, "$.email"),
                List.of("OPERADOR"));
    }

    @Test
    void empresaNasceDesligadaEOperadorNaoAcessa() throws Exception {
        String token = assinarNovoTenant("nasce");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(get("/api/v1/fiscal/contingencia/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(false))
                .andExpect(jsonPath("$.serieContingencia").value(9))
                .andExpect(jsonPath("$.pendentes").value(0));

        mvc.perform(get("/api/v1/fiscal/contingencia/" + idEmpresa)
                        .header("Authorization", "Bearer " + comoOperador(token)))
                .andExpect(status().isForbidden());
    }

    /**
     * ⚠️ Achado ao ligar o painel: {@code entrar()}/{@code sair()} são {@code UPDATE}-only —
     * sem {@code fiscal_config_empresa} (o normal antes do primeiro PUT de Configuração Fiscal,
     * F12), o botão "Entrar em contingência" não fazia NADA, sem erro nenhum. Corrigido pra 409
     * explícito.
     */
    @Test
    void entrarSemFiscalConfiguradoDevolve409EmVezDeNaoFazerNada() throws Exception {
        String token = assinarNovoTenant("sem-config");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(post("/api/v1/fiscal/contingencia/" + idEmpresa + "/entrar")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"justificativa\":\"Teste sem config\"}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/fiscal/contingencia/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(false));
    }

    @Test
    void entrarESairManualRefletemNoEstado() throws Exception {
        String token = assinarNovoTenant("entrar-sair");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        configurarFiscalMinimo(idTenant, idEmpresa);

        mvc.perform(post("/api/v1/fiscal/contingencia/" + idEmpresa + "/entrar")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"justificativa\":\"Internet caiu no bairro\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(true))
                .andExpect(jsonPath("$.justificativa").value(org.hamcrest.Matchers.containsString("Internet caiu no bairro")));

        mvc.perform(post("/api/v1/fiscal/contingencia/" + idEmpresa + "/sair")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"justificativa\":\"Internet voltou\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(false));

        mvc.perform(get("/api/v1/fiscal/contingencia/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(false));
    }

    @Test
    void justificativaEmBrancoEhRecusada() throws Exception {
        String token = assinarNovoTenant("sem-justificativa");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(post("/api/v1/fiscal/contingencia/" + idEmpresa + "/entrar")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"justificativa\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("iso-a");
        String tokenB = assinarNovoTenant("iso-b");
        long idEmpresaA = idEmpresaDo(tokenA);
        configurarFiscalMinimo(idTenantDo(tokenA), idEmpresaA);

        mvc.perform(post("/api/v1/fiscal/contingencia/" + idEmpresaA + "/entrar")
                        .header("Authorization", "Bearer " + tokenA).contentType(APPLICATION_JSON)
                        .content("{\"justificativa\":\"Teste isolamento\"}"))
                .andExpect(status().isOk());

        // B pedindo o estado da empresa de A: RLS + filtro explícito não devolvem nada de A, então
        // o GET enxerga "desligada" — nunca o estado ativo do outro tenant.
        mvc.perform(get("/api/v1/fiscal/contingencia/" + idEmpresaA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(false));
    }
}
