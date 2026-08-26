package com.vetor.niner;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.integracao.PedidoWebhookProcessador;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fila de expedição (R5, M7).
 *
 * <p>⭐ O teste que mais importa é {@link #polingNaoDevolveAFilaUmPedidoJaSeparado()}: o
 * marketplace continua dizendo "paid" enquanto a loja já está separando, e um UPDATE ingênuo
 * devolveria o pedido à fila — já separado — sem nada avisando.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ExpedicaoTest {

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
    PedidoWebhookProcessador webhooks;

    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void limpar() {
        ML.resetAll();
    }

    // ------------------------------------------------------------------------------ ferramentas

    private static String payloadDo(String token) {
        return new String(java.util.Base64.getUrlDecoder()
                .decode(token.substring(token.indexOf('.') + 1, token.lastIndexOf('.'))));
    }

    private static long idTenantDo(String token) {
        return ((Number) JsonPath.read(payloadDo(token), "$.tid")).longValue();
    }

    private static long idEmpresaDo(String token) {
        return ((Number) JsonPath.read(payloadDo(token), "$.eid")).longValue();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post2(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder get2(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
    }

    private String assinarNovoTenant(String sufixo) throws Exception {
        String resp = mvc.perform(post2("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja Exp %s","email":"dono%s@lojaexp.com",
                         "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                        """.formatted(sufixo, sufixo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
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

    private long criarCanalConectado(String token, String vendedor) throws Exception {
        String resp = mvc.perform(post2("/api/v1/canais/MERCADO_LIVRE")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"ML EXP\",\"percPreco\":0,\"idEmpresa\":%d}"
                                .formatted(idEmpresaDo(token))))
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
                         "user_id":%s,"refresh_token":"ref"}
                        """.formatted(vendedor))));
        mvc.perform(get2("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "c").param("state", state))
                .andExpect(status().isFound());
        return idCanal;
    }

    private long criarVariacaoComEstoque(String token, String descricao) throws Exception {
        String resp = mvc.perform(post2("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"30.00","percentualVenda":"100",
                                 "precoVenda":"50.00","ativo":true}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idProduto = ((Number) JsonPath.read(resp, "$.idProduto")).longValue();

        String varResp = mvc.perform(post2("/api/v1/produtos/" + idProduto + "/variacoes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idVariacao = ((Number) JsonPath.read(varResp, "$.idVariacao")).longValue();

        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        var tx = new TransactionTemplate(txManager);
        TenantContext.comTenant(idTenant, () -> tx.executeWithoutResult(s -> {
            Long idMestre = jdbc.sql("""
                            INSERT INTO produto_movimento_mestre
                                   (id_tenant, id_empresa, tipo_movimento, data_movimento)
                            VALUES (plataforma.tenant_atual(), ?, 'COMPRA', now())
                            RETURNING id_movimento
                            """).params(idEmpresa).query(Long.class).single();
            jdbc.sql("""
                            INSERT INTO produto_movimento_detalhe
                                   (id_tenant, id_empresa, id_movimento, id_variacao,
                                    credito_debito, qtd_produto, preco_custo, preco_venda)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'C', 50, 30, 50)
                            """).params(idEmpresa, idMestre, idVariacao).update();
        }));
        return idVariacao;
    }

    private void vincular(String token, long idCanal, String idExterno, long idVariacao) throws Exception {
        ML.stubFor(get(urlPathEqualTo("/items/" + idExterno)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"%s\",\"available_quantity\":0,\"variations\":[]}".formatted(idExterno))));
        ML.stubFor(put(urlMatching("/items/" + idExterno + ".*")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")));
        mvc.perform(post2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"idExterno\":\"%s\",\"idVariacao\":%d}".formatted(idExterno, idVariacao)))
                .andExpect(status().isCreated());
    }

    private void mlTemPedido(String idPedido, String status, String idItem) {
        ML.stubFor(get(urlPathEqualTo("/orders/" + idPedido)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":%s,"status":"%s","total_amount":80.00,
                         "buyer":{"nickname":"COMPRADOR"},"shipping":{"id":"ENV9"},
                         "order_items":[{"item":{"id":"%s","title":"PRODUTO"},
                                         "quantity":1,"unit_price":80.00}]}
                        """.formatted(idPedido, status, idItem))));
        ML.stubFor(get(urlPathEqualTo("/shipments/ENV9")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"ENV9\",\"shipping_option\":{\"cost\":10.00}}")));
    }

    private void notificar(String vendedor, String idPedido, int tentativa) throws Exception {
        mvc.perform(post2("/api/publico/webhooks/mercadolivre").contentType(APPLICATION_JSON)
                        .content("""
                                {"topic":"orders_v2","resource":"/orders/%s","user_id":%s,"attempts":%d}
                                """.formatted(idPedido, vendedor, tentativa)))
                .andExpect(status().isOk());
    }

    private void processar(long idTenant) {
        TenantContext.comTenant(idTenant, () -> webhooks.processarLoteDoTenantCorrente());
    }

    private String statusDoPedido(long idTenant) {
        var tx = new TransactionTemplate(txManager);
        return TenantContext.comTenant(idTenant, () -> tx.execute(s -> jdbc.sql("""
                        SELECT status::text FROM pedido WHERE id_tenant = plataforma.tenant_atual()
                        """).query(String.class).single()));
    }

    /** Deixa um pedido PAGO e importado. @return o id do pedido no ERP */
    private long pedidoPago(String token, String vendedor, String idPedidoMl, String idItem)
            throws Exception {
        long idTenant = idTenantDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, vendedor);
        long idVariacao = criarVariacaoComEstoque(token, "PRODUTO EXP");
        vincular(token, idCanal, idItem, idVariacao);

        mlTemPedido(idPedidoMl, "paid", idItem);
        notificar(vendedor, idPedidoMl, 1);
        processar(idTenant);

        var tx = new TransactionTemplate(txManager);
        Long id = TenantContext.comTenant(idTenant, () -> tx.execute(s -> jdbc.sql("""
                        SELECT id_pedido FROM pedido WHERE id_tenant = plataforma.tenant_atual()
                        """).query(Long.class).single()));
        return id == null ? 0 : id;
    }

    // ------------------------------------------------------------------------------------ fila

    /** ⭐ O ciclo: o pedido pago aparece na fila, é separado e despachado. */
    @Test
    void pedidoPagoEntraNaFilaESegueAteEnviado() throws Exception {
        String token = assinarNovoTenant("ciclo-exp");
        long idTenant = idTenantDo(token);
        long idPedido = pedidoPago(token, "900", "4000000001", "MLB500");

        mvc.perform(get2("/api/v1/expedicao").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PAGO"))
                .andExpect(jsonPath("$[0].comprador").value("COMPRADOR"))
                .andExpect(jsonPath("$[0].itens").value(1));

        // A lista que o separador leva para a prateleira.
        mvc.perform(get2("/api/v1/expedicao/%d/itens".formatted(idPedido))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].descricao").value(
                        org.hamcrest.Matchers.containsString("PRODUTO EXP")));

        mvc.perform(post2("/api/v1/expedicao/%d/separar".formatted(idPedido))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(statusDoPedido(idTenant)).isEqualTo("EM_SEPARACAO");

        mvc.perform(post2("/api/v1/expedicao/%d/enviar".formatted(idPedido))
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"codigoRastreio\":\"BR123456789\"}"))
                .andExpect(status().isNoContent());
        assertThat(statusDoPedido(idTenant)).isEqualTo("ENVIADO");

        // Enviado sai da fila: ela é "o que ainda não saiu".
        mvc.perform(get2("/api/v1/expedicao").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));

        // ⭐ E o aviso ao canal foi ENFILEIRADO, não feito na requisição: o pacote já saiu no mundo
        // físico, e uma indisponibilidade do ML não pode impedir o lojista de registrar isso.
        var tx = new TransactionTemplate(txManager);
        Integer eventos = TenantContext.comTenant(idTenant, () -> tx.execute(s -> jdbc.sql("""
                        SELECT count(*) FROM outbox_evento
                         WHERE id_tenant = plataforma.tenant_atual() AND tipo = 'ENVIO_CONFIRMADO'
                        """).query(Integer.class).single()));
        assertThat(eventos).isEqualTo(1);
    }

    /**
     * ⭐ O defeito que a V070 existe para impedir.
     *
     * <p>O marketplace continua dizendo "paid" enquanto a loja já está separando. Um UPDATE
     * ingênuo na reimportação devolveria o pedido à fila — <b>já separado</b> — e alguém o
     * separaria de novo, montando dois pacotes para a mesma venda.
     */
    @Test
    void polingNaoDevolveAFilaUmPedidoJaSeparado() throws Exception {
        String token = assinarNovoTenant("nao-volta");
        long idTenant = idTenantDo(token);
        long idPedido = pedidoPago(token, "901", "4000000002", "MLB501");

        mvc.perform(post2("/api/v1/expedicao/%d/separar".formatted(idPedido))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(statusDoPedido(idTenant)).isEqualTo("EM_SEPARACAO");

        // O ML notifica de novo, ainda dizendo "paid".
        notificar("901", "4000000002", 2);
        processar(idTenant);

        assertThat(statusDoPedido(idTenant))
                .as("o canal não pode devolver à fila um pedido já separado")
                .isEqualTo("EM_SEPARACAO");
    }

    /**
     * ⛔ ...mas CANCELADO do canal sempre vence: o comprador desistiu, e isso é fato do canal.
     */
    @Test
    void canceladoNoCanalVenceMesmoDepoisDeSeparado() throws Exception {
        String token = assinarNovoTenant("cancela-exp");
        long idTenant = idTenantDo(token);
        long idPedido = pedidoPago(token, "902", "4000000003", "MLB502");

        mvc.perform(post2("/api/v1/expedicao/%d/separar".formatted(idPedido))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mlTemPedido("4000000003", "cancelled", "MLB502");
        notificar("902", "4000000003", 2);
        processar(idTenant);

        assertThat(statusDoPedido(idTenant)).isEqualTo("CANCELADO");
    }

    // ------------------------------------------------------------------------------- as recusas

    /**
     * ⛔ Separar duas vezes é recusado — e a mensagem diz o <b>estado real</b>.
     *
     * <p>"Não foi possível separar" mandaria o operador procurar problema no sistema. "Este pedido
     * está em separação" resolve a dúvida na mesma frase: quase sempre é um colega que já pegou.
     */
    @Test
    void separarDuasVezesRecusaDizendoOEstadoReal() throws Exception {
        String token = assinarNovoTenant("duplo-exp");
        long idPedido = pedidoPago(token, "903", "4000000004", "MLB503");

        mvc.perform(post2("/api/v1/expedicao/%d/separar".formatted(idPedido))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(post2("/api/v1/expedicao/%d/separar".formatted(idPedido))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("em separação")));
    }

    /** Não dá para enviar o que não foi separado. */
    @Test
    void enviarSemSepararERecusado() throws Exception {
        String token = assinarNovoTenant("pula-etapa");
        long idPedido = pedidoPago(token, "904", "4000000005", "MLB504");

        mvc.perform(post2("/api/v1/expedicao/%d/enviar".formatted(idPedido))
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    /**
     * ⛔ Pedido RECEBIDO (não pago) não entra na fila.
     *
     * <p>Separar mercadoria para um pedido que pode não ser pago é trabalho jogado fora — e a peça
     * sai da prateleira e some do fluxo da loja. A reserva já segura o estoque.
     */
    @Test
    void pedidoNaoPagoNaoApareceNaFila() throws Exception {
        String token = assinarNovoTenant("nao-pago");
        long idTenant = idTenantDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "905");
        long idVariacao = criarVariacaoComEstoque(token, "PRODUTO NAO PAGO");
        vincular(token, idCanal, "MLB505", idVariacao);

        mlTemPedido("4000000006", "confirmed", "MLB505");
        notificar("905", "4000000006", 1);
        processar(idTenant);

        assertThat(statusDoPedido(idTenant)).isEqualTo("RECEBIDO");
        mvc.perform(get2("/api/v1/expedicao").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
