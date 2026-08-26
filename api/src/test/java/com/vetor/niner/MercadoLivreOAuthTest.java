package com.vetor.niner;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.canais.CredenciaisCanal;
import com.vetor.niner.canais.CredenciaisCanalRepositorio;
import com.vetor.niner.comum.tenant.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth do Mercado Livre (bloco M1) — conectar a conta do lojista ao canal.
 *
 * <p>⚠️ <b>Contra WireMock, e não contra o ML.</b> O Mercado Livre não tem sandbox: os testes
 * reais rodam na produção deles, com usuários de teste que são no máximo 10 e expiram. Aqui se
 * exercita <b>o nosso lado</b> — o {@code state}, o isolamento e a gravação cifrada. A primeira
 * chamada real vai encontrar divergências no formato; é esperado, e é o precedente do XSD que
 * passou e da SEFAZ que recusou.
 *
 * <p>⭐ O teste que mais importa aqui é {@link #stateDeUmTenantNaoConectaCanalDeOutro()}: é a
 * falha de isolamento (P8) que o desenho inteiro do {@code state} existe para impedir.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MercadoLivreOAuthTest {


    /** A empresa em que o usuário entrou — o canal precisa dela desde a V067 (estoque é por empresa). */
    private static long idEmpresaDo(String token) {
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.substring(token.indexOf('.') + 1, token.lastIndexOf('.'))));
        return ((Number) com.jayway.jsonpath.JsonPath.read(payload, "$.eid")).longValue();
    }
    /** ⚠️ Estático e iniciado aqui: {@code @DynamicPropertySource} roda antes do contexto subir. */
    private static final WireMockServer ML = new WireMockServer(
            com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());

    static {
        ML.start();
    }

    @DynamicPropertySource
    static void apontarParaOMlFalso(DynamicPropertyRegistry registro) {
        registro.add("niner.canais.mercadolivre.api-url", ML::baseUrl);
        // ⚠️ Fictício de propósito: o `client_id` de verdade é público mas fica FORA do git
        // (docs/mercadolivre/api.md), e um teste não precisa dele para provar a montagem da URL.
        registro.add("niner.canais.mercadolivre.client-id", () -> "1111222233334444");
        registro.add("niner.canais.mercadolivre.client-secret", () -> "segredo-de-teste");
        registro.add("niner.canais.mercadolivre.redirect-uri",
                () -> "https://api.nainer.com.br/api/publico/canais/mercadolivre/retorno");
        registro.add("niner.canais.mercadolivre.auth-url", () -> "https://auth.mercadolivre.com.br");
        registro.add("niner.canais.mercadolivre.retorno-web", () -> "http://localhost:5173/canais");
    }

    @AfterAll
    static void derrubar() {
        ML.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CredenciaisCanalRepositorio credenciais;

    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void limparStubs() {
        ML.resetAll();
    }

    // ------------------------------------------------------------------------------- ferramentas

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja ML %s","email":"dono%s@lojaml.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static long idTenantDo(String token) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return ((Number) JsonPath.read(payload, "$.tid")).longValue();
    }

    /** Marketplace exige controle de estoque (§8.1) — sem isto nem o canal nasce. */
    private void ligarControleDeEstoque(String token) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("""
                        {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                         "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":false,
                         "cfgPermiteQtdDecimal":false,"cfgPermiteEstoqueNegativo":false,
                         "cfgDiasValidadeOrcamento":15,"cfgExigeNumeroVendaDevolucao":false,
                         "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,
                         "cfgConsisteValorContasPagar":false,
                         "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":false}
                        """))
                .andExpect(status().isOk());
    }

    private long criarCanal(String token, String nome) throws Exception {
        String resp = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/canais/MERCADO_LIVRE")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"%s\",\"percPreco\":0,\"idEmpresa\":%d}"
                                .formatted(nome, idEmpresaDo(token))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCanal")).longValue();
    }

    /** Pede a URL de consentimento e extrai o {@code state} dela. */
    private String obterState(String token, long idCanal) throws Exception {
        String resp = mvc.perform(get("/api/v1/canais/%d/mercadolivre/autorizar".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(resp, "$.url");
        String bruto = url.substring(url.indexOf("&state=") + "&state=".length());
        return URLDecoder.decode(bruto, StandardCharsets.UTF_8);
    }

    private void mlDevolveToken(String accessToken, String refreshToken, int expiraEm, String userId) {
        ML.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"access_token":"%s","token_type":"bearer","expires_in":%d,
                         "scope":"offline_access read write","user_id":%s,"refresh_token":"%s"}
                        """.formatted(accessToken, expiraEm, userId, refreshToken))));
    }

    private String statusDoCanal(String token, long idCanal) throws Exception {
        String resp = mvc.perform(get("/api/v1/canais/" + idCanal)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.status");
    }

    /** Lê o JSONB cru — precisa de transação, senão o RLS esconde tudo (tenant NULL). */
    private String credencialCrua(long idTenant, long idCanal) {
        var tx = new TransactionTemplate(txManager);
        return TenantContext.comTenant(idTenant, () -> tx.execute(s -> jdbc.sql("""
                        SELECT credenciais::text FROM canal
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """).params(idCanal).query(String.class).optional().orElse(null)));
    }

    // ------------------------------------------------------------------------- começar a conexão

    @Test
    void autorizarMontaAUrlDoConsentimentoComStateEClientId() throws Exception {
        String token = assinarNovoTenant("url");
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML URL");

        String resp = mvc.perform(get("/api/v1/canais/%d/mercadolivre/autorizar".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(resp, "$.url");

        // ⚠️ O consentimento mora em auth.mercadolivre.com.br — NÃO em api.mercadolibre.com.
        assertThat(url).startsWith("https://auth.mercadolivre.com.br/authorization?");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("client_id=1111222233334444");
        assertThat(url).contains("&state=");
        // A redirect_uri viaja escapada e tem de bater caractere por caractere com a do painel.
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fapi.nainer.com.br"
                + "%2Fapi%2Fpublico%2Fcanais%2Fmercadolivre%2Fretorno");
    }

    /** Guarda 1 da §8.1: sem controle de estoque não se conecta marketplace. */
    @Test
    void autorizarRecusaQuandoEstoqueNegativoEstaPermitido() throws Exception {
        String token = assinarNovoTenant("sem-controle-oauth");
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML SEM CONTROLE");

        // Religa o parâmetro pelo banco: pela tela o guarda 2 impediria — e o que se quer provar
        // aqui é o guarda 1, no caminho do OAuth.
        long idTenant = idTenantDo(token);
        var tx = new TransactionTemplate(txManager);
        TenantContext.comTenant(idTenant, () -> tx.executeWithoutResult(s -> jdbc.sql("""
                UPDATE cfg_geral SET cfg_permite_estoque_negativo = true
                 WHERE id_tenant = plataforma.tenant_atual()
                """).update()));

        mvc.perform(get("/api/v1/canais/%d/mercadolivre/autorizar".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void autorizarNaoEnxergaCanalDeOutroTenant() throws Exception {
        String tokenA = assinarNovoTenant("dono-a");
        String tokenB = assinarNovoTenant("dono-b");
        ligarControleDeEstoque(tokenA);
        ligarControleDeEstoque(tokenB);
        long canalDeB = criarCanal(tokenB, "ML DO B");

        // A pede autorização para o canal de B: não existe, do ponto de vista de A.
        mvc.perform(get("/api/v1/canais/%d/mercadolivre/autorizar".formatted(canalDeB))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------------------- voltar do ML

    @Test
    void retornoTrocaOCodigoPorTokenEGravaAConexao() throws Exception {
        String token = assinarNovoTenant("conecta");
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML CONECTA");
        long idTenant = idTenantDo(token);
        String state = obterState(token, idCanal);

        mlDevolveToken("APP_USR-token-de-acesso", "TG-refresh-1", 21600, "987654321");

        mvc.perform(get("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "TG-code-do-consentimento").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString("conectado=")));

        // O que o ML recebeu: o code, o client_secret e a MESMA redirect_uri do consentimento.
        ML.verify(postRequestedFor(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=TG-code-do-consentimento"))
                .withRequestBody(containing("client_secret=segredo-de-teste")));

        assertThat(statusDoCanal(token, idCanal)).isEqualTo("CONECTADO");

        // ⛔ O token tem de estar CIFRADO em repouso — o valor em claro não pode aparecer no banco.
        String cru = credencialCrua(idTenant, idCanal);
        assertThat(cru).doesNotContain("APP_USR-token-de-acesso");
        assertThat(cru).doesNotContain("TG-refresh-1");
        // `contaExterna` fica legível de propósito: o painel a lê por SQL e ela não é segredo.
        assertThat(cru).contains("987654321");

        // E decifra de volta corretamente.
        Optional<CredenciaisCanal> lida =
                TenantContext.comTenant(idTenant, () -> credenciais.carregar(idCanal));
        assertThat(lida).isPresent();
        assertThat(lida.get().accessToken()).isEqualTo("APP_USR-token-de-acesso");
        assertThat(lida.get().refreshToken()).isEqualTo("TG-refresh-1");
        assertThat(lida.get().contaExterna()).isEqualTo("987654321");
        // 6 horas — o motivo de a renovação automática ser obrigatória.
        assertThat(lida.get().expiraEm()).isAfter(Instant.now().plus(Duration.ofHours(5)));
    }

    /**
     * ⭐ O teste que justifica a V065 inteira.
     *
     * <p>O {@code state} carrega o par (tenant, canal). Usá-lo não pode, em hipótese alguma,
     * mexer no canal de outro lojista — é a falha de isolamento (P8) mais direta que a integração
     * tem, e a que aconteceria na porta de entrada.
     */
    @Test
    void stateDeUmTenantNaoConectaCanalDeOutro() throws Exception {
        String tokenA = assinarNovoTenant("iso-a");
        String tokenB = assinarNovoTenant("iso-b");
        ligarControleDeEstoque(tokenA);
        ligarControleDeEstoque(tokenB);
        long canalDeA = criarCanal(tokenA, "ML DO A");
        long canalDeB = criarCanal(tokenB, "ML DO B");

        String stateDeA = obterState(tokenA, canalDeA);
        mlDevolveToken("token-do-A", "refresh-do-A", 21600, "111");

        mvc.perform(get("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "code-do-A").param("state", stateDeA))
                .andExpect(status().isFound());

        assertThat(statusDoCanal(tokenA, canalDeA)).isEqualTo("CONECTADO");
        // ⛔ O canal de B não foi tocado.
        assertThat(statusDoCanal(tokenB, canalDeB)).isEqualTo("DESCONECTADO");
        assertThat(credencialCrua(idTenantDo(tokenB), canalDeB)).isNull();
    }

    /** ⚠️ Uso único: a segunda volta com o mesmo {@code state} não conecta nada. */
    @Test
    void stateNaoServeDuasVezes() throws Exception {
        String token = assinarNovoTenant("uso-unico");
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML UNICO");
        String state = obterState(token, idCanal);
        mlDevolveToken("token-1", "refresh-1", 21600, "222");

        mvc.perform(get("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "code-1").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("conectado=")));

        // Segunda vez com o MESMO state: recusa, e não chama o ML de novo.
        mvc.perform(get("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "code-2").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("erro=")));

        ML.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    void stateInventadoEhRecusadoSemFalarComOMl() throws Exception {
        mvc.perform(get("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "qualquer").param("state", "state-que-nunca-existiu"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("erro=")));

        ML.verify(0, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    /**
     * O lojista clicou "cancelar" na tela do ML.
     *
     * <p>⚠️ Não é falha nossa, e a mensagem tem de dizer isso — senão ele vai procurar problema em
     * rede, servidor e credencial, que é o defeito de {@code catch} genérico já visto no módulo
     * fiscal (2026-08-24).
     */
    @Test
    void lojistaQueRecusaOConsentimentoRecebeMensagemPropria() throws Exception {
        mvc.perform(get("/api/publico/canais/mercadolivre/retorno")
                        .param("error", "access_denied").param("state", "irrelevante"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString("Autoriza")));

        ML.verify(0, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    /** ML recusou a troca (código expirado/usado): não conecta, e o canal segue desconectado. */
    @Test
    void codigoRecusadoPeloMlNaoConectaOCanal() throws Exception {
        String token = assinarNovoTenant("code-ruim");
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML CODE RUIM");
        String state = obterState(token, idCanal);

        ML.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_grant\",\"message\":\"Code has expired\"}")));

        mvc.perform(get("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "code-vencido").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("erro=")));

        assertThat(statusDoCanal(token, idCanal)).isEqualTo("DESCONECTADO");
    }

    // ------------------------------------------------------------------------------- renovação

    /**
     * ⚠️ Resposta de renovação <b>sem</b> {@code refresh_token} novo mantém o antigo.
     *
     * <p>Gravar {@code null} ali apagaria a única chave de renovação e obrigaria o lojista a
     * reautorizar no dia seguinte, sem motivo aparente — exatamente o tipo de defeito que só
     * aparece 6 horas depois, longe de quem o escreveu.
     */
    @Test
    void renovacaoSemRefreshNovoPreservaORefreshAntigo() throws Exception {
        String token = assinarNovoTenant("renova");
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML RENOVA");
        long idTenant = idTenantDo(token);
        String state = obterState(token, idCanal);

        mlDevolveToken("token-velho", "refresh-que-fica", 21600, "333");
        mvc.perform(get("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "code-ok").param("state", state))
                .andExpect(status().isFound());

        // O ML responde a renovação sem refresh_token novo — comportamento permitido.
        ML.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("""
                        {"access_token":"token-novo","token_type":"bearer","expires_in":21600,
                         "scope":"offline_access read write","user_id":333}
                        """)));

        var atual = TenantContext.comTenant(idTenant, () -> credenciais.carregar(idCanal)).orElseThrow();
        boolean renovou = TenantContext.comTenant(idTenant, () -> oauthRenovar(atual));
        assertThat(renovou).isTrue();

        var depois = TenantContext.comTenant(idTenant, () -> credenciais.carregar(idCanal)).orElseThrow();
        assertThat(depois.accessToken()).isEqualTo("token-novo");
        assertThat(depois.refreshToken()).isEqualTo("refresh-que-fica");
    }

    @Autowired
    com.vetor.niner.integracao.mercadolivre.MercadoLivreOAuthService oauthService;

    private boolean oauthRenovar(CredenciaisCanal atual) {
        return oauthService.renovar(atual);
    }

    /**
     * Autorização revogada pelo lojista: marca {@code ERRO} e <b>preserva</b> a credencial.
     *
     * <p>Apagá-la jogaria fora a única pista de qual conta estava conectada — e {@code ERRO} já
     * basta para parar a sincronização.
     */
    @Test
    void renovacaoRecusadaEmDefinitivoMarcaErroSemApagarACredencial() throws Exception {
        String token = assinarNovoTenant("revogado");
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML REVOGADO");
        long idTenant = idTenantDo(token);
        String state = obterState(token, idCanal);

        mlDevolveToken("token-antes", "refresh-antes", 21600, "444");
        mvc.perform(get("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "code-ok").param("state", state))
                .andExpect(status().isFound());

        ML.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_grant\"}")));

        var atual = TenantContext.comTenant(idTenant, () -> credenciais.carregar(idCanal)).orElseThrow();
        assertThat(TenantContext.comTenant(idTenant, () -> oauthRenovar(atual))).isFalse();

        assertThat(statusDoCanal(token, idCanal)).isEqualTo("ERRO");
        assertThat(credencialCrua(idTenant, idCanal)).isNotNull();
    }

    /** Só ADMIN conecta canal — mesma regra do resto da tela de Canais. */
    @Test
    void operadorNaoConecta() throws Exception {
        String token = assinarNovoTenant("operador");
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML OPERADOR");

        String tokenOperador = tokenComPapel(token, "OPERADOR");
        mvc.perform(get("/api/v1/canais/%d/mercadolivre/autorizar".formatted(idCanal))
                        .header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden());
    }

    @Autowired
    com.vetor.niner.comum.seguranca.TokenService tokens;

    private String tokenComPapel(String tokenAdmin, String papel) {
        String payload = new String(Base64.getUrlDecoder().decode(tokenAdmin.split("\\.")[1]));
        long idTenant = ((Number) JsonPath.read(payload, "$.tid")).longValue();
        long idEmpresa = ((Number) JsonPath.read(payload, "$.eid")).longValue();
        long idUsuario = Long.parseLong(JsonPath.read(payload, "$.sub"));
        return tokens.emitir(idUsuario, idTenant, idEmpresa, JsonPath.read(payload, "$.email"),
                java.util.List.of(papel));
    }

    /** Contrato da tela: a listagem nunca devolve credencial, conectada ou não. */
    @Test
    void listagemNuncaDevolveCredencial() throws Exception {
        String token = assinarNovoTenant("sem-vazar");
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML SEM VAZAR");
        String state = obterState(token, idCanal);
        mlDevolveToken("token-secreto", "refresh-secreto", 21600, "555");
        mvc.perform(get("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "code-ok").param("state", state))
                .andExpect(status().isFound());

        mvc.perform(get("/api/v1/canais/" + idCanal).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONECTADO"))
                .andExpect(jsonPath("$.contaExterna").value("555"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.credenciais").doesNotExist());
    }
}
