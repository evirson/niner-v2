package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.email.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recuperação de senha (bloqueador nº 5). O e-mail é dublado para capturar o link — é o único
 * lugar onde o token existe em claro, por construção (o banco guarda só o hash).
 *
 * <p>Os casos cobrem as três decisões que sustentam a segurança do fluxo: a resposta **não**
 * revela se a conta existe, o token vale **uma vez só**, e pedir de novo **invalida o anterior**.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RecuperacaoSenhaTest {

    private static final Pattern LINK = Pattern.compile("redefinir-senha\\?token=([A-Za-z0-9_-]+)");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    EmailService email;

    private String criarLoja(String sufixo) throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeLoja":"Loja Senha %s","email":"dono%s@lojasenha.com",
                                 "senha":"senhaAntiga1","nomeAdmin":"Dono"}
                                """.formatted(sufixo, sufixo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.slug");
    }

    private void solicitar(String slug, String emailUsuario) throws Exception {
        mvc.perform(post("/api/publico/recuperar-senha").contentType(APPLICATION_JSON)
                        .content("{\"slug\":\"%s\",\"email\":\"%s\"}".formatted(slug, emailUsuario)))
                .andExpect(status().isNoContent());
    }

    private String capturarToken() {
        ArgumentCaptor<String> corpo = ArgumentCaptor.forClass(String.class);
        verify(email, atLeastOnce()).enviar(anyString(), anyString(), corpo.capture());
        Matcher m = LINK.matcher(corpo.getAllValues().getLast());
        assertThat(m.find()).as("o e-mail precisa conter o link de redefinição").isTrue();
        return m.group(1);
    }

    private void redefinir(String token, String novaSenha, int statusEsperado) throws Exception {
        mvc.perform(post("/api/publico/redefinir-senha").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"novaSenha\":\"%s\"}".formatted(token, novaSenha)))
                .andExpect(status().is(statusEsperado));
    }

    private void login(String slug, String emailUsuario, String senha, int statusEsperado) throws Exception {
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .content("{\"slug\":\"%s\",\"email\":\"%s\",\"senha\":\"%s\"}"
                                .formatted(slug, emailUsuario, senha)))
                .andExpect(status().is(statusEsperado));
    }

    @Test
    void fluxoCompletoTrocaASenhaEInvalidaAAntiga() throws Exception {
        when(email.enviar(anyString(), anyString(), anyString())).thenReturn(true);
        String slug = criarLoja("fluxo");
        String usuario = "donofluxo@lojasenha.com";

        solicitar(slug, usuario);
        redefinir(capturarToken(), "senhaNova2026", 204);

        login(slug, usuario, "senhaNova2026", 200);
        login(slug, usuario, "senhaAntiga1", 401);
    }

    @Test
    void naoRevelaSeAContaExiste() throws Exception {
        String slug = criarLoja("sigilo");

        // Loja existente + e-mail inexistente, e loja inexistente: os dois respondem 204…
        solicitar(slug, "nao-existe@lojasenha.com");
        solicitar("loja-que-nao-existe", "qualquer@email.com");

        // …e nenhum e-mail sai, então nem o tempo de resposta entrega a diferença.
        verify(email, never()).enviar(eq("nao-existe@lojasenha.com"), anyString(), anyString());
        verify(email, never()).enviar(eq("qualquer@email.com"), anyString(), anyString());
    }

    @Test
    void tokenValeUmaVezSo() throws Exception {
        when(email.enviar(anyString(), anyString(), anyString())).thenReturn(true);
        String slug = criarLoja("umavez");
        solicitar(slug, "donoumavez@lojasenha.com");
        String token = capturarToken();

        redefinir(token, "primeiraNova123", 204);
        redefinir(token, "segundaNova123", 400);
        login(slug, "donoumavez@lojasenha.com", "primeiraNova123", 200);
    }

    @Test
    void pedidoNovoInvalidaOAnterior() throws Exception {
        when(email.enviar(anyString(), anyString(), anyString())).thenReturn(true);
        String slug = criarLoja("doispedidos");
        String usuario = "donodoispedidos@lojasenha.com";

        solicitar(slug, usuario);
        String tokenAntigo = capturarToken();
        solicitar(slug, usuario);
        String tokenNovo = capturarToken();

        assertThat(tokenNovo).isNotEqualTo(tokenAntigo);
        redefinir(tokenAntigo, "naoDeveValer1", 400);   // link velho esquecido na caixa de entrada
        redefinir(tokenNovo, "valeSim123456", 204);
        login(slug, usuario, "valeSim123456", 200);
    }

    @Test
    void tokenInventadoNaoRedefine() throws Exception {
        redefinir("token-que-nunca-existiu", "qualquerCoisa1", 400);
        verify(email, never()).enviar(anyString(), anyString(), any());
    }
}
