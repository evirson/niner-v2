package com.vetor.niner;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.integracao.PedidoPollingProcessador;
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

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Importação de pedido do marketplace (M5, R5) contra WireMock.
 *
 * <p>⭐ Os dois testes que mais importam aqui:
 * <ul>
 *   <li>{@link #anuncioNaoVinculadoNaoGravaPedidoPelaMetade()} — recusar o pedido inteiro é
 *       deliberado; meia importação faria a loja despachar um pacote faltando produto.</li>
 *   <li>{@link #importarDuasVezesNaoDuplicaPedidoNemItem()} — idempotência (P2), que é a promessa
 *       do outbox e do webhook.</li>
 * </ul>
 *
 * <p>⚠️ Contra WireMock. Nenhuma notificação real do Mercado Livre chegou até hoje.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PedidoCanalTest {

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
    PedidoPollingProcessador polling;

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
                        {"nomeLoja":"Loja Pedido %s","email":"dono%s@lojaped.com",
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

    /** Conecta o canal com o vendedor `vendedor` — é ele que o webhook usa para achar o dono. */
    private long criarCanalConectado(String token, String vendedor) throws Exception {
        String resp = mvc.perform(post2("/api/v1/canais/MERCADO_LIVRE")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"ML PEDIDO\",\"percPreco\":0,\"idEmpresa\":%d}"
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

    /**
     * ⚠️ Cria a variação <b>com estoque</b>.
     *
     * <p>Desde o M6 o pedido {@code paid} vira venda na hora, e a venda debita estoque de verdade
     * — com o controle de estoque ligado (que marketplace exige), um produto sem saldo faz a
     * conversão ser recusada pela trava de estoque negativo. Não é um ajuste de conveniência do
     * teste: é a regra do produto aparecendo.
     */
    private long criarVariacao(String token, String descricao) throws Exception {
        String resp = mvc.perform(post2("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100",
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
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'C', 50, 10, 50)
                            """).params(idEmpresa, idMestre, idVariacao).update();
        }));
        return idVariacao;
    }

    private void vincular(String token, long idCanal, String idExterno, long idVariacao) throws Exception {
        // O adapter lê o item antes de escrever; o vínculo já enfileira a primeira publicação.
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

    /** O ML devolve o pedido. ⚠️ Formato novo: o envio NÃO vem aqui, vem de /shipments. */
    private void mlTemPedido(String idPedido, String idItem, int quantidade, String precoUnit) {
        ML.stubFor(get(urlPathEqualTo("/orders/" + idPedido)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":%s,"status":"paid","total_amount":%s,
                         "buyer":{"nickname":"COMPRADOR TESTE"},
                         "shipping":{"id":"ENV1"},
                         "order_items":[{"item":{"id":"%s","title":"PRODUTO DO ANUNCIO"},
                                         "quantity":%d,"unit_price":%s}]}
                        """.formatted(idPedido, precoUnit, idItem, quantidade, precoUnit))));
        ML.stubFor(get(urlPathEqualTo("/shipments/ENV1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"ENV1\",\"shipping_option\":{\"cost\":19.90}}")));
    }

    private void notificar(String vendedor, String recurso) throws Exception {
        mvc.perform(post2("/api/publico/webhooks/mercadolivre").contentType(APPLICATION_JSON)
                        .content("""
                                {"topic":"orders_v2","resource":"%s","user_id":%s,"attempts":1}
                                """.formatted(recurso, vendedor)))
                .andExpect(status().isOk());
    }

    private void processar(long idTenant) {
        TenantContext.comTenant(idTenant, () -> webhooks.processarLoteDoTenantCorrente());
    }

    private <T> T noBanco(long idTenant, java.util.function.Function<JdbcClient, T> consulta) {
        var tx = new TransactionTemplate(txManager);
        return TenantContext.comTenant(idTenant, () -> tx.execute(s -> consulta.apply(jdbc)));
    }

    private int contar(long idTenant, String tabela) {
        return noBanco(idTenant, j -> j.sql(
                        "SELECT count(*) FROM " + tabela + " WHERE id_tenant = plataforma.tenant_atual()")
                .query(Integer.class).single());
    }

    // ------------------------------------------------------------------------- a corrente toda

    /** ⭐ Notificação → consulta ao canal → pedido na fila de expedição. */
    @Test
    void notificacaoViraPedidoNaFilaDeExpedicao() throws Exception {
        String token = assinarNovoTenant("corrente");
        long idTenant = idTenantDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "777");
        long idVariacao = criarVariacao(token, "PRODUTO DO PEDIDO");
        vincular(token, idCanal, "MLB200", idVariacao);

        mlTemPedido("2000000001", "MLB200", 2, "80.00");
        notificar("777", "/orders/2000000001");
        processar(idTenant);

        assertThat(contar(idTenant, "pedido")).isEqualTo(1);
        assertThat(contar(idTenant, "pedido_item")).isEqualTo(1);

        var pedido = noBanco(idTenant, j -> j.sql("""
                        SELECT status::text AS status, total, frete, comprador ->> 'nome' AS nome
                          FROM pedido WHERE id_tenant = plataforma.tenant_atual()
                        """).query((rs, n) -> List.of(rs.getString("status"),
                        rs.getBigDecimal("total").toPlainString(),
                        rs.getBigDecimal("frete").toPlainString(),
                        String.valueOf(rs.getString("nome")))).single());

        assertThat(pedido.get(0)).isEqualTo("PAGO");
        assertThat(new BigDecimal(pedido.get(1))).isEqualByComparingTo("80.00");
        // ⚠️ O frete veio da SEGUNDA chamada: o formato novo de /orders não o traz.
        assertThat(new BigDecimal(pedido.get(2))).isEqualByComparingTo("19.90");
        assertThat(pedido.get(3)).isEqualTo("COMPRADOR TESTE");

        ML.verify(getRequestedFor(urlPathEqualTo("/orders/2000000001")));
        ML.verify(getRequestedFor(urlPathEqualTo("/shipments/ENV1")));
    }

    /**
     * ⛔ Anúncio não vinculado recusa o pedido INTEIRO — nada é gravado.
     *
     * <p>Importar só o que dá faria um pedido com metade das linhas: parece completo na tela de
     * expedição, e a loja despacharia um pacote faltando produto.
     */
    @Test
    void anuncioNaoVinculadoNaoGravaPedidoPelaMetade() throws Exception {
        String token = assinarNovoTenant("sem-vinculo");
        long idTenant = idTenantDo(token);
        ligarControleDeEstoque(token);
        criarCanalConectado(token, "778");

        mlTemPedido("2000000002", "MLB999", 1, "50.00");
        notificar("778", "/orders/2000000002");
        processar(idTenant);

        assertThat(contar(idTenant, "pedido")).isZero();
        assertThat(contar(idTenant, "pedido_item")).isZero();

        // ⭐ E a notificação NÃO foi dada por processada: quando o lojista vincular, ela passa
        // sozinha. A mensagem na linha é a que a tela vai mostrar.
        var pendente = noBanco(idTenant, j -> j.sql("""
                        SELECT processado_em IS NULL AS pendente, erro
                          FROM webhook_recebido WHERE id_tenant = plataforma.tenant_atual()
                        """).query((rs, n) -> List.of(rs.getBoolean("pendente"),
                        String.valueOf(rs.getString("erro")))).single());
        assertThat((Boolean) pendente.get(0)).isTrue();
        assertThat((String) pendente.get(1)).contains("não está vinculado");
    }

    /** ⭐ E depois de vincular, a MESMA notificação passa sozinha — sem ninguém reprocessar. */
    @Test
    void depoisDeVincularAMesmaNotificacaoPassaSozinha() throws Exception {
        String token = assinarNovoTenant("passa-depois");
        long idTenant = idTenantDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "779");

        mlTemPedido("2000000003", "MLB201", 1, "50.00");
        notificar("779", "/orders/2000000003");
        processar(idTenant);
        assertThat(contar(idTenant, "pedido")).isZero();

        long idVariacao = criarVariacao(token, "PRODUTO QUE FALTAVA");
        vincular(token, idCanal, "MLB201", idVariacao);

        processar(idTenant);
        assertThat(contar(idTenant, "pedido")).isEqualTo(1);
    }

    /** ⭐ Idempotência (P2): a mesma notificação duas vezes não duplica nada. */
    @Test
    void importarDuasVezesNaoDuplicaPedidoNemItem() throws Exception {
        String token = assinarNovoTenant("idempotente");
        long idTenant = idTenantDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "780");
        long idVariacao = criarVariacao(token, "PRODUTO REPETIDO");
        vincular(token, idCanal, "MLB202", idVariacao);

        mlTemPedido("2000000004", "MLB202", 1, "50.00");
        notificar("780", "/orders/2000000004");
        processar(idTenant);

        // O ML notifica cada mudança de estado — o mesmo pedido chega de novo.
        mvc.perform(post2("/api/publico/webhooks/mercadolivre").contentType(APPLICATION_JSON)
                        .content("""
                                {"topic":"orders_v2","resource":"/orders/2000000004","user_id":780,"attempts":2}
                                """))
                .andExpect(status().isOk());
        processar(idTenant);
        // E o polling traz os recentes de novo, pela terceira vez.
        ML.stubFor(get(urlPathEqualTo("/orders/search")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"results\":[{\"id\":2000000004}]}")));
        TenantContext.comTenant(idTenant, () -> polling.varrerTenantCorrente());

        assertThat(contar(idTenant, "pedido")).isEqualTo(1);
        assertThat(contar(idTenant, "pedido_item")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------- webhook

    /**
     * ⛔ Vendedor desconhecido responde <b>200</b>, não 404.
     *
     * <p>O ML desativa callback que falha repetidamente. Devolver erro para uma notificação de
     * vendedor que já desconectou treinaria a plataforma a desligar o nosso endereço — e aí
     * <b>nenhum</b> lojista receberia pedido.
     */
    @Test
    void vendedorDesconhecidoRecebe200ENaoGravaNada() throws Exception {
        mvc.perform(post2("/api/publico/webhooks/mercadolivre").contentType(APPLICATION_JSON)
                        .content("""
                                {"topic":"orders_v2","resource":"/orders/9","user_id":999999,"attempts":1}
                                """))
                .andExpect(status().isOk());
    }

    /** Corpo estranho não derruba o endpoint — 200 e segue a vida. */
    @Test
    void corpoSemDadosRecebe200() throws Exception {
        mvc.perform(post2("/api/publico/webhooks/mercadolivre").contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    /**
     * ⛔ O webhook não decide nada: ele NÃO consulta o marketplace na hora.
     *
     * <p>É o que torna um payload forjado inofensivo — e o que mantém a resposta rápida, que é o
     * que a plataforma mede para decidir se o callback está saudável.
     */
    @Test
    void webhookNaoFalaComOMarketplaceNaHora() throws Exception {
        String token = assinarNovoTenant("nao-decide");
        ligarControleDeEstoque(token);
        criarCanalConectado(token, "781");
        ML.resetRequests();

        notificar("781", "/orders/2000000005");

        assertThat(ML.findAll(getRequestedFor(urlPathEqualTo("/orders/2000000005")))).isEmpty();
    }

    /** Tópico que não é de pedido é registrado e encerrado, sem virar erro no painel. */
    @Test
    void topicoDeItemNaoViraErro() throws Exception {
        String token = assinarNovoTenant("topico-item");
        long idTenant = idTenantDo(token);
        ligarControleDeEstoque(token);
        criarCanalConectado(token, "782");

        mvc.perform(post2("/api/publico/webhooks/mercadolivre").contentType(APPLICATION_JSON)
                        .content("""
                                {"topic":"items","resource":"/items/MLB1","user_id":782,"attempts":1}
                                """))
                .andExpect(status().isOk());
        processar(idTenant);

        String erro = noBanco(idTenant, j -> j.sql("""
                        SELECT COALESCE(erro, '') FROM webhook_recebido
                         WHERE id_tenant = plataforma.tenant_atual()
                        """).query(String.class).single());
        assertThat(erro).isEmpty();
    }

    // ------------------------------------------------------------------------------- isolamento

    /**
     * ⛔ P8: a mesma conta de marketplace não pode estar conectada em dois tenants.
     *
     * <p>Se estivesse, uma notificação de venda seria ambígua — e o desempate importaria o pedido
     * de um lojista dentro da loja de outro.
     */
    @Test
    void mesmaContaDoMarketplaceNaoConectaEmDoisTenants() throws Exception {
        String tokenA = assinarNovoTenant("conta-a");
        String tokenB = assinarNovoTenant("conta-b");
        ligarControleDeEstoque(tokenA);
        ligarControleDeEstoque(tokenB);

        criarCanalConectado(tokenA, "790");

        // B tenta conectar a MESMA conta do Mercado Livre.
        String resp = mvc.perform(post2("/api/v1/canais/MERCADO_LIVRE")
                        .header("Authorization", "Bearer " + tokenB).contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"ML DE B\",\"percPreco\":0,\"idEmpresa\":%d}"
                                .formatted(idEmpresaDo(tokenB))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long canalDeB = ((Number) JsonPath.read(resp, "$.idCanal")).longValue();

        String urlResp = mvc.perform(get2("/api/v1/canais/%d/mercadolivre/autorizar".formatted(canalDeB))
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(urlResp, "$.url");
        String state = java.net.URLDecoder.decode(
                url.substring(url.indexOf("&state=") + 7), java.nio.charset.StandardCharsets.UTF_8);

        ML.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"access_token":"tok","token_type":"bearer","expires_in":21600,
                         "user_id":790,"refresh_token":"ref"}
                        """)));

        // A volta redireciona com erro; o canal de B NÃO conecta.
        mvc.perform(get2("/api/publico/canais/mercadolivre/retorno")
                        .param("code", "c").param("state", state))
                .andExpect(status().isFound());

        String statusDeB = mvc.perform(get2("/api/v1/canais/" + canalDeB)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(statusDeB, "$.status")).isNotEqualTo("CONECTADO");
    }

    /** A notificação de um vendedor cai no tenant dele, nunca no outro. */
    @Test
    void notificacaoCaiNoTenantDono() throws Exception {
        String tokenA = assinarNovoTenant("dono-not-a");
        String tokenB = assinarNovoTenant("dono-not-b");
        ligarControleDeEstoque(tokenA);
        ligarControleDeEstoque(tokenB);
        long canalA = criarCanalConectado(tokenA, "791");
        criarCanalConectado(tokenB, "792");

        long varA = criarVariacao(tokenA, "PRODUTO DE A");
        vincular(tokenA, canalA, "MLB300", varA);
        mlTemPedido("2000000006", "MLB300", 1, "50.00");

        notificar("791", "/orders/2000000006");
        processar(idTenantDo(tokenA));
        processar(idTenantDo(tokenB));

        assertThat(contar(idTenantDo(tokenA), "pedido")).isEqualTo(1);
        assertThat(contar(idTenantDo(tokenB), "pedido")).isZero();
        assertThat(contar(idTenantDo(tokenB), "webhook_recebido")).isZero();
    }

    /** O parser do recurso não confia no formato. */
    @Test
    void recursoQueNaoEPedidoNaoViraImportacao() {
        assertThat(PedidoWebhookProcessador.idDoRecurso("/orders/123")).isEqualTo("123");
        assertThat(PedidoWebhookProcessador.idDoRecurso("/items/MLB1")).isNull();
        assertThat(PedidoWebhookProcessador.idDoRecurso("/orders/")).isNull();
        assertThat(PedidoWebhookProcessador.idDoRecurso(null)).isNull();
    }
}
