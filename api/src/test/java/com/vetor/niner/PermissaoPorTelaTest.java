package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Permissão por tela e por ação (RBAC, V073).
 *
 * <p>As regras que os casos abaixo prendem, todas decididas pelo dono do produto em 2026-08-27:
 * o administrador pode tudo e não tem grade; usuário novo **não vê nada** até ser liberado;
 * permissão é por tela **e** por ação; e tela exclusiva do administrador **não é concedível**.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PermissaoPorTelaTest {

    @Autowired
    MockMvc mvc;

    private record Conta(String token, long idOperador) {
    }

    /** Cria uma conta e, dentro dela, um operador sem permissão nenhuma. */
    private Conta contaComOperador(String sufixo) throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja RBAC %s","email":"dono%s@lojarbac.com",
                         "senha":"segredo123","nomeAdmin":"Dono"}
                        """.formatted(sufixo, sufixo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");

        String eu = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        long idEmpresa = ((Number) JsonPath.read(eu, "$.empresa.idEmpresa")).longValue();

        String operador = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"Operador RBAC","email":"op%s@lojarbac.com","senha":"senha12345",
                                 "administrador":false,"idsEmpresa":[%d]}
                                """.formatted(sufixo, idEmpresa)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Conta(token, ((Number) JsonPath.read(operador, "$.idUsuario")).longValue());
    }

    /** Conta os itens que casam com o filtro — JsonPath devolve o ARRAY do filtro, não o tamanho. */
    private static int contar(String json, String campoVerdadeiro) {
        return ((java.util.List<?>) JsonPath.read(json, "$[?(@." + campoVerdadeiro + " == true)]")).size();
    }

    /** Uma ação de uma tela específica — o filtro do JsonPath devolve lista, então pega-se o 1º. */
    @SuppressWarnings("unchecked")
    private static boolean acao(String json, String chaveTela, String campo) {
        var achados = (java.util.List<java.util.Map<String, Object>>) JsonPath.read(
                json, "$[?(@.chave == '" + chaveTela + "')]");
        return achados.size() == 1 && Boolean.TRUE.equals(achados.get(0).get(campo));
    }

    private String entrar(String email, String senha) throws Exception {
        String resp = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content("""
                        {"email":"%s","senha":"%s"}
                        """.formatted(email, senha)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void catalogoTemAsTelasDoMenu() throws Exception {
        Conta c = contaComOperador("catalogo");
        String telas = mvc.perform(get("/api/v1/telas").header("Authorization", "Bearer " + c.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int quantas = ((Number) JsonPath.read(telas, "$.length()")).intValue();
        assertTrue(quantas >= 60, "o catálogo deveria ter as telas do menu, veio " + quantas);
    }

    /** O administrador não tem grade: a resposta vem toda liberada, sem nenhuma linha gravada. */
    @Test
    void administradorPodeTudoSemGrade() throws Exception {
        Conta c = contaComOperador("admin");
        String minhas = mvc.perform(get("/api/v1/eu/permissoes").header("Authorization", "Bearer " + c.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int total = ((Number) JsonPath.read(minhas, "$.length()")).intValue();
        int acessaveis = contar(minhas, "acessar");
        assertEquals(total, acessaveis, "o administrador acessa todas as telas");
    }

    /**
     * ⚠️ Usuário novo nasce **sem nada**. O contrário — nascer com tudo e ir tirando — deixaria
     * toda tela nova acessível a todo mundo no dia em que fosse criada, e ninguém perceberia.
     */
    @Test
    void usuarioNovoNaoAcessaNadaAteSerLiberado() throws Exception {
        Conta c = contaComOperador("novo");
        String tokenOperador = entrar("opnovo@lojarbac.com", "senha12345");

        String minhas = mvc.perform(get("/api/v1/eu/permissoes").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, contar(minhas, "acessar"));
        assertTrue(c.idOperador() > 0);
    }

    @Test
    void concederPorTelaEPorAcao() throws Exception {
        Conta c = contaComOperador("conceder");

        mvc.perform(put("/api/v1/usuarios/" + c.idOperador() + "/permissoes")
                        .header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON).content("""
                                [{"chaveTela":"pdv","acessar":true,"incluir":true,"alterar":false,"excluir":false},
                                 {"chaveTela":"clientes","acessar":true,"incluir":false,"alterar":true,"excluir":false}]
                                """))
                .andExpect(status().isOk());

        String tokenOperador = entrar("opconceder@lojarbac.com", "senha12345");
        String minhas = mvc.perform(get("/api/v1/eu/permissoes").header("Authorization", "Bearer " + tokenOperador))
                .andReturn().getResponse().getContentAsString();

        assertEquals(2, contar(minhas, "acessar"));
        assertEquals(true, acao(minhas, "pdv", "incluir"));
        assertEquals(false, acao(minhas, "pdv", "alterar"));
        assertEquals(true, acao(minhas, "clientes", "alterar"));
        assertEquals(false, acao(minhas, "clientes", "incluir"));
    }

    /**
     * ⚠️ Tela exclusiva do administrador não é concedível — e a recusa é explícita, com o nome da
     * tela. Ignorar em silêncio faria o admin achar que concedeu, e a permissão "não salvaria"
     * sem explicação nenhuma. Descoberto testando ao vivo: antes desta trava, a DRE era gravada e
     * aparecia no menu do operador, para a rotina negar depois.
     */
    @Test
    void telaExclusivaDoAdministradorNaoPodeSerConcedida() throws Exception {
        Conta c = contaComOperador("dre");
        mvc.perform(put("/api/v1/usuarios/" + c.idOperador() + "/permissoes")
                        .header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON).content("""
                                [{"chaveTela":"relatorio-dre","acessar":true,"incluir":false,"alterar":false,"excluir":false}]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("DRE")));
    }

    /** Quem não é administrador não configura permissão — nem a própria. */
    @Test
    void operadorNaoConfiguraPermissao() throws Exception {
        Conta c = contaComOperador("naoconfig");
        String tokenOperador = entrar("opnaoconfig@lojarbac.com", "senha12345");

        mvc.perform(get("/api/v1/usuarios/" + c.idOperador() + "/permissoes")
                        .header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/usuarios/" + c.idOperador() + "/permissoes")
                        .header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content("""
                                [{"chaveTela":"pdv","acessar":true,"incluir":true,"alterar":true,"excluir":true}]
                                """))
                .andExpect(status().isForbidden());
    }

    /**
     * ⚠️ P8: id de usuário vem do cliente e é sempre conferido contra o tenant da sessão — sem
     * isso, o admin de uma conta configuraria permissões de usuário de outra.
     */
    @Test
    void naoConfiguraUsuarioDeOutraConta() throws Exception {
        Conta minha = contaComOperador("minha");
        Conta alheia = contaComOperador("alheia");

        mvc.perform(get("/api/v1/usuarios/" + alheia.idOperador() + "/permissoes")
                        .header("Authorization", "Bearer " + minha.token()))
                .andExpect(status().isNotFound());
    }

    /** Salvar substitui a grade inteira: o que não vem no corpo deixa de valer. */
    @Test
    void salvarSubstituiAGradeInteira() throws Exception {
        Conta c = contaComOperador("substitui");
        String url = "/api/v1/usuarios/" + c.idOperador() + "/permissoes";

        mvc.perform(put(url).header("Authorization", "Bearer " + c.token()).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"pdv","acessar":true,"incluir":true,"alterar":true,"excluir":true},
                                 {"chaveTela":"clientes","acessar":true,"incluir":true,"alterar":true,"excluir":true}]
                                """))
                .andExpect(status().isOk());

        mvc.perform(put(url).header("Authorization", "Bearer " + c.token()).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"pdv","acessar":true,"incluir":false,"alterar":false,"excluir":false}]
                                """))
                .andExpect(status().isOk());

        String agora = mvc.perform(get(url).header("Authorization", "Bearer " + c.token()))
                .andReturn().getResponse().getContentAsString();
        assertEquals(1, contar(agora, "acessar"),
                "clientes deveria ter saído da grade");
        assertEquals(false, acao(agora, "pdv", "excluir"));
    }
}
