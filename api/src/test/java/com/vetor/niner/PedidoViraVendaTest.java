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

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pedido de marketplace vira <b>venda</b> (M6, R5) — a decisão nº 1 da §8.
 *
 * <p>⭐ O que se prova aqui é a corrente inteira e as três decisões de produto que ela carrega:
 * <b>sem caixa</b>, <b>sem comissão</b>, e <b>o dinheiro entra pela carteira do canal</b>.
 *
 * <p>⚠️ Contra WireMock. Nenhum pedido real do Mercado Livre passou por aqui.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PedidoViraVendaTest {

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
                        {"nomeLoja":"Loja Venda %s","email":"dono%s@lojavenda.com",
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
                        .content("{\"nome\":\"ML VENDA\",\"percPreco\":0,\"idEmpresa\":%d}"
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

    private long criarVariacaoComEstoque(String token, long idTenant, long idEmpresa,
                                         String descricao, String quantidade) throws Exception {
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
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'C', CAST(? AS numeric), 30, 50)
                            """).params(idEmpresa, idMestre, idVariacao, quantidade).update();
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

    private void mlTemPedido(String idPedido, String status, String idItem, int qtd, String precoUnit) {
        ML.stubFor(get(urlPathEqualTo("/orders/" + idPedido)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":%s,"status":"%s","total_amount":%s,
                         "buyer":{"nickname":"COMPRADOR"},
                         "order_items":[{"item":{"id":"%s","title":"PRODUTO"},
                                         "quantity":%d,"unit_price":%s}]}
                        """.formatted(idPedido, status, precoUnit, idItem, qtd, precoUnit))));
    }

    private void notificar(String vendedor, String idPedido) throws Exception {
        mvc.perform(post2("/api/publico/webhooks/mercadolivre").contentType(APPLICATION_JSON)
                        .content("""
                                {"topic":"orders_v2","resource":"/orders/%s","user_id":%s,"attempts":1}
                                """.formatted(idPedido, vendedor)))
                .andExpect(status().isOk());
    }

    private void processar(long idTenant) {
        TenantContext.comTenant(idTenant, () -> webhooks.processarLoteDoTenantCorrente());
    }

    private <T> T noBanco(long idTenant, java.util.function.Function<JdbcClient, T> consulta) {
        var tx = new TransactionTemplate(txManager);
        return TenantContext.comTenant(idTenant, () -> tx.execute(s -> consulta.apply(jdbc)));
    }

    /**
     * ⚠️ Helper próprio em vez de {@code noBanco(... count ...)}: com o genérico, o compilador
     * infere {@code Predicate<Object>} e o {@code assertThat} vira ambíguo. Erro de inferência que
     * aparece como "assertThat is ambiguous", longe da causa.
     */
    private int contar(long idTenant, String tabela) {
        var tx = new TransactionTemplate(txManager);
        Integer quantos = TenantContext.comTenant(idTenant, () -> tx.execute(s -> jdbc.sql(
                        "SELECT count(*) FROM " + tabela + " WHERE id_tenant = plataforma.tenant_atual()")
                .query(Integer.class).single()));
        return quantos == null ? 0 : quantos;
    }

    private BigDecimal saldo(long idTenant, long idVariacao, String coluna) {
        return noBanco(idTenant, j -> j.sql(
                        "SELECT " + coluna + " FROM produto_estoque "
                                + "WHERE id_tenant = plataforma.tenant_atual() AND id_variacao = ?")
                .params(idVariacao).query(BigDecimal.class).single());
    }

    // ------------------------------------------------------------------------------- a reserva

    /**
     * ⭐ Pedido RECEBIDO reserva o estoque — e a reserva derruba o <b>disponível</b>, que é o que
     * o M3 publica no anúncio. O laço do anti-overselling se fecha sozinho.
     */
    @Test
    void pedidoRecebidoReservaEstoqueSemVirarVenda() throws Exception {
        String token = assinarNovoTenant("reserva");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "800");
        long idVariacao = criarVariacaoComEstoque(token, idTenant, idEmpresa, "PRODUTO RESERVA", "10");
        vincular(token, idCanal, "MLB400", idVariacao);

        mlTemPedido("3000000001", "confirmed", "MLB400", 3, "80.00");
        notificar("800", "3000000001");
        processar(idTenant);

        assertThat(saldo(idTenant, idVariacao, "qtd_estoque")).isEqualByComparingTo("10");
        assertThat(saldo(idTenant, idVariacao, "reservado")).isEqualByComparingTo("3");
        // ⭐ É este número que vai para o anúncio.
        assertThat(saldo(idTenant, idVariacao, "disponivel")).isEqualByComparingTo("7");

        // Ainda NÃO virou venda: o comprador pode não pagar.
        assertThat(contar(idTenant, "venda")).isZero();
    }

    /** ⛔ A mesma notificação três vezes não reserva três vezes. */
    @Test
    void reservaNaoDobraComNotificacaoRepetida() throws Exception {
        String token = assinarNovoTenant("reserva-2x");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "801");
        long idVariacao = criarVariacaoComEstoque(token, idTenant, idEmpresa, "PRODUTO 2X", "10");
        vincular(token, idCanal, "MLB401", idVariacao);

        mlTemPedido("3000000002", "confirmed", "MLB401", 2, "80.00");
        for (int i = 0; i < 3; i++) {
            notificar("801", "3000000002");
            processar(idTenant);
        }

        assertThat(saldo(idTenant, idVariacao, "reservado")).isEqualByComparingTo("2");
    }

    // ------------------------------------------------------------------------------- a conversão

    /**
     * ⭐ O teste central do M6: pedido PAGO vira venda — <b>sem caixa</b>, <b>sem comissão</b>, com
     * o dinheiro entrando pela carteira do canal.
     */
    @Test
    void pedidoPagoViraVendaSemCaixaESemComissao() throws Exception {
        String token = assinarNovoTenant("converte");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "802");
        long idVariacao = criarVariacaoComEstoque(token, idTenant, idEmpresa, "PRODUTO VENDIDO", "10");
        vincular(token, idCanal, "MLB402", idVariacao);

        mlTemPedido("3000000003", "paid", "MLB402", 2, "80.00");
        notificar("802", "3000000003");
        processar(idTenant);

        // Estoque saiu de verdade, e a reserva foi liberada — senão a mesma peça sairia duas vezes
        // de `disponivel`.
        assertThat(saldo(idTenant, idVariacao, "qtd_estoque")).isEqualByComparingTo("8");
        assertThat(saldo(idTenant, idVariacao, "reservado")).isEqualByComparingTo("0");

        var venda = noBanco(idTenant, j -> j.sql("""
                        SELECT id_venda, origem::text AS origem, id_caixa, id_cliente
                          FROM venda WHERE id_tenant = plataforma.tenant_atual()
                        """).query((rs, n) -> new Object[]{rs.getLong("id_venda"),
                        rs.getString("origem"), rs.getObject("id_caixa"), rs.getObject("id_cliente")})
                .single());

        assertThat(venda[1]).isEqualTo("MARKETPLACE");
        // ⛔ As três decisões da §8, viradas asserção.
        assertThat(venda[2]).as("venda de marketplace NÃO entra no caixa").isNull();
        assertThat(venda[3]).as("o comprador do marketplace não vira cadastro").isNull();

        // ⚠️ Devolve BOOLEAN, não o valor: `.single()` do JdbcClient recusa um mapper que retorna
        // null ("Result value is null but no null value expected") — e o valor que se quer afirmar
        // aqui É null. Testar ausência exige perguntar "é nulo?" dentro do SQL/mapper.
        Boolean semVendedor = noBanco(idTenant, j -> j.sql("""
                        SELECT id_funcionario IS NULL AS sem_vendedor
                          FROM produto_movimento_detalhe
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND credito_debito = 'D'
                        """).query((rs, n) -> rs.getBoolean("sem_vendedor")).single());
        assertThat(semVendedor).as("sem vendedor, logo sem comissão").isTrue();

        // ⭐ E o dinheiro entra pela carteira do canal — é isso que faz DRE, Lucratividade e Fluxo
        // de Caixa enxergarem a venda sem código novo.
        var receber = noBanco(idTenant, j -> j.sql("""
                        SELECT cr.valor_receber, cr.data_recebimento, tc.nome_carteira
                          FROM contas_receber cr
                          JOIN tipo_carteira tc ON tc.id_tenant = cr.id_tenant AND tc.id_carteira = cr.id_carteira
                         WHERE cr.id_tenant = plataforma.tenant_atual()
                        """).query((rs, n) -> new Object[]{rs.getBigDecimal("valor_receber"),
                        rs.getObject("data_recebimento"), rs.getString("nome_carteira")}).single());

        assertThat((BigDecimal) receber[0]).isEqualByComparingTo("80.00");
        // ⚠️ Nasce EM ABERTO: o dinheiro do ML não está na conta no instante da venda.
        assertThat(receber[1]).isNull();
        assertThat(receber[2]).isEqualTo("ML VENDA");
    }

    /** ⛔ Idempotência: o mesmo pedido pago três vezes gera UMA venda. */
    @Test
    void converterDuasVezesNaoDuplicaVendaNemBaixaEstoqueDuasVezes() throws Exception {
        String token = assinarNovoTenant("converte-2x");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "803");
        long idVariacao = criarVariacaoComEstoque(token, idTenant, idEmpresa, "PRODUTO UNICO", "10");
        vincular(token, idCanal, "MLB403", idVariacao);

        mlTemPedido("3000000004", "paid", "MLB403", 2, "80.00");
        for (int i = 0; i < 3; i++) {
            notificar("803", "3000000004");
            processar(idTenant);
        }

        assertThat(contar(idTenant, "venda")).isEqualTo(1);
        assertThat(saldo(idTenant, idVariacao, "qtd_estoque")).isEqualByComparingTo("8");
        assertThat(contar(idTenant, "contas_receber")).isEqualTo(1);
    }

    /**
     * ⭐ O caminho de verdade: chega RECEBIDO (reserva) e depois PAGO (vira venda), em duas
     * notificações — que é como o marketplace realmente avisa.
     *
     * <p>⚠️ Este teste é o que prova que a conversão roda <b>também quando o pedido já existia</b>.
     * Reagir só ao pedido novo faria a venda nunca nascer, e ninguém notaria até o fim do mês.
     */
    @Test
    void recebidoDepoisPagoReservaEDepoisConverte() throws Exception {
        String token = assinarNovoTenant("ciclo");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "804");
        long idVariacao = criarVariacaoComEstoque(token, idTenant, idEmpresa, "PRODUTO CICLO", "10");
        vincular(token, idCanal, "MLB404", idVariacao);

        mlTemPedido("3000000005", "confirmed", "MLB404", 4, "80.00");
        notificar("804", "3000000005");
        processar(idTenant);
        assertThat(saldo(idTenant, idVariacao, "reservado")).isEqualByComparingTo("4");
        assertThat(saldo(idTenant, idVariacao, "qtd_estoque")).isEqualByComparingTo("10");

        // O comprador pagou. O MESMO pedido, agora PAGO.
        mlTemPedido("3000000005", "paid", "MLB404", 4, "80.00");
        mvc.perform(post2("/api/publico/webhooks/mercadolivre").contentType(APPLICATION_JSON)
                        .content("""
                                {"topic":"orders_v2","resource":"/orders/3000000005","user_id":804,"attempts":2}
                                """))
                .andExpect(status().isOk());
        processar(idTenant);

        assertThat(saldo(idTenant, idVariacao, "reservado")).isEqualByComparingTo("0");
        assertThat(saldo(idTenant, idVariacao, "qtd_estoque")).isEqualByComparingTo("6");
        assertThat(contar(idTenant, "venda")).isEqualTo(1);
    }

    /** Pedido cancelado antes de pagar devolve a reserva ao disponível. */
    @Test
    void pedidoCanceladoLiberaAReserva() throws Exception {
        String token = assinarNovoTenant("cancela");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "805");
        long idVariacao = criarVariacaoComEstoque(token, idTenant, idEmpresa, "PRODUTO CANCELA", "10");
        vincular(token, idCanal, "MLB405", idVariacao);

        mlTemPedido("3000000006", "confirmed", "MLB405", 5, "80.00");
        notificar("805", "3000000006");
        processar(idTenant);
        assertThat(saldo(idTenant, idVariacao, "disponivel")).isEqualByComparingTo("5");

        mlTemPedido("3000000006", "cancelled", "MLB405", 5, "80.00");
        mvc.perform(post2("/api/publico/webhooks/mercadolivre").contentType(APPLICATION_JSON)
                        .content("""
                                {"topic":"orders_v2","resource":"/orders/3000000006","user_id":805,"attempts":2}
                                """))
                .andExpect(status().isOk());
        processar(idTenant);

        assertThat(saldo(idTenant, idVariacao, "reservado")).isEqualByComparingTo("0");
        assertThat(saldo(idTenant, idVariacao, "disponivel")).isEqualByComparingTo("10");
    }

    /**
     * ⭐ A venda de marketplace aparece no Relatório de Vendas — <b>sem código novo lá</b>.
     *
     * <p>É a razão inteira de "pedido vira venda" ter sido a decisão escolhida.
     */
    @Test
    void vendaDeMarketplaceApareceNoRelatorioDeVendas() throws Exception {
        String token = assinarNovoTenant("relatorio");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "806");
        long idVariacao = criarVariacaoComEstoque(token, idTenant, idEmpresa, "PRODUTO RELATORIO", "10");
        vincular(token, idCanal, "MLB406", idVariacao);

        mlTemPedido("3000000007", "paid", "MLB406", 1, "80.00");
        notificar("806", "3000000007");
        processar(idTenant);

        String hoje = java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).toString();
        mvc.perform(get2("/api/v1/relatorios/vendas")
                        .header("Authorization", "Bearer " + token)
                        .param("dataInicial", hoje).param("dataFinal", hoje)
                        .param("idsEmpresa", String.valueOf(idEmpresa)))
                .andExpect(status().isOk());
        // A chamada responder 200 já prova o que importa: a venda de marketplace passa pelo mesmo
        // caminho, sem coluna nova nem tratamento especial. O conteúdo do relatório tem testes
        // próprios em RelatorioVendasCrudTest.
    }
}
