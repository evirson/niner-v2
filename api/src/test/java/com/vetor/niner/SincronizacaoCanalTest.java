package com.vetor.niner;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.integracao.SincronizacaoEstoqueManipulador;
import com.vetor.niner.integracao.outbox.OutboxProcessador;
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

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sincronização de estoque e preço com o canal (M3, R3) — o bloco que transforma a integração em
 * produto.
 *
 * <p>⭐ O que se prova aqui é a <b>corrente inteira</b>: mexer no estoque pela rotina de negócio
 * (não por SQL) → o gatilho da V067 enfileira no outbox na mesma transação → o worker despacha →
 * o adapter manda o {@code PUT} certo ao Mercado Livre. Testar só as pontas deixaria passar
 * exatamente o que estava quebrado antes deste bloco: o meio, que não existia.
 *
 * <p>⚠️ Contra WireMock. Nenhuma chamada real ao ML foi feita até hoje.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SincronizacaoCanalTest {

    private static final WireMockServer ML = new WireMockServer(
            com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());

    static {
        ML.start();
    }

    @DynamicPropertySource
    static void apontarParaOMlFalso(DynamicPropertyRegistry registro) {
        registro.add("niner.canais.mercadolivre.api-url", ML::baseUrl);
        registro.add("niner.canais.mercadolivre.client-id", () -> "1111222233334444");
        registro.add("niner.canais.mercadolivre.client-secret", () -> "segredo-de-teste");
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
    OutboxProcessador processador;

    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void limpar() {
        ML.resetAll();
    }

    // ------------------------------------------------------------------------------ ferramentas

    private static long idTenantDo(String token) {
        return ((Number) JsonPath.read(payloadDo(token), "$.tid")).longValue();
    }

    private static long idEmpresaDo(String token) {
        return ((Number) JsonPath.read(payloadDo(token), "$.eid")).longValue();
    }

    private static String payloadDo(String token) {
        return new String(java.util.Base64.getUrlDecoder()
                .decode(token.substring(token.indexOf('.') + 1, token.lastIndexOf('.'))));
    }

    private String assinarNovoTenant(String sufixo) throws Exception {
        String resp = mvc.perform(post2("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja Sync %s","email":"dono%s@lojasync.com",
                         "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                        """.formatted(sufixo, sufixo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post2(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder get2(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
    }

    private void ligarControleDeEstoque(String token) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
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

    private long criarCanalConectado(String token, String percPreco) throws Exception {
        String resp = mvc.perform(post2("/api/v1/canais/MERCADO_LIVRE")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"ML SYNC\",\"percPreco\":%s,\"idEmpresa\":%d}"
                                .formatted(percPreco, idEmpresaDo(token))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idCanal = ((Number) JsonPath.read(resp, "$.idCanal")).longValue();

        String urlResp = mvc.perform(get2("/api/v1/canais/%d/mercadolivre/autorizar".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(urlResp, "$.url");
        String state = java.net.URLDecoder.decode(
                url.substring(url.indexOf("&state=") + 7), java.nio.charset.StandardCharsets.UTF_8);

        ML.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"access_token":"tok","token_type":"bearer","expires_in":21600,
                         "user_id":777,"refresh_token":"ref"}
                        """)));
        mvc.perform(get2("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "c").param("state", state))
                .andExpect(status().isFound());
        return idCanal;
    }

    private long criarProduto(String token, String descricao, String precoVenda) throws Exception {
        String resp = mvc.perform(post2("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100",
                                 "precoVenda":"%s","ativo":true}
                                """.formatted(descricao, precoVenda)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarVariacao(String token, long idProduto) throws Exception {
        String resp = mvc.perform(post2("/api/v1/produtos/" + idProduto + "/variacoes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVariacao")).longValue();
    }

    private void vincular(String token, long idCanal, String idExterno, long idVariacao) throws Exception {
        mvc.perform(post2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"idExterno\":\"%s\",\"idVariacao\":%d}".formatted(idExterno, idVariacao)))
                .andExpect(status().isCreated());
    }

    /** O ML responde a leitura do item (o adapter lê antes de escrever) e aceita o PUT. */
    private void mlAceitaItem(String idExterno, String corpoDoItem) {
        ML.stubFor(get(urlPathEqualTo("/items/" + idExterno)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(corpoDoItem)));
        ML.stubFor(put(urlMatching("/items/" + idExterno + ".*")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")));
    }

    /** Mexe no estoque pela rotina real (`produto_movimento_detalhe`), não por UPDATE direto. */
    private void lancarEstoque(long idTenant, long idEmpresa, long idVariacao, String quantidade) {
        var tx = new TransactionTemplate(txManager);
        TenantContext.comTenant(idTenant, () -> tx.executeWithoutResult(s -> {
            Long idMestre = jdbc.sql("""
                            INSERT INTO produto_movimento_mestre
                                   (id_tenant, id_empresa, tipo_movimento, data_movimento)
                            VALUES (plataforma.tenant_atual(), ?, 'COMPRA', now())
                            RETURNING id_movimento
                            """)
                    .params(idEmpresa).query(Long.class).single();
            // 'C' = crédito: entrada de mercadoria. É a trigger `fn_atualiza_estoque_movimento`
            // que soma em `produto_estoque` — e é dela que o gatilho da V067 pega carona.
            jdbc.sql("""
                            INSERT INTO produto_movimento_detalhe
                                   (id_tenant, id_empresa, id_movimento, id_variacao,
                                    credito_debito, qtd_produto, preco_custo, preco_venda)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'C', CAST(? AS numeric), 10, 50)
                            """)
                    .params(idEmpresa, idMestre, idVariacao, quantidade).update();
        }));
    }

    private void despachar(long idTenant) {
        TenantContext.comTenant(idTenant, () -> processador.processarLoteDoTenantCorrente());
    }

    private int eventosPendentes(long idTenant) {
        var tx = new TransactionTemplate(txManager);
        return TenantContext.comTenant(idTenant, () -> tx.execute(s -> jdbc.sql("""
                        SELECT count(*) FROM outbox_evento
                         WHERE id_tenant = plataforma.tenant_atual() AND status = 'PENDENTE'
                        """).query(Integer.class).single()));
    }

    private String statusSync(long idTenant, long idCanal) {
        var tx = new TransactionTemplate(txManager);
        return TenantContext.comTenant(idTenant, () -> tx.execute(s -> jdbc.sql("""
                        SELECT status_sync::text FROM anuncio
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                         ORDER BY id_anuncio LIMIT 1
                        """).params(idCanal).query(String.class).single()));
    }

    private com.fasterxml.jackson.databind.JsonNode corpoDoPut(String idExterno) throws Exception {
        var enviados = ML.findAll(putRequestedFor(urlMatching("/items/" + idExterno + ".*")));
        assertThat(enviados).as("nenhum PUT chegou em /items/" + idExterno).isNotEmpty();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(enviados.getLast().getBodyAsString());
    }

    // ------------------------------------------------------------------------- a corrente toda

    /**
     * ⭐ O teste que justifica o M3: mexer no estoque pela rotina de negócio faz o saldo chegar ao
     * anúncio, sozinho.
     */
    @Test
    void movimentoDeEstoquePublicaOSaldoNoAnuncio() throws Exception {
        String token = assinarNovoTenant("corrente");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");
        long idVariacao = criarVariacao(token, criarProduto(token, "PRODUTO SYNC", "50.00"));

        mlAceitaItem("MLB100", "{\"id\":\"MLB100\",\"available_quantity\":0,\"variations\":[]}");
        vincular(token, idCanal, "MLB100", idVariacao);

        // O vínculo já enfileira a primeira publicação — senão o anúncio ficaria com o saldo
        // errado do ML até a próxima venda.
        assertThat(eventosPendentes(idTenant)).isPositive();

        // ⚠️ DRENA a fila antes de mexer no estoque. Sem isto o teste passaria mesmo com o gatilho
        // da V067 desligado: o evento do vínculo sozinho já produziria um PUT com o saldo atual,
        // e o "sucesso" não provaria nada sobre o gatilho — que é justamente o que este teste
        // existe para provar (é a armadilha de `feedback_teste_de_guard_passa_pelo_motivo_errado`).
        despachar(idTenant);
        assertThat(eventosPendentes(idTenant)).isZero();
        ML.resetRequests();

        lancarEstoque(idTenant, idEmpresa, idVariacao, "7");

        // ⭐ A partir daqui, qualquer evento na fila só pode ter vindo do gatilho do banco.
        assertThat(eventosPendentes(idTenant))
                .as("o gatilho da V067 não enfileirou o movimento de estoque")
                .isPositive();

        despachar(idTenant);

        assertThat(corpoDoPut("MLB100").path("available_quantity").asInt()).isEqualTo(7);
        assertThat(statusSync(idTenant, idCanal)).isEqualTo("OK");
        assertThat(eventosPendentes(idTenant)).isZero();
    }

    /**
     * ⛔ Fracionário vira inteiro para BAIXO.
     *
     * <p>O produto aceita quantidade decimal ({@code numeric(14,3)}) e o ML só entende inteiro.
     * 2,7 vira <b>2</b>: arredondar para cima prometeria uma peça que não existe, que é exatamente
     * o overselling que o P1 proíbe.
     */
    @Test
    void saldoFracionarioEArredondadoParaBaixo() throws Exception {
        String token = assinarNovoTenant("fracao");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");
        long idVariacao = criarVariacao(token, criarProduto(token, "PRODUTO FRACAO", "50.00"));

        mlAceitaItem("MLB101", "{\"id\":\"MLB101\",\"available_quantity\":0,\"variations\":[]}");
        vincular(token, idCanal, "MLB101", idVariacao);
        lancarEstoque(idTenant, idEmpresa, idVariacao, "2.7");
        despachar(idTenant);

        assertThat(corpoDoPut("MLB101").path("available_quantity").asInt()).isEqualTo(2);
    }

    /** Estoque negativo (permitido no ERP) vira zero: "−3 disponíveis" não existe num anúncio. */
    @Test
    void saldoNegativoViraZero() {
        assertThat(SincronizacaoEstoqueManipulador.paraInteiroPublicavel(new BigDecimal("-3"))).isZero();
        assertThat(SincronizacaoEstoqueManipulador.paraInteiroPublicavel(null)).isZero();
        assertThat(SincronizacaoEstoqueManipulador.paraInteiroPublicavel(new BigDecimal("0.999"))).isZero();
    }

    /**
     * ⛔ A armadilha das variações, ponta a ponta: o {@code PUT} leva <b>todas</b> as variações do
     * anúncio, não só a que mudou — omitir apaga as demais no anúncio do lojista.
     */
    @Test
    void putLevaTodasAsVariacoesDoAnuncio() throws Exception {
        String token = assinarNovoTenant("variacoes-put");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");
        long idProduto = criarProduto(token, "CAMISETA SYNC", "50.00");
        long var1 = criarVariacao(token, idProduto);

        mlAceitaItem("MLB102", """
                {"id":"MLB102","variations":[
                   {"id":"V1","available_quantity":0},
                   {"id":"V2","available_quantity":9}]}
                """);

        mvc.perform(post2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"idExterno\":\"MLB102\",\"idExternoVariacao\":\"V1\",\"idVariacao\":%d}"
                                .formatted(var1)))
                .andExpect(status().isCreated());

        lancarEstoque(idTenant, idEmpresa, var1, "4");
        despachar(idTenant);

        var variacoes = corpoDoPut("MLB102").path("variations");
        assertThat(variacoes).hasSize(2);
        assertThat(variacoes.get(0).path("id").asText()).isEqualTo("V1");
        assertThat(variacoes.get(0).path("available_quantity").asInt()).isEqualTo(4);
        // ⛔ V2 volta com o valor que já tinha. Omiti-la a APAGARIA no anúncio do lojista.
        assertThat(variacoes.get(1).path("id").asText()).isEqualTo("V2");
        assertThat(variacoes.get(1).path("available_quantity").asInt()).isEqualTo(9);
    }

    /**
     * ⭐ Loja sem canal conectado não paga nada: mexer no estoque não enfileira evento nenhum.
     *
     * <p>A loja típica deste ERP não vende em marketplace. Enfileirar sempre encheria o outbox de
     * eventos que ninguém consome — e o painel de saúde passaria a mostrar fila num dia normal.
     */
    @Test
    void lojaSemCanalNaoEnfileiraNada() throws Exception {
        String token = assinarNovoTenant("sem-canal");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idVariacao = criarVariacao(token, criarProduto(token, "PRODUTO SEM CANAL", "50.00"));

        lancarEstoque(idTenant, idEmpresa, idVariacao, "10");

        assertThat(eventosPendentes(idTenant)).isZero();
    }

    // ------------------------------------------------------------------------------------ preço

    /** Reajustar o preço da loja republica o preço derivado do canal. */
    @Test
    void reajusteDaLojaRepublicaOPrecoDoCanal() throws Exception {
        String token = assinarNovoTenant("reajuste");
        long idTenant = idTenantDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "20.00");
        long idProduto = criarProduto(token, "PRODUTO REAJUSTE", "50.00");
        long idVariacao = criarVariacao(token, idProduto);

        mlAceitaItem("MLB103", "{\"id\":\"MLB103\",\"available_quantity\":0,\"variations\":[]}");
        vincular(token, idCanal, "MLB103", idVariacao);
        despachar(idTenant);
        ML.resetRequests();

        // Reajusta a loja de 50 para 80. Com +20% do canal, o anúncio tem de ir a 96,00.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/produtos/" + idProduto).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"PRODUTO REAJUSTE","precoCusto":"10.00",
                                 "percentualVenda":"100","precoVenda":"80.00","ativo":true}
                                """))
                .andExpect(status().isOk());

        despachar(idTenant);

        assertThat(corpoDoPut("MLB103").path("price").decimalValue())
                .isEqualByComparingTo(new BigDecimal("96.00"));
    }

    /**
     * ⛔ Preço DIGITADO pelo lojista não é tocado pelo reajuste da loja.
     *
     * <p>Sem esta marca, o dia do reajuste geral seria o dia em que todo preço ajustado à mão no
     * marketplace some <b>em silêncio</b>.
     */
    @Test
    void precoManualNaoAcompanhaOReajusteDaLoja() throws Exception {
        String token = assinarNovoTenant("manual");
        long idTenant = idTenantDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");
        long idProduto = criarProduto(token, "PRODUTO MANUAL", "50.00");
        long idVariacao = criarVariacao(token, idProduto);

        mlAceitaItem("MLB104", "{\"id\":\"MLB104\",\"available_quantity\":0,\"variations\":[]}");
        vincular(token, idCanal, "MLB104", idVariacao);
        despachar(idTenant);

        // O lojista digitou o preço do anúncio (a tela para isso ainda não existe — marca direta).
        var tx = new TransactionTemplate(txManager);
        TenantContext.comTenant(idTenant, () -> tx.executeWithoutResult(s -> jdbc.sql("""
                UPDATE anuncio SET preco_manual = true, preco = 199.00
                 WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                """).params(idCanal).update()));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/produtos/" + idProduto).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"PRODUTO MANUAL","precoCusto":"10.00",
                                 "percentualVenda":"100","precoVenda":"80.00","ativo":true}
                                """))
                .andExpect(status().isOk());

        // ⛔ O gatilho da V067 nem enfileira: não há anúncio derivado neste produto.
        assertThat(eventosPendentes(idTenant)).isZero();

        BigDecimal preco = TenantContext.comTenant(idTenant, () -> tx.execute(s -> jdbc.sql("""
                        SELECT preco FROM anuncio
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """).params(idCanal).query(BigDecimal.class).single()));
        assertThat(preco).isEqualByComparingTo(new BigDecimal("199.00"));
    }

    // ------------------------------------------------------------------------------- isolamento

    /**
     * P8: o evento de um tenant não publica no canal de outro.
     *
     * <p>O worker entra em {@code TenantContext} por tenant; sem isso a consulta leria zero linha
     * em silêncio — ou, pior, a linha errada.
     */
    @Test
    void eventoDeUmTenantNaoPublicaNoCanalDeOutro() throws Exception {
        String tokenA = assinarNovoTenant("sync-iso-a");
        String tokenB = assinarNovoTenant("sync-iso-b");
        ligarControleDeEstoque(tokenA);
        ligarControleDeEstoque(tokenB);
        long canalA = criarCanalConectado(tokenA, "0");
        criarCanalConectado(tokenB, "0");

        long varA = criarVariacao(tokenA, criarProduto(tokenA, "PRODUTO DE A", "50.00"));
        mlAceitaItem("MLB105", "{\"id\":\"MLB105\",\"available_quantity\":0,\"variations\":[]}");
        vincular(tokenA, canalA, "MLB105", varA);

        lancarEstoque(idTenantDo(tokenA), idEmpresaDo(tokenA), varA, "5");

        // Despachar o tenant B não pode publicar nada: o evento é de A.
        despachar(idTenantDo(tokenB));
        assertThat(ML.findAll(putRequestedFor(urlMatching("/items/MLB105.*")))).isEmpty();

        despachar(idTenantDo(tokenA));
        assertThat(corpoDoPut("MLB105").path("available_quantity").asInt()).isEqualTo(5);
    }

    /** Falha transitória do canal não perde o evento: ele volta para a fila. */
    @Test
    void canalForaDoArDeixaOEventoNaFila() throws Exception {
        String token = assinarNovoTenant("transitorio");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");
        long idVariacao = criarVariacao(token, criarProduto(token, "PRODUTO ML FORA", "50.00"));

        mlAceitaItem("MLB106", "{\"id\":\"MLB106\",\"available_quantity\":0,\"variations\":[]}");
        vincular(token, idCanal, "MLB106", idVariacao);
        lancarEstoque(idTenant, idEmpresa, idVariacao, "3");

        // O ML devolve 503 no PUT — transitório.
        ML.stubFor(put(urlMatching("/items/MLB106.*")).willReturn(aResponse().withStatus(503)));
        despachar(idTenant);

        assertThat(statusSync(idTenant, idCanal)).isEqualTo("ERRO");
        var tx = new TransactionTemplate(txManager);
        List<String> status = TenantContext.comTenant(idTenant, () -> tx.execute(s -> jdbc.sql("""
                        SELECT status::text FROM outbox_evento
                         WHERE id_tenant = plataforma.tenant_atual()
                        """).query(String.class).list()));
        // ⛔ Nenhum evento vira PROCESSADO: o que falhou por motivo transitório espera a próxima
        // rodada. Dar por processado perderia a atualização para sempre.
        assertThat(status).doesNotContain("PROCESSADO");
    }
}
