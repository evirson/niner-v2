package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contratação de uma <b>segunda empresa</b> por quem já é cliente (2026-08-27).
 *
 * <p>O caso do dono do produto: ele tem 3 empresas de calçados num tenant e vai contratar uma
 * panificadora. Na tela de contratação o sistema reconhece que o e-mail já tem conta e oferece
 * duas saídas — acrescentar ao grupo existente (visão consolidada, cadastro compartilhado, uma
 * assinatura) ou abrir um grupo separado (cadastro limpo, <b>sem</b> visão de grupo, duas
 * assinaturas).
 *
 * <p>⚠️ A assimetria de senha é a regra central e está coberta aqui: <b>entrar no grupo existente
 * exige a senha</b> (vai mexer em dados que já são de alguém), <b>abrir um grupo separado não</b>
 * (é conta nova, que só por acaso usa o mesmo e-mail).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ContratacaoGrupoTest {

    @Autowired
    MockMvc mvc;

    private String assinar(String nomeLoja, String email, String senha, Integer idRamo, Boolean grupoSeparado,
            int statusEsperado) throws Exception {
        String corpo = """
                {"nomeLoja":"%s","email":"%s","senha":"%s","nomeAdmin":"Dono"%s%s}
                """.formatted(nomeLoja, email, senha,
                idRamo == null ? "" : ",\"idRamo\":" + idRamo,
                grupoSeparado == null ? "" : ",\"criarGrupoSeparado\":" + grupoSeparado);
        return mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().is(statusEsperado))
                .andReturn().getResponse().getContentAsString();
    }

    private String entrar(String email, String senha) throws Exception {
        String resp = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content("""
                        {"email":"%s","senha":"%s"}
                        """.formatted(email, senha)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    /**
     * ⚠️ O tipo do Problem Details é o que a tela de contratação usa para distinguir "já tem
     * conta" (uma decisão a tomar) de um erro de verdade. Comparar pela mensagem quebraria no dia
     * em que alguém melhorasse o texto — daí este teste prender o `type`, não o `detail`.
     */
    @Test
    void emailRepetidoDevolveOTipoQueAbreAEscolha() throws Exception {
        assinar("Calcados Grupo", "dono@calcadosgrupo.com", "senha123456", 10, null, 201);

        mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Padaria Grupo","email":"dono@calcadosgrupo.com",
                         "senha":"senha123456","nomeAdmin":"Dono","idRamo":22}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:niner:erro:conta-ja-existe"));
    }

    /**
     * ⚠️ Grupo separado <b>não</b> pede a senha da conta antiga — e este teste garante isso
     * mandando uma senha diferente de propósito. A conta nova é independente; nada da antiga é
     * tocado.
     */
    @Test
    void grupoSeparadoCriaOutroTenantSemPedirASenhaAntiga() throws Exception {
        String email = "dono@duasgrupos.com";
        String primeira = assinar("Calcados Dois", email, "senhaCalcados1", 10, null, 201);
        long tenantAntigo = ((Number) JsonPath.read(primeira, "$.idTenant")).longValue();

        String segunda = assinar("Padaria Dois", email, "senhaPadaria99", 22, true, 201);
        long tenantNovo = ((Number) JsonPath.read(segunda, "$.idTenant")).longValue();

        assertNotEquals(tenantAntigo, tenantNovo, "grupo separado precisa nascer em outro tenant");

        // Cada senha entra na sua conta — é o login sem identificador resolvendo o empate sozinho.
        assertEquals(tenantAntigo, ((Number) JsonPath.read(
                mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content("""
                                {"email":"%s","senha":"senhaCalcados1"}
                                """.formatted(email)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.idTenant")).longValue());
        assertEquals(tenantNovo, ((Number) JsonPath.read(
                mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content("""
                                {"email":"%s","senha":"senhaPadaria99"}
                                """.formatted(email)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.idTenant")).longValue());
    }

    /**
     * O outro caminho: a empresa entra no grupo que já existe. É o que a tela faz depois de
     * confirmar a senha — login e, com o token, criação da empresa dentro do mesmo tenant.
     */
    @Test
    void acrescentarAoGrupoCriaAEmpresaNoMesmoTenant() throws Exception {
        String email = "dono@mesmogrupo.com";
        String primeira = assinar("Calcados Mesmo", email, "senhaUnica1234", 10, null, 201);
        long idTenant = ((Number) JsonPath.read(primeira, "$.idTenant")).longValue();

        String token = entrar(email, "senhaUnica1234");
        String nova = mvc.perform(post("/api/v1/empresas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("""
                                {"razaoSocial":"Padaria Mesmo","idRamo":22}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idRamo").value(22))
                .andReturn().getResponse().getContentAsString();
        long idEmpresaNova = ((Number) JsonPath.read(nova, "$.idEmpresa")).longValue();

        // Continua um tenant só, agora com duas empresas de ramos diferentes — e a lista traz o
        // ramo, que é o dado com que a tela de contratação monta o aviso de "ramos diferentes".
        String empresas = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertEquals(10, (int) (Integer) JsonPath.read(empresas, "$[0].idRamo"));
        assertEquals(22, (int) (Integer) JsonPath.read(empresas, "$[1].idRamo"));

        // E o login continua entrando na MESMA conta: acrescentar empresa não cria conta nova.
        String depois = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content("""
                        {"email":"%s","senha":"senhaUnica1234"}
                        """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(idTenant, ((Number) JsonPath.read(depois, "$.idTenant")).longValue());

        // A empresa nova é alcançável dentro da mesma conta (e não numa conta paralela).
        mvc.perform(get("/api/v1/empresas/" + idEmpresaNova).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razaoSocial").value("PADARIA MESMO"));
    }

    /**
     * ⚠️ A trava de e-mail repetido continua de pé para quem NÃO escolheu nada: sem a bandeira, o
     * cadastro é recusado. Ela existe desde 2026-08-19 porque repetir o cadastro por engano
     * dividia os dados do mesmo lojista entre duas contas sem ele perceber.
     */
    @Test
    void semABandeiraOCadastroRepetidoContinuaRecusado() throws Exception {
        assinar("Loja Trava", "dono@lojatrava.com", "senha123456", null, null, 201);
        assinar("Loja Trava 2", "dono@lojatrava.com", "senha123456", null, false, 409);
    }
}
