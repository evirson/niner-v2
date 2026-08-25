package com.vetor.niner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.vetor.niner.canais.CanalDeVenda.AnuncioDoCanal;
import com.vetor.niner.canais.CanalDeVenda.CanalIndisponivelException;
import com.vetor.niner.canais.CanalDeVenda.SaldoAnuncio;
import com.vetor.niner.canais.CredenciaisCanal;
import com.vetor.niner.canais.TipoCanal;
import com.vetor.niner.integracao.mercadolivre.MercadoLivreAdapter;
import com.vetor.niner.integracao.mercadolivre.MercadoLivreApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapter do Mercado Livre contra WireMock (spec §Fase 2).
 *
 * <p>⚠️ <b>Por que WireMock e não um mock de interface.</b> O ML não tem sandbox: os testes reais
 * rodam na produção deles, com usuários de teste que são no máximo 10 e expiram. E o defeito mais
 * caro deste adapter é de <b>payload</b> — mandar um PUT de variações sem todos os ids APAGA as
 * variações omitidas no anúncio do lojista. Mockar a interface do transporte passaria por esse
 * defeito sem ver; só afirmando o corpo que sai é que ele fica preso.
 *
 * <p>Sem Spring de propósito: o que se testa aqui é tradução e HTTP, não fiação.
 */
class MercadoLivreAdapterTest {

    private WireMockServer ml;
    private MercadoLivreAdapter adapter;
    private final ObjectMapper json = new ObjectMapper();

    private static final CredenciaisCanal CRED =
            new CredenciaisCanal(1L, "SELLER123", "token-de-teste", "refresh", Instant.MAX);

    @BeforeEach
    void subirMlFalso() {
        ml = new WireMockServer(options().dynamicPort());
        ml.start();
        adapter = new MercadoLivreAdapter(new MercadoLivreApi(ml.baseUrl(), json), json);
    }

    @AfterEach
    void derrubar() {
        ml.stop();
    }

    private JsonNode corpoDoPut(String url) throws Exception {
        var enviados = ml.findAll(putRequestedFor(urlEqualTo(url)));
        assertThat(enviados).as("nenhum PUT chegou em " + url).isNotEmpty();
        return json.readTree(enviados.getFirst().getBodyAsString());
    }

    @Test
    void identificaSeuCanal() {
        assertThat(adapter.tipo()).isEqualTo(TipoCanal.MERCADO_LIVRE);
    }

    // =================================================================== a armadilha das variações

    /**
     * ⛔ <b>O teste mais importante deste arquivo.</b> Atualizar o saldo de UMA variação tem de
     * reenviar TODAS — as omitidas seriam apagadas pelo Mercado Livre, sem erro nenhum, e desfazer
     * é republicar o anúncio à mão.
     */
    @Test
    void atualizarEstoqueDeUmaVariacaoReenviaTodasAsOutras() throws Exception {
        ml.stubFor(get(urlEqualTo("/items/MLB123")).willReturn(okJson("""
                {"id":"MLB123","variations":[
                  {"id":"1001","available_quantity":5},
                  {"id":"1002","available_quantity":7},
                  {"id":"1003","available_quantity":9}]}
                """)));
        ml.stubFor(put(urlEqualTo("/items/MLB123")).willReturn(okJson("{\"id\":\"MLB123\"}")));

        // O domínio mexeu só na variação do meio.
        adapter.atualizarEstoque(CRED, "MLB123",
                List.of(new SaldoAnuncio(77L, "MLB123", "1002", 3)));

        JsonNode enviado = corpoDoPut("/items/MLB123");
        JsonNode variacoes = enviado.get("variations");

        assertThat(variacoes).as("as TRÊS variações têm de ir no corpo, não só a que mudou").hasSize(3);
        assertThat(variacoes.get(0).get("id").asText()).isEqualTo("1001");
        assertThat(variacoes.get(0).get("available_quantity").asInt()).as("intocada").isEqualTo(5);
        assertThat(variacoes.get(1).get("id").asText()).isEqualTo("1002");
        assertThat(variacoes.get(1).get("available_quantity").asInt()).as("a que mudou").isEqualTo(3);
        assertThat(variacoes.get(2).get("id").asText()).isEqualTo("1003");
        assertThat(variacoes.get(2).get("available_quantity").asInt()).as("intocada").isEqualTo(9);
    }

    /** Toda variação enviada leva `id` — é ele que impede o ML de apagá-la. */
    @Test
    void todaVariacaoEnviadaLevaId() throws Exception {
        ml.stubFor(get(urlEqualTo("/items/MLB123")).willReturn(okJson("""
                {"id":"MLB123","variations":[{"id":"1001","available_quantity":5},
                                             {"id":"1002","available_quantity":7}]}
                """)));
        ml.stubFor(put(urlEqualTo("/items/MLB123")).willReturn(okJson("{}")));

        adapter.atualizarEstoque(CRED, "MLB123", List.of(new SaldoAnuncio(1L, "MLB123", "1001", 0)));

        for (JsonNode v : corpoDoPut("/items/MLB123").get("variations")) {
            assertThat(v.has("id")).as("variação sem id seria APAGADA pelo ML").isTrue();
        }
    }

