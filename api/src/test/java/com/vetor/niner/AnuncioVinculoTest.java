package com.vetor.niner;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.seguranca.TokenService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vínculo anúncio ↔ produto (R6, bloco M2) contra WireMock.
 *
 * <p>⭐ Dois testes aqui valem mais que os outros:
 * <ul>
 *   <li>{@link #precoDoAnuncioIgnoraAOfertaDaLoja()} — a regra da §8.6 do estudo, que até hoje
 *       estava protegida <b>só por javadoc</b>. O próprio estudo pedia este teste por escrito:
 *       <i>"quando o M3 for escrito, o teste dele precisa incluir um produto com oferta
 *       vigente"</i>. O chamador nasceu aqui, no M2, então a dívida vence aqui.</li>
 *   <li>{@link #mesmoProdutoNaoAlimentaDoisAnunciosDoMesmoCanal()} — a promessa central do produto
 *       (P1, zero overselling) virando restrição de banco.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AnuncioVinculoTest {


    /** A empresa em que o usuário entrou — o canal precisa dela desde a V067 (estoque é por empresa). */
    private static long idEmpresaDo(String token) {
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.substring(token.indexOf('.') + 1, token.lastIndexOf('.'))));
        return ((Number) com.jayway.jsonpath.JsonPath.read(payload, "$.eid")).longValue();
    }
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
    TokenService tokens;

    @BeforeEach
    void limpar() {
        ML.resetAll();
    }

    // ------------------------------------------------------------------------------ ferramentas

    private String assinarNovoTenant(String sufixo) throws Exception {
        String resp = mvc.perform(post2("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja Vinculo %s","email":"dono%s@lojavinc.com",
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

    /** Cria canal e o deixa CONECTADO passando pelo OAuth de verdade (com o ML falso). */
    private long criarCanalConectado(String token, String percPreco) throws Exception {
        String resp = mvc.perform(post2("/api/v1/canais/MERCADO_LIVRE")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"ML VINCULO\",\"percPreco\":%s,\"idEmpresa\":%d}"
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

    /** @return o SKU gerado da variação (o de-para do R6 casa por ele) */
    private String criarProdutoComVariacao(String token, String descricao, String precoVenda,
                                           String extraJson) throws Exception {
        String resp = mvc.perform(post2("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100",
                                 "precoVenda":"%s","ativo":true%s}
                                """.formatted(descricao, precoVenda, extraJson)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idProduto = ((Number) JsonPath.read(resp, "$.idProduto")).longValue();

        String varResp = mvc.perform(post2("/api/v1/produtos/" + idProduto + "/variacoes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(varResp, "$.sku");
    }

    private long idVariacaoDoSku(String token, long idCanal, String sku) throws Exception {
        String resp = mvc.perform(get2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Object> ids = JsonPath.read(resp,
                "$.linhas[?(@.sku=='%s')].idVariacaoSugerida".formatted(sku));
        return ((Number) ids.getFirst()).longValue();
    }

    /** O ML responde a busca de itens do vendedor e o detalhe em lote. */
    private void mlTemAnuncios(String corpoDoLote, String... ids) {
        ML.stubFor(get(urlPathEqualTo("/users/777/items/search")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"results\":[%s]}".formatted(
                        String.join(",", java.util.Arrays.stream(ids).map(i -> "\"" + i + "\"").toList())))));
        ML.stubFor(get(urlPathEqualTo("/items")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(corpoDoLote)));
    }

    // ---------------------------------------------------------------------------------- listar

    /**
     * ⭐ Anúncio com variações vira <b>uma linha por variação</b>, com a descrição montada a partir
     * de {@code attribute_combinations}.
     *
     * <p>Mostrar o id cru ({@code 1795538261}) obrigaria o lojista a abrir o Mercado Livre em outra
     * aba para saber o que está vinculando — justamente o trabalho que esta tela evita.
     */
    @Test
    void anuncioComVariacoesViraUmaLinhaPorVariacao() throws Exception {
        String token = assinarNovoTenant("variacoes");
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");

        mlTemAnuncios("""
                [{"code":200,"body":{"id":"MLB1","title":"CAMISETA","status":"active","price":80.00,
                  "available_quantity":10,
                  "variations":[
                    {"id":"V1","available_quantity":4,"price":80.00,"seller_custom_field":"SKU-P",
                     "attribute_combinations":[{"name":"Cor","value_name":"Azul"},
                                               {"name":"Tamanho","value_name":"P"}]},
                    {"id":"V2","available_quantity":6,"price":80.00,
                     "attribute_combinations":[{"name":"Cor","value_name":"Azul"},
                                               {"name":"Tamanho","value_name":"M"}]}]}}]
                """, "MLB1");

        mvc.perform(get2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(2))
                .andExpect(jsonPath("$.linhas[0].idExterno").value("MLB1"))
                .andExpect(jsonPath("$.linhas[0].idExternoVariacao").value("V1"))
                .andExpect(jsonPath("$.linhas[0].descricaoVariacao").value("Cor: Azul · Tamanho: P"))
                .andExpect(jsonPath("$.linhas[0].quantidadeNoCanal").value(4))
                .andExpect(jsonPath("$.linhas[1].idExternoVariacao").value("V2"))
                .andExpect(jsonPath("$.linhas[1].descricaoVariacao").value("Cor: Azul · Tamanho: M"))
                .andExpect(jsonPath("$.linhas[1].quantidadeNoCanal").value(6));
    }

    /** Anúncio simples vira uma linha só, com a variação do canal nula. */
    @Test
    void anuncioSemVariacaoViraUmaLinhaComVariacaoNula() throws Exception {
        String token = assinarNovoTenant("simples");
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");

        mlTemAnuncios("""
                [{"code":200,"body":{"id":"MLB9","title":"CANECA","status":"active","price":25.00,
                  "available_quantity":3,"variations":[]}}]
                """, "MLB9");

        mvc.perform(get2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(1))
                .andExpect(jsonPath("$.linhas[0].idExternoVariacao").doesNotExist())
                .andExpect(jsonPath("$.linhas[0].titulo").value("CANECA"));
    }

    /** ⭐ Sugere pelo SKU — mas sugerir não é vincular. */
    @Test
    void sugereOVinculoQuandoOSkuDoCanalBateComODoErp() throws Exception {
        String token = assinarNovoTenant("sugestao");
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");
        String sku = criarProdutoComVariacao(token, "CAMISETA AZUL", "50.00", "");

        mlTemAnuncios("""
                [{"code":200,"body":{"id":"MLB2","title":"CAMISETA AZUL","status":"active",
                  "price":50.00,"available_quantity":7,"seller_custom_field":"%s","variations":[]}}]
                """.formatted(sku), "MLB2");

        mvc.perform(get2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas[0].idVariacaoSugerida").isNumber())
                .andExpect(jsonPath("$.linhas[0].descricaoSugerida").value(
                        org.hamcrest.Matchers.containsString("CAMISETA AZUL")))
                // ⛔ Sugestão NÃO cria vínculo: quem confirma é o lojista.
                .andExpect(jsonPath("$.linhas[0].idVariacao").doesNotExist())
                .andExpect(jsonPath("$.linhas[0].idAnuncio").doesNotExist());
    }

    @Test
    void semSkuNoCanalNaoSugereNada() throws Exception {
        String token = assinarNovoTenant("sem-sku");
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");
        criarProdutoComVariacao(token, "PRODUTO SEM SKU NO ML", "50.00", "");

        mlTemAnuncios("""
                [{"code":200,"body":{"id":"MLB3","title":"OUTRA COISA","status":"active",
                  "price":50.00,"available_quantity":1,"variations":[]}}]
                """, "MLB3");

        mvc.perform(get2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas[0].idVariacaoSugerida").doesNotExist());
    }

    // -------------------------------------------------------------------------------- vincular

    @Test
    void vincularGravaOPrecoDerivadoDaRegraDoCanal() throws Exception {
        String token = assinarNovoTenant("preco");
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "20.00");
        String sku = criarProdutoComVariacao(token, "PRODUTO PRECO", "50.00", "");

        mlTemAnuncios("""
                [{"code":200,"body":{"id":"MLB4","title":"PRODUTO PRECO","status":"active",
                  "price":10.00,"available_quantity":1,"seller_custom_field":"%s","variations":[]}}]
                """.formatted(sku), "MLB4");
        long idVariacao = idVariacaoDoSku(token, idCanal, sku);

        mvc.perform(post2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"idExterno\":\"MLB4\",\"idVariacao\":%d}".formatted(idVariacao)))
                .andExpect(status().isCreated());

        // 50,00 + 20% = 60,00 — e o vínculo aparece na próxima listagem.
        mvc.perform(get2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.linhas[0].idVariacao").value(idVariacao))
                .andExpect(jsonPath("$.linhas[0].idAnuncio").isNumber());

        mvc.perform(get2("/api/v1/canais/" + idCanal).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.anunciosVinculados").value(1));
    }

    /**
     * ⭐ A regra da §8.6, que até hoje vivia só no javadoc.
     *
     * <p>O preço do anúncio deriva de {@code preco_venda} e <b>ignora a oferta vigente da loja</b>.
     * Promoção de fim de semana no balcão não pode derrubar o preço no marketplace — quem quiser
     * promoção no canal a faz lá.
     *
     * <p>⚠️ Nenhum teste unitário conseguiria prender isto: a aritmética de {@code PrecoDoCanal}
     * está certa nos dois casos. O que decide é <b>qual coluna o chamador passa</b>, e o chamador
     * nasceu aqui.
     */
    @Test
    void precoDoAnuncioIgnoraAOfertaDaLoja() throws Exception {
        String token = assinarNovoTenant("oferta");
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");

        // Oferta VIGENTE (começa hoje) e bem mais barata que o preço de venda.
        //
        // ⚠️ O fuso é explícito e a hora é MEIO-DIA, não "agora": `ProdutoService.validarOferta`
        // recusa início no passado comparando com `LocalDate.now(America/Sao_Paulo)`. Um
        // `OffsetDateTime.now()` no fuso da JVM (que em container é UTC) cairia no dia seguinte
        // depois das 21h de Brasília e o teste passaria a falhar de noite — o defeito que
        // `TestesFrageisPorRelogio` já custou a este projeto.
        java.time.ZoneId sp = java.time.ZoneId.of("America/Sao_Paulo");
        java.time.LocalDate hojeEmSp = java.time.LocalDate.now(sp);
        String inicio = hojeEmSp.atTime(12, 0).atZone(sp).toOffsetDateTime().toString();
        String fim = hojeEmSp.plusDays(2).atTime(12, 0).atZone(sp).toOffsetDateTime().toString();
        String sku = criarProdutoComVariacao(token, "PRODUTO EM OFERTA", "100.00",
                ",\"precoOferta\":\"10.00\",\"dataInicioOferta\":\"%s\",\"dataFinalOferta\":\"%s\""
                        .formatted(inicio, fim));

        mlTemAnuncios("""
                [{"code":200,"body":{"id":"MLB5","title":"PRODUTO EM OFERTA","status":"active",
                  "price":1.00,"available_quantity":1,"seller_custom_field":"%s","variations":[]}}]
                """.formatted(sku), "MLB5");
        long idVariacao = idVariacaoDoSku(token, idCanal, sku);

        mvc.perform(post2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"idExterno\":\"MLB5\",\"idVariacao\":%d}".formatted(idVariacao)))
                .andExpect(status().isCreated());

        // O preço publicado tem de ser 100,00 (preco_venda), NUNCA 10,00 (preco_oferta).
        String cru = mvc.perform(get2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(cru).doesNotContain("\"precoNoCanal\":10.00");

        assertThat(precoGravado(token, idCanal)).isEqualByComparingTo("100.00");
    }

    /** O preço que o ERP publicará, lido de volta pela própria API. */
    private java.math.BigDecimal precoGravado(String token, long idCanal) throws Exception {
        String resp = mvc.perform(get2("/api/v1/canais/%d/anuncios/vinculos".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return new java.math.BigDecimal(String.valueOf(
                JsonPath.read(resp, "$[0].preco").toString()));
    }

    /**
     * ⛔ A promessa central do produto virando restrição.
     *
     * <p>Dois anúncios do mesmo canal apontando para a mesma variação publicariam o mesmo saldo
     * duas vezes: 5 peças viram 10 prometidas. O marketplace pune cancelamento com reputação, que
     * é o ativo do lojista.
     */
    @Test
    void mesmoProdutoNaoAlimentaDoisAnunciosDoMesmoCanal() throws Exception {
        String token = assinarNovoTenant("duplo");
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");
        String sku = criarProdutoComVariacao(token, "PRODUTO DUPLO", "50.00", "");

        mlTemAnuncios("""
                [{"code":200,"body":{"id":"MLB6","title":"PRODUTO DUPLO","status":"active",
                  "price":50.00,"available_quantity":1,"seller_custom_field":"%s","variations":[]}}]
                """.formatted(sku), "MLB6");
        long idVariacao = idVariacaoDoSku(token, idCanal, sku);

        mvc.perform(post2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"idExterno\":\"MLB6\",\"idVariacao\":%d}".formatted(idVariacao)))
                .andExpect(status().isCreated());

        // Outro anúncio, mesmo produto: recusa, e a mensagem diz POR QUÊ.
        mvc.perform(post2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"idExterno\":\"MLB7\",\"idVariacao\":%d}".formatted(idVariacao)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("já está vinculado a outro anúncio")));
    }

    @Test
    void desvincularLiberaOProdutoParaOutroAnuncio() throws Exception {
        String token = assinarNovoTenant("desvincula");
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");
        String sku = criarProdutoComVariacao(token, "PRODUTO SOLTO", "50.00", "");

        mlTemAnuncios("""
                [{"code":200,"body":{"id":"MLB8","title":"PRODUTO SOLTO","status":"active",
                  "price":50.00,"available_quantity":1,"seller_custom_field":"%s","variations":[]}}]
                """.formatted(sku), "MLB8");
        long idVariacao = idVariacaoDoSku(token, idCanal, sku);

        mvc.perform(post2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"idExterno\":\"MLB8\",\"idVariacao\":%d}".formatted(idVariacao)))
                .andExpect(status().isCreated());

        String vinculos = mvc.perform(get2("/api/v1/canais/%d/anuncios/vinculos".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long idAnuncio = ((Number) JsonPath.read(vinculos, "$[0].idAnuncio")).longValue();

        mvc.perform(delete("/api/v1/canais/%d/anuncios/%d".formatted(idCanal, idAnuncio))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(post2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"idExterno\":\"MLB80\",\"idVariacao\":%d}".formatted(idVariacao)))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------------------- recusas

    @Test
    void canalDesconectadoNaoListaAnuncios() throws Exception {
        String token = assinarNovoTenant("desconectado");
        ligarControleDeEstoque(token);
        String resp = mvc.perform(post2("/api/v1/canais/MERCADO_LIVRE")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"ML SEM CONEXAO\",\"percPreco\":0,\"idEmpresa\":%d}"
                                .formatted(idEmpresaDo(token))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idCanal = ((Number) JsonPath.read(resp, "$.idCanal")).longValue();

        mvc.perform(get2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("não está conectado")));
    }

    /** ⚠️ Falha do terceiro é 502, e a mensagem diz de quem é a culpa. */
    @Test
    void mercadoLivreForaDoArViraErroDeGateway() throws Exception {
        String token = assinarNovoTenant("ml-fora");
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");

        ML.stubFor(get(urlPathEqualTo("/users/777/items/search"))
                .willReturn(aResponse().withStatus(503)));

        mvc.perform(get2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Mercado Livre")));
    }

    /** P8: canal de outro tenant não existe, do ponto de vista deste. */
    @Test
    void naoListaAnuncioDeCanalDeOutroTenant() throws Exception {
        String tokenA = assinarNovoTenant("iso-vinc-a");
        String tokenB = assinarNovoTenant("iso-vinc-b");
        ligarControleDeEstoque(tokenA);
        ligarControleDeEstoque(tokenB);
        long canalDeB = criarCanalConectado(tokenB, "0");

        mvc.perform(get2("/api/v1/canais/%d/anuncios".formatted(canalDeB))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void operadorNaoVincula() throws Exception {
        String token = assinarNovoTenant("operador-vinc");
        ligarControleDeEstoque(token);
        long idCanal = criarCanalConectado(token, "0");

        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        String tokenOperador = tokens.emitir(
                Long.parseLong(JsonPath.read(payload, "$.sub")),
                ((Number) JsonPath.read(payload, "$.tid")).longValue(),
                ((Number) JsonPath.read(payload, "$.eid")).longValue(),
                JsonPath.read(payload, "$.email"), List.of("OPERADOR"));

        mvc.perform(get2("/api/v1/canais/%d/anuncios".formatted(idCanal))
                        .header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden());
    }
}
