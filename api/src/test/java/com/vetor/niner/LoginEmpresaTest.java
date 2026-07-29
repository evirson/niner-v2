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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login com escolha de empresa (docs/telas/usuario.md, {@code usuario_empresa}, 2026-07-28) —
 * quando o usuário tem acesso a mais de uma empresa, {@code POST /api/publico/login} devolve
 * a lista em vez do token; reenviando com {@code idEmpresa} escolhido, aí sim vem o token, já
 * com a empresa ativa gravada no claim {@code eid}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginEmpresaTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Login %s","email":"dono%s@lojalogin.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new TenantNovo(JsonPath.read(resp, "$.token"), JsonPath.read(resp, "$.slug"));
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        return ((Number) JsonPath.read(payload, "$.tid")).longValue();
    }

    /** Cria uma segunda empresa do mesmo tenant direto no banco — não há API de criar empresa. */
    private long criarSegundaEmpresa(long idTenant) {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, cfg_nome_etiqueta) VALUES ("
                            + idTenant + ", 2, 'FILIAL DOIS', '{sku}') RETURNING id_empresa")) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void loginComUmaEmpresaSoResolveDireto() throws Exception {
        TenantNovo tenant = assinarNovoTenant("uma-empresa");

        String login = """
                {"slug":"%s","email":"donouma-empresa@lojalogin.com","senha":"segredo123"}
                """.formatted(tenant.slug());
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.escolherEmpresa").value(false));
    }

    @Test
    void loginComDuasEmpresasPedeEscolhaSemToken() throws Exception {
        TenantNovo tenant = assinarNovoTenant("duas-empresas");
        long idTenant = extrairIdTenant(tenant.token());
        long idEmpresa2 = criarSegundaEmpresa(idTenant);

        String respEu = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long idUsuario = ((Number) JsonPath.read(respEu, "$.usuario.idUsuario")).longValue();
        long idEmpresa1 = ((Number) JsonPath.read(respEu, "$.empresa.idEmpresa")).longValue();

        String usuario = """
                {"nome":"Dono Da Loja","email":"donoduas-empresas@lojalogin.com","administrador":true,
                 "idsEmpresa":[%d,%d]}
                """.formatted(idEmpresa1, idEmpresa2);
        mvc.perform(put("/api/v1/usuarios/" + idUsuario).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(usuario))
                .andExpect(status().isOk());

        String login = """
                {"slug":"%s","email":"donoduas-empresas@lojalogin.com","senha":"segredo123"}
                """.formatted(tenant.slug());
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.escolherEmpresa").value(true))
                .andExpect(jsonPath("$.empresas.length()").value(2));

        String loginComEmpresa = """
                {"slug":"%s","email":"donoduas-empresas@lojalogin.com","senha":"segredo123","idEmpresa":%d}
                """.formatted(tenant.slug(), idEmpresa2);
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(loginComEmpresa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.escolherEmpresa").value(false));
    }

    @Test
    void loginComEmpresaForaDaListaPermitidaEhRejeitado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("empresa-invalida");
        long idTenant = extrairIdTenant(tenant.token());
        long idEmpresaDeOutraFilial = criarSegundaEmpresa(idTenant);
        // idEmpresaDeOutraFilial NÃO foi concedida ao usuário (usuario_empresa só tem a 1ª).

        String login = """
                {"slug":"%s","email":"donoempresa-invalida@lojalogin.com","senha":"segredo123","idEmpresa":%d}
                """.formatted(tenant.slug(), idEmpresaDeOutraFilial);
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isUnauthorized());
    }
}
