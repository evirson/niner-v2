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
 * Lista e ficha de contas no backoffice (R17). Cobre também o ponto sensível: a ficha lê
 * {@code empresa}, que é tabela de <b>domínio com RLS</b> — sem o contexto de tenant estabelecido
 * na consulta, o staff veria zero empresa e a tela mentiria em silêncio.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TenantAdminTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder senhas;

    private String tokenStaff() throws Exception {
        String email = "contas-staff@vetor.com.br";
        jdbc.sql("""
                        INSERT INTO plataforma.staff (nome, email, senha_hash, papel)
                        VALUES ('Staff Contas', ?, ?, 'SUPORTE')
                        ON CONFLICT (lower(email)) DO NOTHING
                        """)
                .params(email, senhas.encode("senha-de-teste-123"))
                .update();
        String resp = mvc.perform(post("/api/admin/sessao").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"senha\":\"senha-de-teste-123\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private long criarTenantComFilial(String sufixo) throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeLoja":"Loja Contas %s","email":"dono%s@lojacontas.com",
                                 "senha":"segredo123","nomeAdmin":"Dono"}
                                """.formatted(sufixo, sufixo)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        mvc.perform(post("/api/v1/empresas").header("Authorization", "Bearer " + JsonPath.read(resp, "$.token"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"Filial da Loja %s\"}".formatted(sufixo)))
                .andExpect(status().isCreated());

        return ((Number) JsonPath.read(resp, "$.idTenant")).longValue();
    }

    @Test
    void staffListaContasComPlanoEUso() throws Exception {
        criarTenantComFilial("lista");
        String token = tokenStaff();

        mvc.perform(get("/api/admin/tenants?busca=Loja Contas lista").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].plano").value("Gratuito"))
                .andExpect(jsonPath("$.itens[0].limiteVendasMes").value(100))
                .andExpect(jsonPath("$.itens[0].vendasNoMes").value(0))
                .andExpect(jsonPath("$.itens[0].qtdEmpresas").value(2))
                .andExpect(jsonPath("$.itens[0].status").value("ATIVA"));
    }

    @Test
    void fichaTrazEmpresasMesmoComRlsNaTabelaDeDominio() throws Exception {
        long idTenant = criarTenantComFilial("ficha");
        String token = tokenStaff();

        mvc.perform(get("/api/admin/tenants/" + idTenant).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumo.nomeConta").value("Loja Contas ficha"))
                .andExpect(jsonPath("$.empresas.length()").value(2))
                .andExpect(jsonPath("$.empresas[1].razaoSocial").value("FILIAL DA LOJA FICHA"));
    }

    @Test
    void semTokenDeStaffNaoLista() throws Exception {
        mvc.perform(get("/api/admin/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/tenants/1")).andExpect(status().isUnauthorized());
    }
}
