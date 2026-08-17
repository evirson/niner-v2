package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.seguranca.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Configuração fiscal por empresa (docs/telas/fiscal-configuracao.md).
 *
 * <p>Diferente de {@code ConfiguracaoGeralTest}: aqui o signup <b>não</b> semeia a linha — a
 * ausência é o estado inicial normal e significa "fiscal desligado" (F12). Por isso o primeiro
 * caso de teste é justamente o GET numa empresa sem configuração.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FiscalConfigCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    // ---------------------------------------------------------------- helpers

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Fiscal %s","email":"dono%s@lojafiscal.com",
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

    /** A empresa da sessão vem do claim {@code eid} (docs/telas/login-empresa.md). */
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

    /** Corpo válido mínimo — Simples Nacional, fiscal desligado, séries default. */
    private static String corpo(int crt, boolean emiteNfce,
                                int serieNfce, int serieContingencia, String extras) {
        return """
                {"crt":%d,"emiteNfce":%b,"emiteNfe":false,
                 "ambiente":"HOMOLOGACAO","serieNfce":%d,"serieNfe":1,
                 "serieContingencia":%d%s}
                """.formatted(crt, emiteNfce, serieNfce, serieContingencia, extras);
    }

    private static String corpoPadrao() {
        return corpo(1, false, 1, 9, "");
    }

    // ---------------------------------------------------------------- leitura

    @Test
    void empresaSemConfiguracaoRespondeDefaultsSemConfigurado() throws Exception {
        String token = assinarNovoTenant("sem-config");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(get("/api/v1/fiscal/config/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurado").value(false))
                .andExpect(jsonPath("$.crt").value(1))
                .andExpect(jsonPath("$.ambiente").value("HOMOLOGACAO"))
                .andExpect(jsonPath("$.emiteNfce").value(false))
                .andExpect(jsonPath("$.serieContingencia").value(9))
                .andExpect(jsonPath("$.cscConfigurado").value(false));
    }

    @Test
    void empresaDeOutroTenantResponde404() throws Exception {
        String tokenA = assinarNovoTenant("iso-a");
        String tokenB = assinarNovoTenant("iso-b");
        long empresaDeA = idEmpresaDo(tokenA);

        // Filtro explícito de id_tenant no SQL (P8/F8): a empresa de A não existe para B.
        mvc.perform(get("/api/v1/fiscal/config/" + empresaDeA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void listaEmpresasMostraQuaisTemFiscalConfigurado() throws Exception {
        String token = assinarNovoTenant("lista");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(get("/api/v1/fiscal/config/empresas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].configurado").value(false));

        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoPadrao()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/fiscal/config/empresas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].configurado").value(true));
    }

    // ---------------------------------------------------------------- gravação

    @Test
    void primeiroSalvarCriaALinha() throws Exception {
        String token = assinarNovoTenant("cria");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo(2, false, 1, 9, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurado").value(true))
                .andExpect(jsonPath("$.crt").value(2));

        mvc.perform(get("/api/v1/fiscal/config/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurado").value(true))
                .andExpect(jsonPath("$.crt").value(2));
    }

    // ---------------------------------------------------------------- DF37 (escopo do produto)

    /**
     * O Niner atende Simples Nacional (CRT 1 e 2) e MEI (CRT 4). CRT 3 é Lucro Real ou Presumido, e
     * a recusa tem que explicar que é <b>escopo</b>: quem lê "não suportado" cadastra CRT 1 para
     * destravar a tela e passa a emitir toda nota com CSOSN e PIS/COFINS zerado.
     */
    @Test
    void crt3EhRejeitadoPorEstarForaDoEscopoDoProduto() throws Exception {
        String token = assinarNovoTenant("crt3-fora");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo(3, false, 1, 9, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("fora do escopo")));
    }

    @Test
    void meiEhAceito() throws Exception {
        String token = assinarNovoTenant("mei");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo(4, false, 1, 9, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crt").value(4));
    }

    // ---------------------------------------------------------------- séries

    @Test
    void serieContingenciaIgualASerieNfceEhRejeitada() throws Exception {
        String token = assinarNovoTenant("serie");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo(1, false, 1, 1, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    // ---------------------------------------------------------------- CSC (write-only)

    @Test
    void cscNaoVoltaNoGetESobreviveASalvamentoSemToken() throws Exception {
        String token = assinarNovoTenant("csc");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo(1, false, 1, 9,
                                ",\"cscId\":\"000001\",\"cscToken\":\"SEGREDO-DO-CSC\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cscConfigurado").value(true))
                // F7: o token não aparece em nenhum campo da resposta.
                .andExpect(jsonPath("$.cscToken").doesNotExist());

        // PUT que mexe só na série NÃO pode zerar o CSC em silêncio.
        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo(1, false, 2, 9, ",\"cscId\":\"000001\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cscConfigurado").value(true))
                .andExpect(jsonPath("$.serieNfce").value(2));
    }

    @Test
    void removerCscApagaOToken() throws Exception {
        String token = assinarNovoTenant("csc-remove");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo(1, false, 1, 9,
                                ",\"cscToken\":\"SEGREDO-DO-CSC\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cscConfigurado").value(true));

        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo(1, false, 1, 9, ",\"removerCsc\":true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cscConfigurado").value(false));
    }

    // ---------------------------------------------------------------- gate F11

    @Test
    void ligarNfceSemPrecondicoesRecusaEMantemGateDesligado() throws Exception {
        String token = assinarNovoTenant("gate");
        long idEmpresa = idEmpresaDo(token);

        // Tenant recém-assinado: empresa sem CNPJ/IE/município/CNAE e sem certificado.
        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo(1, true, 1, 9, "")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").exists());

        // O gate NÃO pode ter sido ligado pela tentativa recusada.
        mvc.perform(get("/api/v1/fiscal/config/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emiteNfce").value(false));
    }

    @Test
    void desligarNfceNuncaEhBloqueado() throws Exception {
        String token = assinarNovoTenant("desliga");
        long idEmpresa = idEmpresaDo(token);

        // Salvar com o gate desligado passa mesmo sem nenhuma precondição atendida — o gate só
        // é conferido quando está sendo LIGADO.
        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoPadrao()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emiteNfce").value(false));
    }

    // ---------------------------------------------------------------- papéis

    @Test
    void operadorNaoLeNemGrava() throws Exception {
        String tokenAdmin = assinarNovoTenant("papel");
        String tokenOperador = comoOperador(tokenAdmin);
        long idEmpresa = idEmpresaDo(tokenAdmin);

        mvc.perform(get("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content(corpoPadrao()))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/fiscal/config/empresas")
                        .header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- isolamento

    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("iso-cfg-a");
        String tokenB = assinarNovoTenant("iso-cfg-b");

        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresaDo(tokenA))
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(APPLICATION_JSON)
                        .content(corpo(2, false, 1, 9, "")))
                .andExpect(status().isOk());

        // O tenant B segue sem configuração — configurar A não vaza para B.
        mvc.perform(get("/api/v1/fiscal/config/" + idEmpresaDo(tokenB))
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurado").value(false));
    }
}
