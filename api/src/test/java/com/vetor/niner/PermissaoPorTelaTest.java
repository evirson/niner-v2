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
        // 57 em 2026-08-27. O piso é frouxo de propósito — o catálogo cresce com o produto —,
        // mas existe para pegar o caso que importa: uma migration que esvazia ou corta a lista.
        assertTrue(quantas >= 50, "o catálogo deveria ter as telas do menu, veio " + quantas);
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
        // ⭐ INVERTIDO em 2026-08-27 (auditoria de segurança). Este teste dizia o contrário — que o
        // PDV NÃO ficava com "incluir" — e o comentário justificava assim: "no PDV, acessar já é
        // vender". Era falso: `POST /pdv/vendas` traduz para INCLUIR no interceptor, então
        // **nenhum operador conseguia vender**, e a concessão era descartada no salvamento porque
        // a V076 tinha marcado a tela como sem a ação.
        //
        // ⚠️ O teste foi invertido, não apagado: ele prendia o comportamento antigo e passava
        // verde enquanto o caixa estava travado. Hoje prende a regra certa — quem recebe "incluir"
        // no PDV fica com ele. Quem impede a divergência de voltar é `AcoesPorTelaConferemTest`.
        assertEquals(true, acao(minhas, "pdv", "incluir"), "sem isto o operador não vende");
        assertEquals(false, acao(minhas, "pdv", "alterar"));
        assertEquals(true, acao(minhas, "clientes", "alterar"), "cadastro tem alterar");
        assertEquals(false, acao(minhas, "clientes", "incluir"), "não foi concedido");
    }

    /**
     * ⚠️ Tela exclusiva do administrador não é concedível — e a recusa é explícita, com o nome da
     * tela — e, desde 2026-08-27, ela nem aparece na grade. Ignorar em silêncio faria o admin achar que concedeu, e a permissão "não salvaria"
     * sem explicação nenhuma. Descoberto testando ao vivo: antes desta trava, a DRE era gravada e
     * aparecia no menu do operador, para a rotina negar depois.
     */
    @Test
    void telaExclusivaDoAdministradorNaoPodeSerConcedida() throws Exception {
        Conta c = contaComOperador("dre");
        mvc.perform(put("/api/v1/usuarios/" + c.idOperador() + "/permissoes")
                        .header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON).content("""
                                [{"chaveTela":"empresas","acessar":true,"incluir":false,"alterar":false,"excluir":false}]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Empresas")));
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
                                [{"chaveTela":"clientes","acessar":true,"incluir":true,"alterar":true,"excluir":true},
                                 {"chaveTela":"produtos","acessar":true,"incluir":true,"alterar":true,"excluir":true}]
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
                "produtos deveria ter saído da grade");
        assertEquals(false, acao(agora, "clientes", "excluir"), "o segundo PUT não concedeu excluir");
    }
/**
     * ⚠️ <b>Ninguém delega o que não tem</b> — regra dele (2026-08-27): "as permissões que ele pode
     * delegar para este novo usuário são no máximo as permissões que ele tem". O caso do enunciado:
     * um usuário com Produtos, Usuários e PDV criando outro usuário.
     */
    @Test
    void naoDelegaOQueNaoTem() throws Exception {
        Conta c = contaComOperador("teto");
        String url = "/api/v1/usuarios/" + c.idOperador() + "/permissoes";

        mvc.perform(put(url).header("Authorization", "Bearer " + c.token()).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"produtos","acessar":true,"incluir":true,"alterar":true,"excluir":false},
                                 {"chaveTela":"usuarios","acessar":true,"incluir":true,"alterar":true,"excluir":false},
                                 {"chaveTela":"pdv","acessar":true,"incluir":false,"alterar":false,"excluir":false}]
                                """))
                .andExpect(status().isOk());

        String tokenOperador = entrar("opteto@lojarbac.com", "senha12345");
        long idEmpresa = ((Number) JsonPath.read(
                mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tokenOperador))
                        .andReturn().getResponse().getContentAsString(), "$.empresa.idEmpresa")).longValue();

        // Ele CRIA um usuário — o que só é possível porque a tela Usuários deixou de ser exclusiva
        // do administrador e ele recebeu "incluir" nela.
        String novo = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"Ajudante","email":"ajudante-teto@lojarbac.com","senha":"senha12345",
                                 "administrador":false,"idsEmpresa":[%d]}
                                """.formatted(idEmpresa)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String urlNovo = "/api/v1/usuarios/" + ((Number) JsonPath.read(novo, "$.idUsuario")).longValue()
                + "/permissoes";

        // TELA que ele não tem → recusado
        mvc.perform(put(urlNovo).header("Authorization", "Bearer " + tokenOperador).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"contas-pagar","acessar":true,"incluir":false,"alterar":false,"excluir":false}]
                                """))
                .andExpect(status().isForbidden());

        // AÇÃO que ele não tem, numa tela que ele tem → recusado (ele não pode excluir produto)
        mvc.perform(put(urlNovo).header("Authorization", "Bearer " + tokenOperador).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"produtos","acessar":true,"incluir":true,"alterar":true,"excluir":true}]
                                """))
                .andExpect(status().isForbidden());

        // dentro do teto → passa
        mvc.perform(put(urlNovo).header("Authorization", "Bearer " + tokenOperador).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"produtos","acessar":true,"incluir":true,"alterar":false,"excluir":false}]
                                """))
                .andExpect(status().isOk());
    }

    /**
     * ⚠️ O teto sozinho seria contornável em um passo: bastaria marcar tudo para si mesmo. Esta
     * trava e a seguinte são o que o tornam efetivo.
     */
    @Test
    void naoAlteraAPropriaGrade() throws Exception {
        Conta c = contaComOperador("propria");
        mvc.perform(put("/api/v1/usuarios/" + c.idOperador() + "/permissoes")
                        .header("Authorization", "Bearer " + c.token()).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"usuarios","acessar":true,"incluir":true,"alterar":true,"excluir":false}]
                                """))
                .andExpect(status().isOk());

        String tokenOperador = entrar("oppropria@lojarbac.com", "senha12345");
        mvc.perform(put("/api/v1/usuarios/" + c.idOperador() + "/permissoes")
                        .header("Authorization", "Bearer " + tokenOperador).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"usuarios","acessar":true,"incluir":true,"alterar":true,"excluir":true}]
                                """))
                .andExpect(status().isForbidden());
    }

    /** ⚠️ E não mexe em quem está acima — seria tirar o acesso de um colega mais graduado. */
    @Test
    void naoAlteraQuemTemMaisPermissao() throws Exception {
        Conta c = contaComOperador("acima");
        long idEmpresa = ((Number) JsonPath.read(
                mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + c.token()))
                        .andReturn().getResponse().getContentAsString(), "$.empresa.idEmpresa")).longValue();

        String outro = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"Graduado","email":"graduado-acima@lojarbac.com","senha":"senha12345",
                                 "administrador":false,"idsEmpresa":[%d]}
                                """.formatted(idEmpresa)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idGraduado = ((Number) JsonPath.read(outro, "$.idUsuario")).longValue();

        mvc.perform(put("/api/v1/usuarios/" + c.idOperador() + "/permissoes")
                        .header("Authorization", "Bearer " + c.token()).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"usuarios","acessar":true,"incluir":true,"alterar":true,"excluir":false}]
                                """))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/usuarios/" + idGraduado + "/permissoes")
                        .header("Authorization", "Bearer " + c.token()).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"contas-pagar","acessar":true,"incluir":false,"alterar":false,"excluir":false}]
                                """))
                .andExpect(status().isOk());

        String tokenOperador = entrar("opacima@lojarbac.com", "senha12345");
        mvc.perform(put("/api/v1/usuarios/" + idGraduado + "/permissoes")
                        .header("Authorization", "Bearer " + tokenOperador).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"usuarios","acessar":true,"incluir":false,"alterar":false,"excluir":false}]
                                """))
                .andExpect(status().isForbidden());
    }
