package com.vetor.niner.integracao.mercadolivre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vetor.niner.canais.CanalDeVenda;
import com.vetor.niner.canais.CredenciaisCanal;
import com.vetor.niner.canais.TipoCanal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter do <b>Mercado Livre</b> — a tradução entre o vocabulário do domínio
 * ({@link CanalDeVenda}) e o do ML. O domínio nunca vê um {@code JsonNode} daqui.
 *
 * <p>⚠️ <b>Nenhum método desta classe é chamado dentro de uma requisição de usuário.</b> Quem a
 * chama é o worker do outbox (P2), que já estabeleceu o {@code TenantContext} (P8).
 */
public class MercadoLivreAdapter implements CanalDeVenda {

    /** Página do ML é por {@code offset}/{@code limit}, e o teto de {@code limit} é 50. */
    private static final int LIMITE_MAXIMO = 50;

    private final MercadoLivreApi api;
    private final ObjectMapper json;

    public MercadoLivreAdapter(MercadoLivreApi api, ObjectMapper json) {
        this.api = api;
        this.json = json;
    }

    @Override
    public TipoCanal tipo() {
        return TipoCanal.MERCADO_LIVRE;
    }

    // ------------------------------------------------------------------ leitura (R6)

    @Override
    public List<AnuncioDoCanal> listarAnuncios(CredenciaisCanal credenciais, int pagina, int limite) {
        int tamanho = Math.min(Math.max(limite, 1), LIMITE_MAXIMO);
        int offset = Math.max(pagina - 1, 0) * tamanho;

        JsonNode busca = api.get("/users/%s/items/search?offset=%d&limit=%d"
                .formatted(credenciais.contaExterna(), offset, tamanho), credenciais.accessToken());

        List<String> ids = new ArrayList<>();
        for (JsonNode id : busca.path("results")) {
            ids.add(id.asText());
        }
        if (ids.isEmpty()) {
            return List.of();
        }

        // O ML devolve só os IDs na busca; os detalhes vêm de /items?ids=... em lote. Uma chamada
        // por anúncio queimaria o limite de 1.500/min à toa numa loja com centenas de itens.
        JsonNode lote = api.get("/items?ids=" + String.join(",", ids), credenciais.accessToken());

        List<AnuncioDoCanal> anuncios = new ArrayList<>();
        for (JsonNode envelope : lote) {
            // O endpoint em lote embrulha cada item em {code, body} — um item que falha não
            // derruba os outros, e ignorá-lo é melhor que perder a página inteira.
            JsonNode corpo = envelope.has("body") ? envelope.get("body") : envelope;
            if (envelope.path("code").isInt() && envelope.path("code").asInt() != 200) {
                continue;
            }
            anuncios.add(new AnuncioDoCanal(
                    corpo.path("id").asText(),
                    corpo.path("title").asText(),
                    textoOuNulo(corpo.path("seller_custom_field")),
                    corpo.path("price").isNumber() ? corpo.path("price").decimalValue() : null,
                    corpo.path("available_quantity").asInt(0),
                    corpo.path("status").asText(),
                    corpo.path("variations").isArray() && !corpo.path("variations").isEmpty()));
        }
        return anuncios;
    }

    // ------------------------------------------------------------------ escrita (R3)

