package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login <b>sem identificador de conta</b> (2026-08-27): e-mail + senha, e o sistema descobre a
 * conta sozinho pelo {@code plataforma.diretorio_login} (V071).
 *
 * <p>O caso que motivou tudo, na descrição do dono do produto: o mesmo dono vende cosméticos numa
 * conta e sapatos em outra, possivelmente em células diferentes do parque — e não deve precisar
 * decorar identificador nenhum para entrar em qualquer uma das duas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginSemIdentificadorTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug, long idTenant) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Conta %s","email":"dono%s@contadupla.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Conta"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        long idTenant = ((Number) JsonPath.read(resp, "$.idTenant")).longValue();
        return new TenantNovo(token, JsonPath.read(resp, "$.slug"), idTenant);
    }

    private long primeiraEmpresa(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.empresa.idEmpresa")).longValue();
    }

    /** Cria um operador com e-mail e senha escolhidos — é assim que o mesmo e-mail vai parar em duas contas. */
    private long criarUsuario(String token, String email, String senha) throws Exception {
        long idEmpresa = primeiraEmpresa(token);
        String body = """
                {"nome":"Dono Repetido","email":"%s","senha":"%s","administrador":false,"idsEmpresa":[%d]}
                """.formatted(email, senha, idEmpresa);
        String resp = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idUsuario")).longValue();
    }

    private String login(String corpo) throws Exception {
        return mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private int contarNoDiretorio(String email) {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM plataforma.diretorio_login WHERE email = lower('" + email + "')")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void entraSoComEmailESenha() throws Exception {
        assinarNovoTenant("simples");

        String resp = login("""
                {"email":"donosimples@contadupla.com","senha":"segredo123"}
                """);
        assertTrue(JsonPath.<String>read(resp, "$.token") != null, "deveria emitir o token sem pedir identificador");
        assertEquals(Boolean.FALSE, JsonPath.read(resp, "$.escolherConta"));
    }

    @Test
    void mesmoEmailEmDuasContasComSenhasDiferentesEntraDireto() throws Exception {
        String email = "dono-duas-senhas@contadupla.com";
        TenantNovo cosmeticos = assinarNovoTenant("cosmeticos-senhas");
        TenantNovo sapatos = assinarNovoTenant("sapatos-senhas");
        criarUsuario(cosmeticos.token(), email, "senhaCosmeticos1");
        criarUsuario(sapatos.token(), email, "senhaSapatos1");
        assertEquals(2, contarNoDiretorio(email), "o mesmo e-mail deveria estar nas duas contas do diretório");

        // Senhas diferentes desempatam sozinhas: só uma conta autentica, ninguém vê tela de escolha.
        String naCosmeticos = login("""
                {"email":"%s","senha":"senhaCosmeticos1"}
                """.formatted(email));
        assertEquals(Boolean.FALSE, JsonPath.read(naCosmeticos, "$.escolherConta"));
        assertEquals((int) cosmeticos.idTenant(), (int) (Integer) JsonPath.read(naCosmeticos, "$.idTenant"));

        String nosSapatos = login("""
                {"email":"%s","senha":"senhaSapatos1"}
                """.formatted(email));
        assertEquals((int) sapatos.idTenant(), (int) (Integer) JsonPath.read(nosSapatos, "$.idTenant"));
    }

    @Test
    void mesmoEmailEMesmaSenhaEmDuasContasPedeEscolha() throws Exception {
        String email = "dono-mesma-senha@contadupla.com";
        TenantNovo cosmeticos = assinarNovoTenant("cosmeticos-igual");
        TenantNovo sapatos = assinarNovoTenant("sapatos-igual");
        criarUsuario(cosmeticos.token(), email, "mesmaSenha123");
        criarUsuario(sapatos.token(), email, "mesmaSenha123");

        String resp = login("""
                {"email":"%s","senha":"mesmaSenha123"}
                """.formatted(email));
        assertEquals(Boolean.TRUE, JsonPath.read(resp, "$.escolherConta"));
        assertEquals(2, (int) (Integer) JsonPath.read(resp, "$.contas.length()"));
        assertTrue(JsonPath.<Object>read(resp, "$.token") == null, "não pode emitir token antes da escolha");

        // Segunda volta: mesma credencial + a conta escolhida.
        String comConta = login("""
                {"email":"%s","senha":"mesmaSenha123","idTenant":%d}
                """.formatted(email, sapatos.idTenant()));
        assertEquals(Boolean.FALSE, JsonPath.read(comConta, "$.escolherConta"));
        assertEquals((int) sapatos.idTenant(), (int) (Integer) JsonPath.read(comConta, "$.idTenant"));
    }

    /**
     * A escolha da conta não substitui a senha. Sem isto, quem descobrisse um {@code idTenant}
     * (que é um número pequeno e sequencial) entraria numa conta alheia informando-o.
     */
    @Test
    void contaEscolhidaEmQueASenhaNaoBateEhRejeitada() throws Exception {
        TenantNovo alheia = assinarNovoTenant("alheia");
        assinarNovoTenant("propria");

        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content("""
                        {"email":"donopropria@contadupla.com","senha":"segredo123","idTenant":%d}
                        """.formatted(alheia.idTenant())))
                .andExpect(status().isUnauthorized());
    }

    /**
     * ⚠️ A lista de contas só existe depois que a senha bate. Se a resposta a uma senha errada
     * dissesse "este e-mail está em 2 contas", o login viraria uma consulta pública de quem é
     * cliente — o mesmo motivo pelo qual a recuperação de senha responde 204 sempre.
     */
    @Test
    void senhaErradaNaoRevelaAsContasDoEmail() throws Exception {
        String email = "dono-nao-vaza@contadupla.com";
        TenantNovo a = assinarNovoTenant("vaza-a");
        TenantNovo b = assinarNovoTenant("vaza-b");
        criarUsuario(a.token(), email, "mesmaSenha123");
        criarUsuario(b.token(), email, "mesmaSenha123");

        String corpo = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content("""
                        {"email":"%s","senha":"chuteEspertoDemais"}
                        """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertTrue(!corpo.contains("Conta vaza-a") && !corpo.contains("Conta vaza-b"),
                "a resposta de erro não pode citar as contas do e-mail: " + corpo);
    }

    @Test
    void usuarioInativoNaoEntraENaoDizPorque() throws Exception {
        TenantNovo tenant = assinarNovoTenant("inativo");
        long idUsuario = criarUsuario(tenant.token(), "inativo@contadupla.com", "senha12345");
        long idEmpresa = primeiraEmpresa(tenant.token());

        mvc.perform(put("/api/v1/usuarios/" + idUsuario).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"Dono Repetido","email":"inativo@contadupla.com","ativo":false,
                                 "administrador":false,"idsEmpresa":[%d]}
                                """.formatted(idEmpresa)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content("""
                        {"email":"inativo@contadupla.com","senha":"senha12345"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Credenciais inválidas."));
    }

    /**
     * O diretório é mantido pela trigger, não pelos serviços — é o que garante que ele acompanhe
     * também os caminhos de que ninguém lembra. Aqui: trocar o e-mail move a linha (e o e-mail
     * antigo para de abrir a porta), excluir o usuário a remove.
     */
    @Test
    void trocarOEmailMoveODiretorioEExcluirLimpa() throws Exception {
        TenantNovo tenant = assinarNovoTenant("trigger");
        long idUsuario = criarUsuario(tenant.token(), "antigo@contadupla.com", "senha12345");
        long idEmpresa = primeiraEmpresa(tenant.token());
        assertEquals(1, contarNoDiretorio("antigo@contadupla.com"));

        mvc.perform(put("/api/v1/usuarios/" + idUsuario).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"Dono Repetido","email":"novo@contadupla.com","ativo":true,
                                 "administrador":false,"idsEmpresa":[%d]}
                                """.formatted(idEmpresa)))
                .andExpect(status().isOk());
        assertEquals(0, contarNoDiretorio("antigo@contadupla.com"), "e-mail antigo não pode continuar no diretório");
        assertEquals(1, contarNoDiretorio("novo@contadupla.com"));

        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content("""
                        {"email":"antigo@contadupla.com","senha":"senha12345"}
                        """))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/v1/usuarios/" + idUsuario).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());
        assertEquals(0, contarNoDiretorio("novo@contadupla.com"), "excluir o usuário deveria limpar o diretório");
    }

    /**
     * ⚠️ O diretório é um índice, e índice que a aplicação pode escrever deixa de ser confiável:
     * quem grava é só a trigger, que roda como {@code niner_owner}. Sem este teste, o
     * {@code ALTER DEFAULT PRIVILEGES} da V011 (que dá escrita em toda tabela nova de
     * {@code plataforma} para {@code niner_app}) reabriria a porta em silêncio na próxima
     * migration que recriasse a tabela.
     */
    @Test
    void aplicacaoNaoEscreveNoDiretorio() {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO plataforma.diretorio_login (email, id_tenant, id_usuario) "
                    + "VALUES ('invasor@contadupla.com', 1, 1)");
            throw new AssertionError("niner_app não pode inserir no diretório de login");
        } catch (SQLException esperado) {
            assertTrue(esperado.getMessage().toLowerCase().contains("permission denied"),
                    "esperava negação de permissão, veio: " + esperado.getMessage());
        }
    }
}
