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
import java.sql.Statement;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Revogação de sessão (V080, 2026-08-27).
 *
 * <p>Pedido do dono do produto depois da auditoria: <i>"se a senha for trocada, ou o usuário
 * desativado, tem que efetuar o logoff do respectivo usuário o mais breve possível — algo que não
 * deixe o sistema lento e não consuma muitos recursos do servidor"</i>.
 *
 * <p>O token vale 8 h, não tem {@code jti} e não há lista de revogação: até aqui, <b>demitir
 * alguém não o tirava do sistema</b>. A revogação entrou na consulta que o filtro de horário de
 * acesso já fazia a cada requisição — nenhuma consulta nova.
 *
 * <p>⚠️ O caso do <b>staff</b> não é detalhe: token de backoffice não tem {@code tid}, e a consulta
 * de sessão responderia "usuário não existe" para ele — sem o desvio, esta mudança derrubaria o
 * backoffice inteiro.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RevogacaoDeSessaoTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record Conta(String tokenAdmin, long idEmpresa) {
    }

    private Conta assinar(String sufixo) throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja Revoga %s","email":"dono%s@lojarevoga.com",
                         "senha":"segredo123","nomeAdmin":"Dono"}
                        """.formatted(sufixo, sufixo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        String eu = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return new Conta(token, ((Number) JsonPath.read(eu, "$.empresa.idEmpresa")).longValue());
    }

    private long criarOperador(Conta c, String email) throws Exception {
        String resp = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + c.tokenAdmin())
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"Operador","email":"%s","senha":"senha12345","idsEmpresa":[%d]}
                                """.formatted(email, c.idEmpresa())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(resp, "$.idUsuario")).longValue();
        PermissaoDeTeste.liberarTudo(mvc, c.tokenAdmin(), id);
        return id;
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
     * ⚠️ O {@code iat} do JWT tem precisão de <b>segundos</b>: dentro do mesmo segundo, o token
     * emitido e a revogação empatam e a comparação "emitido antes" é falsa. Empurrar a coluna um
     * segundo para trás faz o teste medir a regra, não o relógio — sem isto ele passaria ou
     * falharia conforme a velocidade da máquina, que é a pior espécie de teste.
     */
    private void envelhecerRevogacao(long idUsuario) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
                Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE usuario SET sessao_valida_desde = sessao_valida_desde + interval '2 seconds'"
                    + " WHERE id_usuario = " + idUsuario);
        }
    }

    @Test
    void trocarASenhaDerrubaASessaoAberta() throws Exception {
        Conta c = assinar("senha");
        long idOperador = criarOperador(c, "opsenha@lojarevoga.com");
        String tokenOperador = entrar("opsenha@lojarevoga.com", "senha12345");

        mvc.perform(get("/api/v1/produtos").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/usuarios/" + idOperador).header("Authorization", "Bearer " + c.tokenAdmin())
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"Operador","email":"opsenha@lojarevoga.com","senha":"outrasenha123",
                                 "idsEmpresa":[%d]}
                                """.formatted(c.idEmpresa())))
                .andExpect(status().isOk());
        envelhecerRevogacao(idOperador);

        mvc.perform(get("/api/v1/produtos").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void desativarDerrubaASessaoAberta() throws Exception {
        Conta c = assinar("desativa");
        long idOperador = criarOperador(c, "opdesativa@lojarevoga.com");
        String tokenOperador = entrar("opdesativa@lojarevoga.com", "senha12345");

        mvc.perform(put("/api/v1/usuarios/" + idOperador).header("Authorization", "Bearer " + c.tokenAdmin())
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"Operador","email":"opdesativa@lojarevoga.com","ativo":false,
                                 "idsEmpresa":[%d]}
                                """.formatted(c.idEmpresa())))
                .andExpect(status().isOk());
        envelhecerRevogacao(idOperador);

        mvc.perform(get("/api/v1/produtos").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isUnauthorized());
    }

    /** Usuário excluído: a linha some, e sessão sem usuário é sessão morta. */
    @Test
    void excluirDerrubaASessaoAberta() throws Exception {
        Conta c = assinar("exclui");
        long idOperador = criarOperador(c, "opexclui@lojarevoga.com");
        String tokenOperador = entrar("opexclui@lojarevoga.com", "senha12345");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/usuarios/" + idOperador)
                        .header("Authorization", "Bearer " + c.tokenAdmin()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/produtos").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isUnauthorized());
    }

    /** Mexer em outra coisa do cadastro não expulsa ninguém — revogar demais é tão ruim quanto de menos. */
    @Test
    void editarONomeNaoDerrubaASessao() throws Exception {
        Conta c = assinar("nome");
        long idOperador = criarOperador(c, "opnome@lojarevoga.com");
        String tokenOperador = entrar("opnome@lojarevoga.com", "senha12345");

        mvc.perform(put("/api/v1/usuarios/" + idOperador).header("Authorization", "Bearer " + c.tokenAdmin())
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"Operador Renomeado","email":"opnome@lojarevoga.com",
                                 "idsEmpresa":[%d]}
                                """.formatted(c.idEmpresa())))
                .andExpect(status().isOk());
        // ⚠️ Sem envelhecer: aqui o ponto é que NADA revogou. Empurrar a coluna à mão provaria
        // apenas que o mecanismo funciona, que é o que os outros três casos já provam.

        mvc.perform(get("/api/v1/produtos").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isOk());
    }
}
