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
 * Ficha completa de empresa (2026-08-19) — fecha uma lacuna real: até então {@code empresa} era
 * só leitura, sem tela nem endpoint pra editar CNPJ/Inscrição Estadual/Inscrição Municipal/
 * código de município IBGE/CNAE, que a Conformidade Fiscal cobra mas nenhum lugar do sistema
 * deixava preencher (achado reportado pelo dono do produto testando o painel).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EmpresaCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Empresa %s","email":"dono%s@lojaempresa.com",
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

    private String corpoCompleto(String cnpj) {
        return """
                {"nomeFantasia":"Nome Fantasia Teste","cnpj":"%s","inscricaoEstadual":"1234567890",
                 "inscricaoMunicipal":"9876543","codigoMunicipioIbge":4106902,"cnae":"4781400",
                 "endereco":"Rua Teste","numero":"100","complemento":"Sala 1","bairro":"Centro",
                 "cidade":"Curitiba","estado":"PR","cep":"80000000","telefone":"41999998888",
                 "email":"contato@empresateste.com"}
                """.formatted(cnpj);
    }

    @Test
    void adminAtualizaOsDadosDaEmpresaComSucesso() throws Exception {
        String token = assinarNovoTenant("atualiza");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(put("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpoCompleto("11222333000181")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nomeFantasia").value("NOME FANTASIA TESTE"))
                .andExpect(jsonPath("$.cnpj").value("11222333000181"))
                .andExpect(jsonPath("$.inscricaoEstadual").value("1234567890"))
                .andExpect(jsonPath("$.codigoMunicipioIbge").value(4106902))
                .andExpect(jsonPath("$.cnae").value("4781400"))
                .andExpect(jsonPath("$.cidade").value("CURITIBA"));

        mvc.perform(get("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cnpj").value("11222333000181"));
    }

    @Test
    void camposEmBrancoSaoAceitosENaoBloqueiamOSalvar() throws Exception {
        String token = assinarNovoTenant("branco");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(put("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cnpj").doesNotExist());
    }

    @Test
    void cnpjInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("cnpj-invalido");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(put("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"cnpj\":\"11111111111111\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("email-invalido");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(put("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"nao-e-email\"}"))
                .andExpect(status().isBadRequest());
    }

    /** Salvar de novo com o MESMO CNPJ que a própria empresa já tem não pode disparar o
     *  conflito de unicidade — só CNPJ de OUTRA empresa do tenant deveria. */
    @Test
    void salvarDeNovoComOMesmoCnpjNaoConflitaConsigoMesma() throws Exception {
        String token = assinarNovoTenant("cnpj-mesmo");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(put("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpoCompleto("11222333000181")))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpoCompleto("11222333000181")))
                .andExpect(status().isOk());
    }

    @Test
    void operadorNaoConsegueVerNemEditarEmpresa() throws Exception {
        String token = assinarNovoTenant("operador-bloqueado");
        long idEmpresa = idEmpresaDo(token);
        String tokenOperador = comoOperador(token);

        mvc.perform(get("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void empresaDeOutroTenantRespondeNaoEncontrado() throws Exception {
        String tokenA = assinarNovoTenant("isolamento-a");
        String tokenB = assinarNovoTenant("isolamento-b");
        long idEmpresaA = idEmpresaDo(tokenA);

        mvc.perform(get("/api/v1/empresas/" + idEmpresaA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void razaoSocialCodigoEMatrizNaoMudamPorEsteEndpoint() throws Exception {
        String token = assinarNovoTenant("estrutural");
        long idEmpresa = idEmpresaDo(token);

        String antes = mvc.perform(get("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String razaoSocialAntes = JsonPath.read(antes, "$.razaoSocial");

        mvc.perform(put("/api/v1/empresas/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpoCompleto("11222333000181")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razaoSocial").value(razaoSocialAntes))
                .andExpect(jsonPath("$.matriz").value(true));
    }
}
