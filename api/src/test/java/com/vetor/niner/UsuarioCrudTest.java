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
import java.sql.SQLException;
import java.sql.Statement;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRUD de usuário (docs/telas/usuario.md) — mesmo padrão de {@link FuncionarioCrudTest}: cada
 * teste assina um tenant novo (self-service, R12) e usa o token real emitido pelo ADMIN criado
 * no signup. Toda a superfície é ADMIN-only — diferente de funcionário/cliente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UsuarioCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Usuario %s","email":"dono%s@lojausuario.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new TenantNovo(JsonPath.read(resp, "$.token"), JsonPath.read(resp, "$.slug"));
    }

    private long buscarPrimeiraEmpresa(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$[0].idEmpresa")).longValue();
    }

    @Test
    void criaUsuarioComDadosCompletos() throws Exception {
        TenantNovo tenant = assinarNovoTenant("completo");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());

        String usuario = """
                {"nome":"maria operadora","email":"MARIA@LOJA.COM","senha":"senha1234",
                 "administrador":false,"idsEmpresa":[%d]}
                """.formatted(idEmpresa);

        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(usuario))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("MARIA OPERADORA"))
                .andExpect(jsonPath("$.email").value("maria@loja.com"))
                .andExpect(jsonPath("$.administrador").value(false))
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.empresas.length()").value(1));
    }

    @Test
    void usuarioSemEmpresaSelecionadaEhRejeitado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sem-empresa");

        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"Sem Empresa\",\"email\":\"semempresa@loja.com\",\"senha\":\"senha1234\",\"idsEmpresa\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void usuarioNovoSemSenhaEhRejeitado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sem-senha");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());

        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"Sem Senha\",\"email\":\"semsenha@loja.com\",\"idsEmpresa\":[" + idEmpresa + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void senhaComMenosDeOitoCaracteresEhRejeitada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("senha-curta");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());

        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"Senha Curta\",\"email\":\"senhacurta@loja.com\",\"senha\":\"123\",\"idsEmpresa\":[" + idEmpresa + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailInvalidoEhRejeitado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("email-invalido");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());

        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"Email Invalido\",\"email\":\"nao-e-email\",\"senha\":\"senha1234\",\"idsEmpresa\":[" + idEmpresa + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailDuplicadoRespondeConflito() throws Exception {
        TenantNovo tenant = assinarNovoTenant("email-duplicado");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());
        String usuario = """
                {"nome":"Usuario Um","email":"duplicado@loja.com","senha":"senha1234","idsEmpresa":[%d]}
                """.formatted(idEmpresa);

        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(usuario))
                .andExpect(status().isCreated());

        String usuarioDuplicado = """
                {"nome":"Usuario Dois","email":"DUPLICADO@loja.com","senha":"senha1234","idsEmpresa":[%d]}
                """.formatted(idEmpresa);

        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(usuarioDuplicado))
                .andExpect(status().isConflict());
    }

    @Test
    void operadorNaoConsegueAcessarUsuarios() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-bloqueado");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());
        String usuario = """
                {"nome":"Operador Teste","email":"operador@loja.com","senha":"senha1234",
                 "administrador":false,"idsEmpresa":[%d]}
                """.formatted(idEmpresa);
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(usuario))
                .andExpect(status().isCreated());

        String login = """
                {"slug":"%s","email":"operador@loja.com","senha":"senha1234"}
                """.formatted(tenant.slug());
        String resp = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tokenOperador = JsonPath.read(resp, "$.token");

        mvc.perform(get("/api/v1/usuarios").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminNaoConsegueExcluirAPropriaConta() throws Exception {
        TenantNovo tenant = assinarNovoTenant("auto-exclusao");
        String resp = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long idProprioUsuario = ((Number) JsonPath.read(resp, "$.usuario.idUsuario")).longValue();

        mvc.perform(delete("/api/v1/usuarios/" + idProprioUsuario).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tentativaDeMudarOProprioNivelEhIgnoradaContinuaAdmin() throws Exception {
        // Só existe um ADMIN por tenant, para sempre (2026-07-28) — a tela de usuário nem
        // aceita o campo "administrador" no request; mesmo mandando um, é ignorado.
        TenantNovo tenant = assinarNovoTenant("auto-nivel");
        String respEu = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long idProprioUsuario = ((Number) JsonPath.read(respEu, "$.usuario.idUsuario")).longValue();
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());

        String tentativaDeRebaixar = """
                {"nome":"Dono Da Loja Editado","email":"donoauto-nivel@lojausuario.com","administrador":false,
                 "idsEmpresa":[%d]}
                """.formatted(idEmpresa);
        mvc.perform(put("/api/v1/usuarios/" + idProprioUsuario).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(tentativaDeRebaixar))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("DONO DA LOJA EDITADO"))
                .andExpect(jsonPath("$.administrador").value(true));
    }

    @Test
    void criarUsuarioIgnoraTentativaDeMarcarComoAdministrador() throws Exception {
        TenantNovo tenant = assinarNovoTenant("cria-nao-vira-admin");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());

        String usuario = """
                {"nome":"Tentativa Admin","email":"tentativaadmin@lojausuario.com","senha":"senha1234",
                 "administrador":true,"idsEmpresa":[%d]}
                """.formatted(idEmpresa);
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(usuario))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.administrador").value(false));
    }

    @Test
    void bancoRejeitaUmSegundoAdministradorNoMesmoTenant() throws Exception {
        // Garantia de banco (usuario_um_admin_uk, V015) por trás da regra "só um ADMIN por
        // tenant, para sempre" — defesa em profundidade além da aplicação nunca gravar
        // administrador=true fora do signup.
        TenantNovo tenant = assinarNovoTenant("dois-admins");
        long idTenant = extrairIdTenant(tenant.token());
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());

        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> st.executeUpdate(
                    "INSERT INTO usuario (id_tenant, id_empresa, nome_usuario, email, senha_hash, administrador) VALUES ("
                            + idTenant + ", " + idEmpresa + ", 'SEGUNDO ADMIN', 'segundoadmin@lojausuario.com', 'hash', true)"));
        }
    }

    @Test
    void atualizarUsuarioComSenhaEmBrancoMantemSenhaAtual() throws Exception {
        TenantNovo tenant = assinarNovoTenant("mantem-senha");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());
        String usuario = """
                {"nome":"Mantem Senha","email":"mantemsenha@loja.com","senha":"senhaoriginal1",
                 "idsEmpresa":[%d]}
                """.formatted(idEmpresa);
        String resp = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(usuario))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idUsuario = ((Number) JsonPath.read(resp, "$.idUsuario")).longValue();

        String atualizacao = """
                {"nome":"Mantem Senha Editado","email":"mantemsenha@loja.com","senha":null,
                 "idsEmpresa":[%d]}
                """.formatted(idEmpresa);
        mvc.perform(put("/api/v1/usuarios/" + idUsuario).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(atualizacao))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("MANTEM SENHA EDITADO"));

        String login = """
                {"slug":"%s","email":"mantemsenha@loja.com","senha":"senhaoriginal1"}
                """.formatted(tenant.slug());
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk());
    }

    @Test
    void excluirUsuarioSemVinculoApagaDeVerdade() throws Exception {
        TenantNovo tenant = assinarNovoTenant("exclusao-simples");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());
        String usuario = """
                {"nome":"Usuario Sem Caixa","email":"semcaixa@loja.com","senha":"senha1234","idsEmpresa":[%d]}
                """.formatted(idEmpresa);
        String resp = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(usuario))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idUsuario = ((Number) JsonPath.read(resp, "$.idUsuario")).longValue();

        mvc.perform(delete("/api/v1/usuarios/" + idUsuario).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/usuarios/" + idUsuario).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirUsuarioComCaixaInativaEmVezDeExcluir() throws Exception {
        TenantNovo tenant = assinarNovoTenant("exclusao-com-caixa");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());
        String usuario = """
                {"nome":"Usuario Com Caixa","email":"comcaixa@loja.com","senha":"senha1234","idsEmpresa":[%d]}
                """.formatted(idEmpresa);
        String resp = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(usuario))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idUsuario = ((Number) JsonPath.read(resp, "$.idUsuario")).longValue();
        long idTenant = extrairIdTenant(tenant.token());

        criarCaixaParaUsuario(idTenant, idEmpresa, idUsuario);

        mvc.perform(delete("/api/v1/usuarios/" + idUsuario).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"));

        mvc.perform(get("/api/v1/usuarios/" + idUsuario).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    private void criarCaixaParaUsuario(long idTenant, long idEmpresa, long idUsuario) {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            st.executeUpdate("INSERT INTO caixa_mestre (id_tenant, id_empresa, id_usuario) VALUES ("
                    + idTenant + ", " + idEmpresa + ", " + idUsuario + ")");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