/**
     * ⚠️ A grade mostra ao não-admin <b>apenas o que ele pode delegar</b> — telas e ações. Mostrar
     * tudo e recusar no Salvar é o mesmo incômodo que o dono do produto relatou com o "liberar
     * tudo": marcar dezenas de itens e descobrir no fim que metade não podia.
     */
    @Test
    void gradeMostraSoOQuePodeDelegar() throws Exception {
        Conta c = contaComOperador("recorte");
        mvc.perform(put("/api/v1/usuarios/" + c.idOperador() + "/permissoes")
                        .header("Authorization", "Bearer " + c.token()).contentType(APPLICATION_JSON)
                        .content("""
                                [{"chaveTela":"produtos","acessar":true,"incluir":true,"alterar":false,"excluir":false},
                                 {"chaveTela":"usuarios","acessar":true,"incluir":true,"alterar":true,"excluir":false}]
                                """))
                .andExpect(status().isOk());

        String tokenOperador = entrar("oprecorte@lojarbac.com", "senha12345");
        long idEmpresa = ((Number) JsonPath.read(
                mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tokenOperador))
                        .andReturn().getResponse().getContentAsString(), "$.empresa.idEmpresa")).longValue();
        String novo = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"Novato","email":"novato-recorte@lojarbac.com","senha":"senha12345",
                                 "administrador":false,"idsEmpresa":[%d]}
                                """.formatted(idEmpresa)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idNovo = ((Number) JsonPath.read(novo, "$.idUsuario")).longValue();

        String grade = mvc.perform(get("/api/v1/usuarios/" + idNovo + "/permissoes")
                        .header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // só as duas telas dele, e não as 48 do catálogo
        assertEquals(2, ((java.util.List<?>) JsonPath.read(grade, "$")).size());
        // e a ação que ele NÃO tem some da linha: ele não pode alterar produto
        assertEquals(false, acao(grade, "produtos", "temAlterar"));
        assertEquals(true, acao(grade, "produtos", "temIncluir"));
        assertEquals(true, acao(grade, "usuarios", "temAlterar"));

        // o administrador continua vendo a grade inteira
        String gradeAdmin = mvc.perform(get("/api/v1/usuarios/" + idNovo + "/permissoes")
                        .header("Authorization", "Bearer " + c.token()))
                .andReturn().getResponse().getContentAsString();
        assertTrue(((java.util.List<?>) JsonPath.read(gradeAdmin, "$")).size() > 40,
                "o admin vê o catálogo todo");
    }

    /**
     * ⛔ <b>Operador jamais acessa o cadastro do administrador</b> (decisão do dono do produto,
     * 2026-08-27, depois da auditoria de segurança).
     *
     * <p>O teto de delegação protege a <b>grade</b>; não protegia o <b>registro</b>. Com
     * "Usuários: alterar" — concessão plausível, "deixe o gerente cadastrar gente" — dava para
     * reescrever senha e e-mail do administrador (e desligar o segundo fator dele antes) e entrar
     * no lugar. O {@code DELETE} era pior: apagar o administrador deixa a conta <b>sem admin para
     * sempre</b>, porque criar grava {@code administrador = false} fixo e a restrição de um-só é
     * imutável.
     *
     * <p>⚠️ Tudo responde <b>404</b>, não 403: "você não pode mexer neste" confirmaria qual id é o
     * do administrador. Pelo mesmo motivo ele some da listagem.
     */
    @Test
    void operadorNaoAlcancaOCadastroDoAdministrador() throws Exception {
        Conta c = contaComOperador("alvo-admin");
        // A concessão que abria o buraco: a tela Usuários inteira.
        mvc.perform(put("/api/v1/usuarios/" + c.idOperador() + "/permissoes")
                        .header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON).content("""
                                [{"chaveTela":"usuarios","acessar":true,"incluir":true,"alterar":true,"excluir":true}]
                                """))
                .andExpect(status().isOk());
        String tokenOperador = entrar("opalvo-admin@lojarbac.com", "senha12345");

        // Qual é o id do admin? O operador não descobre pela lista: ele não está lá.
        String lista = mvc.perform(get("/api/v1/usuarios?status=TODOS")
                        .header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, ((java.util.List<?>) JsonPath.read(lista, "$.itens[?(@.administrador == true)]")).size(),
                "o administrador não pode aparecer na listagem de quem não é administrador");

        // Mas o id é adivinhável (o admin nasce no signup) — então cada caminho é fechado de novo.
        String listaAdmin = mvc.perform(get("/api/v1/usuarios?status=TODOS")
                        .header("Authorization", "Bearer " + c.token()))
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        var adminsNaLista = (java.util.List<java.util.Map<String, Object>>) JsonPath.read(
                listaAdmin, "$.itens[?(@.administrador == true)]");
        long idAdmin = ((Number) adminsNaLista.get(0).get("idUsuario")).longValue();

        mvc.perform(get("/api/v1/usuarios/" + idAdmin).header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/v1/usuarios/" + idAdmin).header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"ADMIN","email":"atacante@x.com","senha":"NovaSenha123",
                                 "idsEmpresa":[1],"exigeCodigoLogin":false}
                                """))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/usuarios/" + idAdmin).header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/usuarios/" + idAdmin + "/permissoes")
                        .header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isNotFound());

        // E o administrador segue entrando com a senha dele — a tentativa não pode ter passado.
        assertTrue(entrar("donoalvo-admin@lojarbac.com", "segredo123").length() > 20);
    }
    /**
     * ⭐ <b>O teste de estreia que faltava:</b> um operador com a grade cheia <b>chega a vender</b>.
     *
     * <p>Nenhum teste exercitava o PDV como não-administrador, e por isso o defeito passou: a tela
     * estava catalogada sem "incluir", a concessão era descartada no salvamento, e
     * {@code POST /pdv/vendas} respondia <b>403 para todo operador</b> — com o administrador jurando
     * ter liberado tudo.
     *
     * <p>⚠️ O que se afirma aqui é <b>"não é 403"</b>, não "a venda deu certo": montar uma venda
     * completa exige caixa aberto, produto com estoque e forma de pagamento, e isso já é coberto
     * por {@code PdvVendaCrudTest}. O que <b>só</b> este teste cobre é a fronteira da permissão —
     * e um 400 por corpo vazio prova que a requisição <b>atravessou</b> o interceptor.
     */
    @Test
    void operadorComGradeCheiaAtravessaOInterceptorDoPdv() throws Exception {
        Conta c = contaComOperador("pdv-estreia");
        PermissaoDeTeste.liberarTudo(mvc, c.token(), c.idOperador());
        String tokenOperador = entrar("oppdv-estreia@lojarbac.com", "senha12345");

        int status = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();

        assertTrue(status != 403,
                "operador com a grade cheia não pode tomar 403 no PDV — foi assim que ninguém vendia");
    }
}
