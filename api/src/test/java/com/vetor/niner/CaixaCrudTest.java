package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Abertura de Caixa (2026-07-30) — status/abertura/lista de carteiras. PDV e Recebimento de
 * Crediário exigem caixa aberto antes de efetivar (ver {@link PdvCrudTest}/{@link
 * RecebimentoCrediarioCrudTest}); aqui só o CRUD de {@code financeiro.caixa} em si.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CaixaCrudTest {

    @Autowired
    MockMvc mvc;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Caixa %s","email":"dono%s@lojacaixa.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private long buscarIdCarteiraDinheiro(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        return carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
    }

    /** Busca uma carteira que **não** é a Dinheiro (ex.: PIX, também AVISTA, semeada no signup)
     *  pra exercitar a restrição de 2026-07-31 — abertura de caixa só aceita "Dinheiro". */
    private long buscarIdCarteiraNaoDinheiro(String token) throws Exception {
        // /api/v1/caixa/carteiras já vem filtrado só pra Dinheiro (2026-07-31) — busca a lista
        // completa no cadastro de Tipo de Carteira, que não tem esse filtro.
        String respTipoCarteira = mvc.perform(get("/api/v1/tipos-carteira?limite=50").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.Map<String, Object> pagina = JsonPath.read(respTipoCarteira, "$");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> itens = (java.util.List<java.util.Map<String, Object>>) pagina.get("itens");
        return itens.stream()
                .filter(c -> "PIX".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
    }

    @Test
    void statusInicialNaoTemCaixaAberto() throws Exception {
        String token = assinarNovoTenant("status-inicial");

        mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aberto").value(false))
                .andExpect(jsonPath("$.idCaixa").doesNotExist())
                .andExpect(jsonPath("$.nomeCarteira").doesNotExist());
    }

    @Test
    void listarCarteirasParaAberturaTrazAsSemeadasNoSignup() throws Exception {
        String token = assinarNovoTenant("listar-carteiras");

        mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nomeCarteira").value(org.hamcrest.Matchers.hasItem("DINHEIRO")));
    }

    /** 2026-07-31 — saldo inicial só pode ser em "Dinheiro"; PIX/cartão/crediário (também
     *  semeadas no signup) não podem aparecer nem ser aceitas na abertura. */
    @Test
    void listarCarteirasParaAberturaSoTrazDinheiro() throws Exception {
        String token = assinarNovoTenant("so-dinheiro");

        mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nomeCarteira").value("DINHEIRO"));
    }

    @Test
    void abrirCaixaComCarteiraDiferenteDeDinheiroRespondeErroDeValidacao() throws Exception {
        String token = assinarNovoTenant("carteira-nao-dinheiro");
        long idCarteiraPix = buscarIdCarteiraNaoDinheiro(token);

        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteiraPix)))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aberto").value(false));
    }

    @Test
    void abrirCaixaComSucessoAtualizaOStatus() throws Exception {
        String token = assinarNovoTenant("abrir-sucesso");
        long idCarteira = buscarIdCarteiraDinheiro(token);

        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":150.00}".formatted(idCarteira)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aberto").value(true))
                .andExpect(jsonPath("$.idCarteira").value(idCarteira))
                .andExpect(jsonPath("$.nomeCarteira").value("DINHEIRO"))
                .andExpect(jsonPath("$.saldoInicial").value(150.00));

        mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aberto").value(true))
                .andExpect(jsonPath("$.saldoInicial").value(150.00));
    }

    @Test
    void abrirCaixaDuasVezesNoMesmoDiaRespondeConflito() throws Exception {
        String token = assinarNovoTenant("abrir-duas-vezes");
        long idCarteira = buscarIdCarteiraDinheiro(token);
        String corpo = "{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteira);

        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict());
    }

    @Test
    void abrirCaixaComCarteiraInexistenteRespondeErroDeValidacao() throws Exception {
        String token = assinarNovoTenant("carteira-inexistente");

        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":999999,\"saldoInicial\":100.00}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void caixaDeUmTenantNaoInterfereNoStatusDeOutro() throws Exception {
        String tokenA = assinarNovoTenant("isolamento-a");
        String tokenB = assinarNovoTenant("isolamento-b");
        long idCarteiraA = buscarIdCarteiraDinheiro(tokenA);

        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + tokenA)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":50.00}".formatted(idCarteiraA)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aberto").value(false));
    }
}
