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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fechamento da superfície de backoffice (2026-08-19) — era `permitAll` e expunha o gerenciador de
 * marketing, com <b>nome, e-mail e WhatsApp de leads</b>, a qualquer um na internet.
 *
 * <p>O que estes testes protegem, em ordem de importância: o backoffice não abre sem token; o
 * token do <b>lojista</b> não vale no backoffice; e o token do <b>staff</b> não vale no ERP. As
 * duas últimas são a separação de populações (R18/ADR-009) — se um dia alguém "simplificar" o
 * decoder para aceitar os dois `aud`, é aqui que quebra.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StaffAdminSegurancaTest {

    private static final String ROTA_PROTEGIDA = "/api/admin/marketing/funil";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder senhas;

    private String criarStaff(String email, String senha, String papel, boolean ativo) {
        jdbc.sql("""
                        INSERT INTO plataforma.staff (nome, email, senha_hash, papel, ativo)
                        VALUES (?, ?, ?, ?::plataforma.papel_staff, ?)
                        ON CONFLICT (lower(email)) DO NOTHING
                        """)
                .params("Staff " + papel, email, senhas.encode(senha), papel, ativo)
                .update();
        return email;
    }

    private String entrar(String email, String senha) throws Exception {
        String resp = mvc.perform(post("/api/admin/sessao").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, senha)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private String tokenDeLojista() throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeLoja":"Loja Staff Teste","email":"dono@lojastaff.com",
                                 "senha":"segredo123","nomeAdmin":"Dono"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void backofficeSemTokenEhRecusado() throws Exception {
        mvc.perform(get(ROTA_PROTEGIDA)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/marketing/leads")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/eu")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDeLojistaNaoAbreOBackoffice() throws Exception {
        String tokenTenant = tokenDeLojista();
        mvc.perform(get(ROTA_PROTEGIDA).header("Authorization", "Bearer " + tokenTenant))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDeStaffNaoAbreOErpDoLojista() throws Exception {
        criarStaff("suporte@vetor.com.br", "senha-de-teste-123", "SUPORTE", true);
        String tokenStaff = entrar("suporte@vetor.com.br", "senha-de-teste-123");

        mvc.perform(get("/api/v1/minha-conta").header("Authorization", "Bearer " + tokenStaff))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staffEntraEEnxergaOFunil() throws Exception {
        criarStaff("admin@vetor.com.br", "senha-de-teste-123", "SUPER_ADMIN", true);
        String token = entrar("admin@vetor.com.br", "senha-de-teste-123");

        mvc.perform(get("/api/admin/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papel").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.email").value("admin@vetor.com.br"));

        mvc.perform(get(ROTA_PROTEGIDA).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitas").isNumber());
    }

    @Test
    void staffInativoOuSenhaErradaNaoEntra() throws Exception {
        criarStaff("desligado@vetor.com.br", "senha-de-teste-123", "FINANCEIRO", false);

        mvc.perform(post("/api/admin/sessao").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"desligado@vetor.com.br\",\"senha\":\"senha-de-teste-123\"}"))
                .andExpect(status().isUnauthorized());

        criarStaff("ativo@vetor.com.br", "senha-de-teste-123", "FINANCEIRO", true);
        mvc.perform(post("/api/admin/sessao").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"ativo@vetor.com.br\",\"senha\":\"errada\"}"))
                .andExpect(status().isUnauthorized());

        // E-mail inexistente responde igual a senha errada — mensagem diferente entregaria quais
        // contas de staff existem.
        mvc.perform(post("/api/admin/sessao").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"ninguem@vetor.com.br\",\"senha\":\"qualquer-coisa\"}"))
                .andExpect(status().isUnauthorized());
    }
}
