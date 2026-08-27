package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.vetor.niner.comum.email.EmailService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login em duas etapas por e-mail (V079, 2026-08-27).
 *
 * <p>⚠️ <b>O teste que mais importa aqui é o do contador de tentativas.</b> Quatro dígitos são
 * 10.000 combinações: se o {@code UPDATE} que soma a tentativa errada for desfeito pelo rollback
 * da exceção que informa o erro, o teto de 5 tentativas continua escrito no código, continua
 * aparecendo na tela — e não segura nada. Nenhum outro teste desta suíte perceberia isso.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginEmDuasEtapasTest {

    private static final Pattern CODIGO_NO_EMAIL = Pattern.compile("letter-spacing:6px[^>]*>(\\d{4})<");

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    /** Sem SMTP em teste o envio falharia e viraria 503 — aqui o e-mail é capturado para ler o código. */
    @MockitoBean
    EmailService email;

    private record Conta(String token, long idTenant) {
    }

    private Conta assinar(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Duas Etapas %s","email":"dono%s@duasetapas.com",
                 "senha":"segredo123","nomeAdmin":"Dono"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Conta(JsonPath.read(resp, "$.token"),
                ((Number) JsonPath.read(resp, "$.idTenant")).longValue());
    }

    /** Liga a segunda etapa direto no banco — a tela de usuário já grava a coluna; aqui o alvo é o login. */
    private void exigirCodigo(long idTenant, String email) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
                Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE usuario SET exige_codigo_login = true WHERE id_tenant = " + idTenant
                    + " AND lower(email) = lower('" + email + "')");
        }
    }

    private String codigoEnviado() {
        ArgumentCaptor<String> corpo = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(email, org.mockito.Mockito.atLeastOnce())
                .enviar(anyString(), anyString(), corpo.capture());
        Matcher m = CODIGO_NO_EMAIL.matcher(corpo.getAllValues().get(corpo.getAllValues().size() - 1));
        assertTrue(m.find(), "o e-mail tem de conter o código de 4 dígitos");
        return m.group(1);
    }

    private String logar(String email, String senha) throws Exception {
        return mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","senha":"%s"}
                                """.formatted(email, senha)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String conferir(String desafio, String codigo) throws Exception {
        return mvc.perform(post("/api/publico/login/codigo").contentType(APPLICATION_JSON)
                        .content("""
                                {"desafio":"%s","codigo":"%s"}
                                """.formatted(desafio, codigo)))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void senhaCertaNaoBastaEOCodigoCompletaOLogin() throws Exception {
        when(email.enviar(anyString(), anyString(), anyString())).thenReturn(true);
        Conta conta = assinar("a");
        exigirCodigo(conta.idTenant(), "donoa@duasetapas.com");

        String etapa1 = logar("donoa@duasetapas.com", "segredo123");
        // ⚠️ A senha estava certa e mesmo assim não veio token: é isso que a segunda etapa é.
        assertNull(JsonPath.read(etapa1, "$.token"), "não pode vir token na primeira etapa");
        assertTrue((Boolean) JsonPath.read(etapa1, "$.exigeCodigo"));
        assertEquals("d***@duasetapas.com", JsonPath.read(etapa1, "$.emailMascarado"));
        String desafio = JsonPath.read(etapa1, "$.desafio");
        assertNotNull(desafio);

        String etapa2 = conferir(desafio, codigoEnviado());
        assertNotNull(JsonPath.read(etapa2, "$.token"));
    }

    @Test
    void codigoServeUmaVezSo() throws Exception {
        when(email.enviar(anyString(), anyString(), anyString())).thenReturn(true);
        Conta conta = assinar("b");
        exigirCodigo(conta.idTenant(), "donob@duasetapas.com");

        String desafio = JsonPath.read(logar("donob@duasetapas.com", "segredo123"), "$.desafio");
        String codigo = codigoEnviado();
        assertNotNull(JsonPath.read(conferir(desafio, codigo), "$.token"));

        // Reapresentar o mesmo código (e o mesmo desafio) não pode render um segundo token.
        mvc.perform(post("/api/publico/login/codigo").contentType(APPLICATION_JSON)
                        .content("""
                                {"desafio":"%s","codigo":"%s"}
                                """.formatted(desafio, codigo)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * ⭐ <b>O teste central.</b> Cinco erros gastam o desafio; o sexto é recusado mesmo que o código
     * esteja <b>certo</b>. Sem a contagem sobrevivendo a cada erro, 10.000 tentativas seriam
     * viáveis em minutos.
     */
    @Test
    void tentativasErradasSaoContadasEFechamAPorta() throws Exception {
        when(email.enviar(anyString(), anyString(), anyString())).thenReturn(true);
        Conta conta = assinar("c");
        exigirCodigo(conta.idTenant(), "donoc@duasetapas.com");

        String desafio = JsonPath.read(logar("donoc@duasetapas.com", "segredo123"), "$.desafio");
        String certo = codigoEnviado();
        String errado = certo.equals("0000") ? "1111" : "0000";

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/publico/login/codigo").contentType(APPLICATION_JSON)
                            .content("""
                                    {"desafio":"%s","codigo":"%s"}
                                    """.formatted(desafio, errado)))
                    .andExpect(status().isUnauthorized());
        }

        // Confere no banco, não só pelo status: se o contador tivesse voltado a zero pelo rollback,
        // o passo seguinte ainda passaria por outro motivo e o buraco ficaria aberto.
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT tentativas FROM plataforma.codigo_login WHERE id_desafio = '" + desafio + "'")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt(1), "cada erro tem de somar uma tentativa no banco");
        }

        mvc.perform(post("/api/publico/login/codigo").contentType(APPLICATION_JSON)
                        .content("""
                                {"desafio":"%s","codigo":"%s"}
                                """.formatted(desafio, certo)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void reenviarTrocaOCodigoEOAnteriorDeixaDeValer() throws Exception {
        when(email.enviar(anyString(), anyString(), anyString())).thenReturn(true);
        Conta conta = assinar("d");
        exigirCodigo(conta.idTenant(), "donod@duasetapas.com");

        String desafio = JsonPath.read(logar("donod@duasetapas.com", "segredo123"), "$.desafio");
        String primeiro = codigoEnviado();

        mvc.perform(post("/api/publico/login/codigo/reenviar").contentType(APPLICATION_JSON)
                        .content("""
                                {"desafio":"%s"}
                                """.formatted(desafio)))
                .andExpect(status().isNoContent());
        String segundo = codigoEnviado();

        if (!primeiro.equals(segundo)) {
            mvc.perform(post("/api/publico/login/codigo").contentType(APPLICATION_JSON)
                            .content("""
                                    {"desafio":"%s","codigo":"%s"}
                                    """.formatted(desafio, primeiro)))
                    .andExpect(status().isUnauthorized());
        }
        assertNotNull(JsonPath.read(conferir(desafio, segundo), "$.token"));
    }

    /** Desafio inventado não vira token — e o reenvio não conta se ele existe (204 sempre). */
    @Test
    void desafioInventadoNaoAbrePorta() throws Exception {
        mvc.perform(post("/api/publico/login/codigo").contentType(APPLICATION_JSON)
                        .content("""
                                {"desafio":"3f2504e0-4f89-11d3-9a0c-0305e82c3301","codigo":"1234"}
                                """))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/publico/login/codigo").contentType(APPLICATION_JSON)
                        .content("""
                                {"desafio":"nao-e-um-uuid","codigo":"1234"}
                                """))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/publico/login/codigo/reenviar").contentType(APPLICATION_JSON)
                        .content("""
                                {"desafio":"3f2504e0-4f89-11d3-9a0c-0305e82c3301"}
                                """))
                .andExpect(status().isNoContent());
    }

    /** Quem não ligou a opção continua entrando direto — a mudança não pode pegar todo mundo. */
    @Test
    void semAOpcaoLigadaOLoginContinuaDeUmPassoSo() throws Exception {
        assinar("e");
        String resp = logar("donoe@duasetapas.com", "segredo123");
        assertNotNull(JsonPath.read(resp, "$.token"));
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"donoe@duasetapas.com","senha":"segredo123"}
                                """))
                .andExpect(jsonPath("$.exigeCodigo").value(false));
    }
}
