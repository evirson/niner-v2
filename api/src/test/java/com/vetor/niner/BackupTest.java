package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Backup agendado (bloqueador nº 3), com agenda editável no backoffice.
 *
 * <p>O caso que mais importa aqui é a <b>recusa de rodar como {@code niner_app}</b>: essa role
 * está sob {@code FORCE ROW LEVEL SECURITY} e, sem contexto de tenant, o {@code pg_dump} sairia
 * com <b>zero linha</b> das tabelas dos lojistas — um arquivo que parece backup, tem tamanho, e
 * só se revela vazio no dia do restore.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BackupTest {

    @DynamicPropertySource
    static void credencialErrada(DynamicPropertyRegistry registro) {
        registro.add("niner.backup.usuario", () -> "niner_app");
        registro.add("niner.backup.senha", () -> "dev_app");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder senhas;

    private String token(String email, String papel) throws Exception {
        jdbc.sql("""
                        INSERT INTO plataforma.staff (nome, email, senha_hash, papel)
                        VALUES (?, ?, ?, ?::plataforma.papel_staff)
                        ON CONFLICT (lower(email)) DO NOTHING
                        """)
                .params("Staff " + papel, email, senhas.encode("senha-de-teste-123"), papel)
                .update();
        String resp = mvc.perform(post("/api/admin/sessao").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"senha\":\"senha-de-teste-123\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void recusaRodarComOUsuarioDaAplicacaoEDeixaOMotivoVisivel() throws Exception {
        String token = token("backup-admin@vetor.com.br", "SUPER_ADMIN");

        mvc.perform(post("/api/admin/backup/executar").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value(org.hamcrest.Matchers.containsString("ERRO")))
                .andExpect(jsonPath("$.resultado").value(org.hamcrest.Matchers.containsString("RLS")));

        // O motivo fica na tela de configuração — backup que falha em silêncio é o pior tipo.
        mvc.perform(get("/api/admin/configuracao").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backupUltimoStatus").value("ERRO"))
                .andExpect(jsonPath("$.backupUltimoDetalhe").value(org.hamcrest.Matchers.containsString("niner_app")));
    }

    @Test
    void suporteVeSituacaoMasNaoDisparaBackup() throws Exception {
        String tokenSuporte = token("backup-suporte@vetor.com.br", "SUPORTE");

        mvc.perform(get("/api/admin/backup").header("Authorization", "Bearer " + tokenSuporte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.habilitado").exists());
        mvc.perform(post("/api/admin/backup/executar").header("Authorization", "Bearer " + tokenSuporte))
                .andExpect(status().isForbidden());
    }

    @Test
    void backupExigeAutenticacaoDeStaff() throws Exception {
        mvc.perform(get("/api/admin/backup")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/backup/executar")).andExpect(status().isUnauthorized());
    }
}
