package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Configuração da plataforma editável pelo backoffice (bloqueador nº 2, decisão do dono do
 * produto). O que estes testes garantem: <b>segredo entra e não sai</b>, campo em branco não
 * apaga credencial por engano, e só SUPER_ADMIN muda para onde vão os e-mails e qual conta
 * recebe o dinheiro.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConfiguracaoPlataformaTest {

    private static final String SENHA_STAFF = "senha-de-teste-123";

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
                .params("Staff " + papel, email, senhas.encode(SENHA_STAFF), papel)
                .update();
        String resp = mvc.perform(post("/api/admin/sessao").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA_STAFF)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private String corpo(String smtpSenha, String mpToken) {
        return """
                {"smtpHabilitado":true,"smtpHost":"smtp.vetor.com.br","smtpPorta":587,
                 "smtpUsuario":"envio@vetorsistemas.com.br",%s"smtpStarttls":true,
                 "smtpRemetenteEmail":"nao-responda@niner.com.br","smtpRemetenteNome":"Niner",
                 "backupHabilitado":true,"backupHora":"02:30:00","backupRetencaoDias":45%s}
                """.formatted(
                smtpSenha == null ? "" : "\"smtpSenha\":\"" + smtpSenha + "\",",
                mpToken == null ? "" : ",\"mpAccessToken\":\"" + mpToken + "\"");
    }

    @Test
    void superAdminGravaESegredoNuncaVolta() throws Exception {
        String token = token("config-admin@vetor.com.br", "SUPER_ADMIN");

        String resp = mvc.perform(put("/api/admin/configuracao").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo("senha-do-smtp", "TEST-token-mp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smtpHost").value("smtp.vetor.com.br"))
                .andExpect(jsonPath("$.smtpPorta").value(587))
                .andExpect(jsonPath("$.backupHora").value("02:30:00"))
                .andExpect(jsonPath("$.backupRetencaoDias").value(45))
                .andExpect(jsonPath("$.smtpSenhaDefinida").value(true))
                .andExpect(jsonPath("$.mpAccessTokenDefinido").value(true))
                .andReturn().getResponse().getContentAsString();

        // O corpo da resposta não pode conter o segredo em lugar nenhum.
        assertThat(resp).doesNotContain("senha-do-smtp").doesNotContain("TEST-token-mp");

        // E no banco os dois estão CIFRADOS — não é só a API que esconde.
        var guardado = jdbc.sql("""
                        SELECT coalesce(smtp_senha_cifrada, '') || '|' || coalesce(mp_access_token_cifrado, '')
                          FROM plataforma.configuracao_plataforma WHERE id = 1
                        """).query(String.class).single();
        assertThat(guardado).doesNotContain("senha-do-smtp").doesNotContain("TEST-token-mp");
        assertThat(guardado).isNotBlank();
    }

    @Test
    void campoEmBrancoMantemOSegredoJaGravado() throws Exception {
        String token = token("config-admin2@vetor.com.br", "SUPER_ADMIN");

        mvc.perform(put("/api/admin/configuracao").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo("senha-original", null)))
                .andExpect(status().isOk());
        String cifradoAntes = jdbc.sql(
                "SELECT smtp_senha_cifrada FROM plataforma.configuracao_plataforma WHERE id = 1")
                .query(String.class).single();

        // Salvar o formulário sem redigitar a senha não pode apagá-la.
        mvc.perform(put("/api/admin/configuracao").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo(null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smtpSenhaDefinida").value(true));

        assertThat(jdbc.sql("SELECT smtp_senha_cifrada FROM plataforma.configuracao_plataforma WHERE id = 1")
                .query(String.class).single()).isEqualTo(cifradoAntes);

        // Para apagar de verdade, o marcador explícito.
        mvc.perform(put("/api/admin/configuracao").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo("LIMPAR", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smtpSenhaDefinida").value(false));
    }

    @Test
    void suporteLeMasNaoAltera() throws Exception {
        String tokenSuporte = token("config-suporte@vetor.com.br", "SUPORTE");

        mvc.perform(get("/api/admin/configuracao").header("Authorization", "Bearer " + tokenSuporte))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/configuracao").header("Authorization", "Bearer " + tokenSuporte)
                        .contentType(APPLICATION_JSON).content(corpo("tentativa", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void semTokenNemComTokenDeLojista() throws Exception {
        mvc.perform(get("/api/admin/configuracao")).andExpect(status().isUnauthorized());

        String respTenant = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeLoja":"Loja Config","email":"dono@lojaconfig.com",
                                 "senha":"segredo123","nomeAdmin":"Dono"}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/admin/configuracao")
                        .header("Authorization", "Bearer " + JsonPath.read(respTenant, "$.token")))
                .andExpect(status().isUnauthorized());
    }
}