    /**
     * {@inheritDoc}
     *
     * <h2>⛔ A armadilha das variações</h2>
     *
     * No Mercado Livre, um {@code PUT} em {@code variations} <b>apaga as variações que não forem
     * enviadas</b>. Mandar só o tamanho que mudou faz o anúncio perder os outros onze — sem erro,
     * e desfazer é republicar à mão.
     *
     * <p>Por isso este método <b>lê o anúncio antes de escrever</b> e monta o corpo com
     * <b>todas</b> as variações que existem lá, trocando a quantidade só nas que o domínio
     * mandou. As demais são reenviadas com o valor que já tinham.
     *
     * <p>⚠️ Ler antes de escrever custa uma chamada a mais por sincronização. É deliberado: o
     * limite do ML é 1.500/min por vendedor, folgado para uma loja pequena, e o preço de errar é
     * perda de dado no anúncio do lojista — assimetria que não deixa dúvida sobre qual lado errar.
     */
    @Override
    public void atualizarEstoque(CredenciaisCanal credenciais, String idExternoItem,
                                 List<SaldoAnuncio> saldos) {
        if (saldos == null || saldos.isEmpty()) {
            return;
        }
        JsonNode item = api.get("/items/" + idExternoItem, credenciais.accessToken());
        boolean temVariacoes = item.path("variations").isArray() && !item.path("variations").isEmpty();

        ObjectNode corpo = json.createObjectNode();

        if (!temVariacoes) {
            // Anúncio simples: um saldo só. Se o domínio mandou mais de um, é erro de vínculo —
            // some as quantidades seria inventar um número que ninguém pediu.
            if (saldos.size() > 1) {
                throw new IllegalArgumentException(
                        "Anúncio %s não tem variações no Mercado Livre, mas o ERP mandou %d saldos."
                                .formatted(idExternoItem, saldos.size()));
            }
            corpo.put("available_quantity", saldos.getFirst().quantidade());
            api.put("/items/" + idExternoItem, credenciais.accessToken(), corpo.toString());
            return;
        }

        Map<String, Integer> novosPorVariacao = new LinkedHashMap<>();
        for (SaldoAnuncio saldo : saldos) {
            if (saldo.idExternoVariacao() != null) {
                novosPorVariacao.put(saldo.idExternoVariacao(), saldo.quantidade());
            }
        }

        ArrayNode variacoes = corpo.putArray("variations");
        for (JsonNode variacaoNoCanal : item.path("variations")) {
            String idVariacao = variacaoNoCanal.path("id").asText();
            ObjectNode saida = variacoes.addObject();
            // ⛔ O `id` é o que impede o ML de APAGAR esta variação. Nunca omitir.
            saida.put("id", idVariacao);
            saida.put("available_quantity", novosPorVariacao.containsKey(idVariacao)
                    ? novosPorVariacao.get(idVariacao)
                    // Variação que o ERP não citou volta com o valor que já tinha — reenviar o
                    // que existe é o que a mantém viva.
                    : variacaoNoCanal.path("available_quantity").asInt(0));
        }

        api.put("/items/" + idExternoItem, credenciais.accessToken(), corpo.toString());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mesma armadilha do estoque quando o preço é de uma variação: o corpo leva todas as
     * variações, com o preço trocado só na que interessa.
     */
    @Override
    public void atualizarPreco(CredenciaisCanal credenciais, String idExternoItem,
                               String idExternoVariacao, BigDecimal preco) {
        if (preco == null) {
            throw new IllegalArgumentException(
                    "Preço nulo para o anúncio " + idExternoItem + " — publicar zero é pior que não publicar.");
        }
        ObjectNode corpo = json.createObjectNode();

        if (idExternoVariacao == null) {
            corpo.put("price", preco);
            api.put("/items/" + idExternoItem, credenciais.accessToken(), corpo.toString());
            return;
        }

        JsonNode item = api.get("/items/" + idExternoItem, credenciais.accessToken());
        ArrayNode variacoes = corpo.putArray("variations");
        for (JsonNode variacaoNoCanal : item.path("variations")) {
            String idVariacao = variacaoNoCanal.path("id").asText();
            ObjectNode saida = variacoes.addObject();
            saida.put("id", idVariacao);
            if (idVariacao.equals(idExternoVariacao)) {
                saida.put("price", preco);
            } else if (variacaoNoCanal.path("price").isNumber()) {
                saida.put("price", variacaoNoCanal.path("price").decimalValue());
            }
        }
        api.put("/items/" + idExternoItem, credenciais.accessToken(), corpo.toString());
    }

    private static String textoOuNulo(JsonNode no) {
        return no == null || no.isNull() || no.isMissingNode() || no.asText().isBlank()
                ? null
                : no.asText();
    }
}