    /** Ler antes de escrever é obrigatório — é o GET que revela as outras variações. */
    @Test
    void leOAnuncioAntesDeEscrever() {
        ml.stubFor(get(urlEqualTo("/items/MLB123")).willReturn(okJson("""
                {"id":"MLB123","variations":[{"id":"1001","available_quantity":5}]}
                """)));
        ml.stubFor(put(urlEqualTo("/items/MLB123")).willReturn(okJson("{}")));

        adapter.atualizarEstoque(CRED, "MLB123", List.of(new SaldoAnuncio(1L, "MLB123", "1001", 2)));

        ml.verify(1, getRequestedFor(urlEqualTo("/items/MLB123")));
    }

    @Test
    void anuncioSemVariacaoMandaApenasAQuantidade() throws Exception {
        ml.stubFor(get(urlEqualTo("/items/MLB999")).willReturn(okJson(
                "{\"id\":\"MLB999\",\"available_quantity\":4}")));
        ml.stubFor(put(urlEqualTo("/items/MLB999")).willReturn(okJson("{}")));

        adapter.atualizarEstoque(CRED, "MLB999", List.of(new SaldoAnuncio(1L, "MLB999", null, 12)));

        JsonNode enviado = corpoDoPut("/items/MLB999");
        assertThat(enviado.get("available_quantity").asInt()).isEqualTo(12);
        assertThat(enviado.has("variations")).as("não inventar variação onde não há").isFalse();
    }

