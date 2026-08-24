package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.fiscal.configuracao.CsrtService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSRT por UF (NT 2018.005) — o Nainer atende as 27 unidades da federação, então o código do
 * responsável técnico deixou de ser uma variável de ambiente única e virou cadastro por
 * UF × ambiente (2026-08-20).
 *
 * <p>O que estes testes prendem: o código de um estado <b>não</b> vaza para outro (era exatamente
 * o defeito do desenho anterior, que teria carimbado o CSRT do Paraná numa nota de São Paulo e
 * rendido um {@code cStat 974} com diagnóstico enganoso), o segredo entra e não sai pela API, e a
 * mensagem de "não configurado" diz a UF.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CsrtPorUfTest {

    private static final String SENHA_STAFF = "senha-de-teste-123";
    private static final String CSRT_SP = "CSRTDESAOPAULO0000000000000000000000";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder senhas;

    @Autowired
    CsrtService csrt;

    @BeforeEach
    void limpar() {
        jdbc.sql("DELETE FROM cfg_csrt_resptec").update();
    }

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

    private void cadastrarSp(String token) throws Exception {
        mvc.perform(put("/api/admin/fiscal/csrt/SP/2").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCsrt\":\"07\",\"csrt\":\"%s\",\"observacao\":\"emitido no portal\"}"
                                .formatted(CSRT_SP)))
                .andExpect(status().isOk());
    }

    /** O caso que motivou a mudança: cada estado com o seu, sem um contaminar o outro. */
    @Test
    void csrtDeUmEstadoNaoValeParaOutro() throws Exception {
        cadastrarSp(token("super-csrt@vetor.com.br", "SUPER_ADMIN"));

        assertThat(csrt.buscar("SP", 2)).isPresent().get()
                .satisfies(c -> {
                    assertThat(c.idCsrt()).isEqualTo("07");
                    assertThat(c.codigo()).isEqualTo(CSRT_SP);
                });
        // MG não tem linha e não é a UF do fallback do env: nada, e não o código de São Paulo.
        assertThat(csrt.buscar("MG", 2)).isEmpty();
    }

    /**
     * O par do {@code application.yml} continua valendo — mas só para a UF que o declara
     * ({@code niner.fiscal.resp-tec.uf}, PR nos testes). Sem essa amarra, o fallback seria um
     * curinga que assinaria nota de qualquer estado com o código de um só.
     */
    @Test
    void fallbackDoAmbienteSoValeParaAUfQueODeclara() {
        assertThat(csrt.buscar("PR", 2)).isPresent();
        assertThat(csrt.buscar("SP", 2)).isEmpty();
        assertThat(csrt.buscar("BA", 1)).isEmpty();
    }

    /** Ambiente faz parte da chave: homologação e produção são cadastros separados no portal. */
    @Test
    void ambienteFazParteDaChave() throws Exception {
        cadastrarSp(token("super-csrt@vetor.com.br", "SUPER_ADMIN"));
        assertThat(csrt.buscar("SP", 2)).isPresent();
        assertThat(csrt.buscar("SP", 1)).isEmpty();
    }

    /** Segredo entra e não sai — nem para SUPER_ADMIN, nem na listagem. */
    @Test
    void oCodigoNuncaVoltaPelaApi() throws Exception {
        String token = token("super-csrt@vetor.com.br", "SUPER_ADMIN");
        cadastrarSp(token);

        String corpo = mvc.perform(get("/api/admin/fiscal/csrt").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uf").value("SP"))
                .andExpect(jsonPath("$[0].idCsrt").value("07"))
                .andExpect(jsonPath("$[0].definido").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain(CSRT_SP);

        // E no banco ele está cifrado, não em claro: um dump não entrega o código.
        String gravado = jdbc.sql("SELECT csrt_cifrado FROM cfg_csrt_resptec WHERE uf = 'SP' AND ambiente = 2")
                .query(String.class).single();
        assertThat(gravado).isNotBlank().doesNotContain(CSRT_SP);
    }

    /** Em branco mantém o código gravado — mesma convenção da senha de SMTP. */
    @Test
    void salvarEmBrancoMantemOCodigoGravado() throws Exception {
        String token = token("super-csrt@vetor.com.br", "SUPER_ADMIN");
        cadastrarSp(token);

        mvc.perform(put("/api/admin/fiscal/csrt/SP/2").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCsrt\":\"07\",\"csrt\":\"\",\"observacao\":\"só corrigindo a nota\"}"))
                .andExpect(status().isOk());

        assertThat(csrt.buscar("SP", 2)).isPresent().get()
                .satisfies(c -> assertThat(c.codigo()).isEqualTo(CSRT_SP));
    }

    @Test
    void suporteLeMasNaoGrava() throws Exception {
        String suporte = token("suporte-csrt@vetor.com.br", "SUPORTE");
        mvc.perform(get("/api/admin/fiscal/csrt").header("Authorization", "Bearer " + suporte))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/fiscal/csrt/SP/2").header("Authorization", "Bearer " + suporte)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCsrt\":\"07\",\"csrt\":\"%s\"}".formatted(CSRT_SP)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ufInvalidaEhRecusada() throws Exception {
        String token = token("super-csrt@vetor.com.br", "SUPER_ADMIN");
        mvc.perform(put("/api/admin/fiscal/csrt/XX/2").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCsrt\":\"07\",\"csrt\":\"%s\"}".formatted(CSRT_SP)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removerUfNaoCadastradaResponde404() throws Exception {
        String token = token("super-csrt@vetor.com.br", "SUPER_ADMIN");
        mvc.perform(delete("/api/admin/fiscal/csrt/SP/2").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /**
     * A exigência do par é dado da UF, não {@code if}: o PR cobra no modelo 55 e não cobra no 65 —
     * medido ao vivo em 2026-08-19, e é o que a V046 semeia.
     */
    @Test
    void exigenciaDoCsrtVemDaTabelaDaUf() {
        assertThat(csrt.exigeCsrt("PR", 55, 2)).isTrue();
        assertThat(csrt.exigeCsrt("PR", 65, 2)).isFalse();
    }

    /** F11: a mensagem tem de dizer a UF — é ela que manda no cadastro do responsável técnico. */
    @Test
    void mensagemDeCsrtFaltandoDizAUf() {
        assertThat(CsrtService.mensagemFaltando("sp", 55))
                .contains("SP").contains("55").contains("CSRT");
    }

    /**
     * O campo do CSRT recusa um código curto demais — o caso real de 2026-08-24.
     *
     * <p>O dono do produto recebeu o credenciamento da casa de software com um <b>número de
     * credenciamento</b> (5 dígitos) e um <b>CSRT</b> (36 caracteres), e cadastrou o primeiro no
     * campo do segundo. O campo só tinha {@code @Size(max)}, então aceitou — e o defeito só
     * apareceu na SEFAZ, como <b>cStat 974</b>, uma mensagem que fala em <b>CNPJ</b> e manda o
     * diagnóstico para o lado errado. Piso de tamanho custa uma anotação e economiza essa viagem.
     */
    @Test
    void recusaCodigoCurtoDemaisComoCsrt() throws Exception {
        String token = token("super-csrt@vetor.com.br", "SUPER_ADMIN");

        mvc.perform(put("/api/admin/fiscal/csrt/SP/2").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        // 79413 — o número do credenciamento, não o CSRT.
                        .content("{\"idCsrt\":\"07\",\"csrt\":\"79413\"}"))
                .andExpect(status().isBadRequest());
    }
}