    /** Vínculo errado é erro de dado; somar os saldos inventaria um número que ninguém pediu. */
    @Test
    void anuncioSemVariacaoComVariosSaldosERecusado() {
        ml.stubFor(get(urlEqualTo("/items/MLB999")).willReturn(okJson("{\"id\":\"MLB999\"}")));

        assertThatThrownBy(() -> adapter.atualizarEstoque(CRED, "MLB999", List.of(
                new SaldoAnuncio(1L, "MLB999", null, 3),
                new SaldoAnuncio(2L, "MLB999", null, 4))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não tem variações");
    }

    @Test
    void semSaldoNaoChamaOCanal() {
        adapter.atualizarEstoque(CRED, "MLB123", List.of());
        ml.verify(0, getRequestedFor(urlPathMatching("/items/.*")));
    }

    // =================================================================== credencial e falhas

    @Test
    void mandaOTokenNoCabecalho() {
        ml.stubFor(get(urlEqualTo("/items/MLB1")).willReturn(okJson("{\"id\":\"MLB1\"}")));
        ml.stubFor(put(urlEqualTo("/items/MLB1")).willReturn(okJson("{}")));

        adapter.atualizarEstoque(CRED, "MLB1", List.of(new SaldoAnuncio(1L, "MLB1", null, 1)));

        ml.verify(getRequestedFor(urlEqualTo("/items/MLB1"))
                .withHeader("Authorization", equalTo("Bearer token-de-teste")));
    }

    /**
     * ⚠️ O 429 do ML vem com <b>corpo vazio</b> (limite de 1.500/min por vendedor). Tem de virar
     * falha <b>transitória</b>, para o outbox reagendar — não dead-letter.
     */
    @Test
    void limiteDeRequisicaoViraFalhaTransitoria() {
        ml.stubFor(get(urlEqualTo("/items/MLB1"))
                .willReturn(aResponse().withStatus(429).withBody("")));

        assertThatThrownBy(() -> adapter.atualizarEstoque(CRED, "MLB1",
                List.of(new SaldoAnuncio(1L, "MLB1", null, 1))))
                .isInstanceOf(CanalIndisponivelException.class)
                .hasMessageContaining("429");
    }

    @Test
    void erro5xxViraFalhaTransitoria() {
        ml.stubFor(get(urlEqualTo("/items/MLB1")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> adapter.atualizarEstoque(CRED, "MLB1",
                List.of(new SaldoAnuncio(1L, "MLB1", null, 1))))
                .isInstanceOf(CanalIndisponivelException.class)
                .hasMessageContaining("indisponível");
    }

    /** 4xx de negócio NÃO é transitório: repetir mil vezes esconde a causa atrás das tentativas. */
    @Test
    void erroDeNegocioNaoViraFalhaTransitoria() {
        ml.stubFor(get(urlEqualTo("/items/MLB1")).willReturn(aResponse()
                .withStatus(400).withBody("{\"message\":\"item.attributes.invalid\"}")));

        assertThatThrownBy(() -> adapter.atualizarEstoque(CRED, "MLB1",
                List.of(new SaldoAnuncio(1L, "MLB1", null, 1))))
                .isInstanceOf(MercadoLivreApi.RespostaDeErroException.class)
                .isNotInstanceOf(CanalIndisponivelException.class);
    }

    // =================================================================== leitura de anúncios (R6)

    @Test
    void listarAnunciosBuscaIdsEDepoisOsDetalhesEmLote() {
        ml.stubFor(get(urlEqualTo("/users/SELLER123/items/search?offset=0&limit=50"))
                .willReturn(okJson("{\"results\":[\"MLB1\",\"MLB2\"]}")));
        ml.stubFor(get(urlEqualTo("/items?ids=MLB1,MLB2")).willReturn(okJson("""
                [{"code":200,"body":{"id":"MLB1","title":"CAMISETA AZUL","seller_custom_field":"SKU-1",
                                     "price":79.90,"available_quantity":4,"status":"active","variations":[]}},
                 {"code":200,"body":{"id":"MLB2","title":"TENIS","seller_custom_field":null,
                                     "price":249.00,"available_quantity":2,"status":"paused",
                                     "variations":[{"id":"9"}]}}]
                """)));

        List<AnuncioDoCanal> anuncios = adapter.listarAnuncios(CRED, 1, 50);

        assertThat(anuncios).hasSize(2);
        assertThat(anuncios.getFirst().idExterno()).isEqualTo("MLB1");
        assertThat(anuncios.getFirst().titulo()).isEqualTo("CAMISETA AZUL");
        assertThat(anuncios.getFirst().sku()).isEqualTo("SKU-1");
        assertThat(anuncios.getFirst().preco()).isEqualByComparingTo("79.90");
        assertThat(anuncios.getFirst().temVariacoes()).isFalse();
        assertThat(anuncios.get(1).sku()).as("sem SKU no canal é null, não string vazia").isNull();
        assertThat(anuncios.get(1).temVariacoes()).isTrue();
    }

    /** Um item que falha no lote não pode derrubar a página inteira. */
    @Test
    void itemComErroNoLoteEIgnoradoSemPerderOsOutros() {
        ml.stubFor(get(urlEqualTo("/users/SELLER123/items/search?offset=0&limit=50"))
                .willReturn(okJson("{\"results\":[\"MLB1\",\"MLBQUEBRADO\"]}")));
        ml.stubFor(get(urlEqualTo("/items?ids=MLB1,MLBQUEBRADO")).willReturn(okJson("""
                [{"code":200,"body":{"id":"MLB1","title":"OK","price":10.00,
                                     "available_quantity":1,"status":"active","variations":[]}},
                 {"code":404,"body":{"message":"Item not found"}}]
                """)));

        assertThat(adapter.listarAnuncios(CRED, 1, 50)).hasSize(1);
    }

    @Test
    void paginaVaziaNaoChamaOEndpointDeLote() {
        ml.stubFor(get(urlEqualTo("/users/SELLER123/items/search?offset=0&limit=50"))
                .willReturn(okJson("{\"results\":[]}")));

        assertThat(adapter.listarAnuncios(CRED, 1, 50)).isEmpty();
        ml.verify(0, getRequestedFor(urlPathEqualTo("/items")));
    }

    /** Paginação é offset/limit, e o ML tem teto de 50 por página. */
    @Test
    void traduzPaginaParaOffsetERespeitaOTeto() {
        ml.stubFor(get(urlPathEqualTo("/users/SELLER123/items/search"))
                .willReturn(okJson("{\"results\":[]}")));

        adapter.listarAnuncios(CRED, 3, 500);

        ml.verify(getRequestedFor(urlPathEqualTo("/users/SELLER123/items/search"))
                .withQueryParam("limit", equalTo("50"))
                .withQueryParam("offset", equalTo("100")));
    }

    // =================================================================== preço

    @Test
    void precoDeAnuncioSemVariacaoVaiDireto() throws Exception {
        ml.stubFor(put(urlEqualTo("/items/MLB1")).willReturn(okJson("{}")));

        adapter.atualizarPreco(CRED, "MLB1", null, new BigDecimal("118.00"));

        assertThat(corpoDoPut("/items/MLB1").get("price").decimalValue())
                .isEqualByComparingTo("118.00");
        ml.verify(0, getRequestedFor(urlEqualTo("/items/MLB1")));
    }

    /** Preço de variação sofre a MESMA armadilha: o corpo leva todas. */
    @Test
    void precoDeVariacaoReenviaTodasAsVariacoes() throws Exception {
        ml.stubFor(get(urlEqualTo("/items/MLB1")).willReturn(okJson("""
                {"id":"MLB1","variations":[{"id":"1001","price":10.00},{"id":"1002","price":20.00}]}
                """)));
        ml.stubFor(put(urlEqualTo("/items/MLB1")).willReturn(okJson("{}")));

        adapter.atualizarPreco(CRED, "MLB1", "1002", new BigDecimal("25.50"));

        JsonNode variacoes = corpoDoPut("/items/MLB1").get("variations");
        assertThat(variacoes).hasSize(2);
        assertThat(variacoes.get(0).get("price").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(variacoes.get(1).get("price").decimalValue()).isEqualByComparingTo("25.50");
    }

    @Test
    void precoNuloERecusadoAntesDeChamarOCanal() {
        assertThatThrownBy(() -> adapter.atualizarPreco(CRED, "MLB1", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        ml.verify(0, putRequestedFor(urlPathMatching("/items/.*")));
    }
}
