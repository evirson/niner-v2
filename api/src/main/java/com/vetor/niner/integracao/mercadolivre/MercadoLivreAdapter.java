package com.vetor.niner.integracao.mercadolivre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vetor.niner.canais.CanalDeVenda;
import com.vetor.niner.canais.CredenciaisCanal;
import com.vetor.niner.canais.TipoCanal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(MercadoLivreAdapter.class);

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
                    lerVariacoes(corpo)));
        }
        return anuncios;
    }

    /**
     * As variações do anúncio, do jeito que a tela de vínculo (R6) precisa.
     *
     * <p>⚠️ O ML descreve a variação em {@code attribute_combinations} (uma lista de
     * {name, value_name}), não num rótulo pronto. Montamos <b>"Cor: Azul · Tamanho: M"</b> porque
     * é assim que o lojista reconhece a variação na tela dele — mostrar o id cru
     * ({@code 179553826123}) obrigaria a abrir o Mercado Livre em outra aba para saber o que se
     * está vinculando, que é justamente o trabalho que esta tela existe para evitar.
     */
    private static List<VariacaoDoCanal> lerVariacoes(JsonNode corpo) {
        JsonNode variacoes = corpo.path("variations");
        if (!variacoes.isArray() || variacoes.isEmpty()) {
            return List.of();
        }
        List<VariacaoDoCanal> lidas = new ArrayList<>();
        for (JsonNode v : variacoes) {
            StringBuilder descricao = new StringBuilder();
            for (JsonNode atributo : v.path("attribute_combinations")) {
                if (!descricao.isEmpty()) {
                    descricao.append(" · ");
                }
                descricao.append(atributo.path("name").asText())
                        .append(": ")
                        .append(atributo.path("value_name").asText());
            }
            lidas.add(new VariacaoDoCanal(
                    v.path("id").asText(),
                    // Variação sem atributo nenhum não deveria existir, mas linha em branco na
                    // tela é pior que o id cru — que ao menos casa com o painel do ML.
                    descricao.isEmpty() ? v.path("id").asText() : descricao.toString(),
                    textoOuNulo(v.path("seller_custom_field")),
                    v.path("price").isNumber() ? v.path("price").decimalValue() : null,
                    v.path("available_quantity").asInt(0)));
        }
        return lidas;
    }

    // ------------------------------------------------------------------ pedidos (R5)

    /**
     * {@inheritDoc}
     *
     * <h2>⚠️ Um pedido custa DUAS chamadas</h2>
     *
     * O formato novo de {@code /orders} (que o cabeçalho {@code x-format-new: true} pede, e que
     * {@link MercadoLivreApi} manda sempre) <b>não traz mais os dados de envio</b> — eles saem de
     * {@code /shipments/{id}}. O adapter junta as duas antes de entregar ao domínio, porque
     * "pedido pela metade" não é um conceito que o ERP deva conhecer.
     *
     * <p>⚠️ E a segunda chamada é <b>tolerante</b>: pedido sem envio existe (retirada, digital, ou
     * simplesmente ainda não despachado). Falhar o pedido inteiro porque o envio não respondeu
     * seria perder a venda por causa do acessório.
     */
    @Override
    public PedidoDoCanal buscarPedido(CredenciaisCanal credenciais, String idExterno) {
        JsonNode pedido = api.get("/orders/" + idExterno, credenciais.accessToken());

        List<ItemDoPedido> itens = new ArrayList<>();
        for (JsonNode linha : pedido.path("order_items")) {
            JsonNode item = linha.path("item");
            itens.add(new ItemDoPedido(
                    item.path("id").asText(),
                    textoOuNulo(item.path("variation_id")),
                    item.path("title").asText(),
                    new BigDecimal(linha.path("quantity").asText("0")),
                    linha.path("unit_price").isNumber()
                            ? linha.path("unit_price").decimalValue()
                            : BigDecimal.ZERO));
        }

        String idEnvio = textoOuNulo(pedido.path("shipping").path("id"));
        BigDecimal frete = BigDecimal.ZERO;
        if (idEnvio != null) {
            try {
                JsonNode envio = api.get("/shipments/" + idEnvio, credenciais.accessToken());
                if (envio.path("shipping_option").path("cost").isNumber()) {
                    frete = envio.path("shipping_option").path("cost").decimalValue();
                }
            } catch (RuntimeException e) {
                // Ver a nota do método: o frete é acessório, o pedido não.
                log.warn("Envio {} não pôde ser lido; pedido {} entra sem frete: {}",
                        idEnvio, idExterno, e.getMessage());
            }
        }

        return new PedidoDoCanal(
                pedido.path("id").asText(),
                traduzirStatus(pedido.path("status").asText("")),
                pedido.path("total_amount").isNumber()
                        ? pedido.path("total_amount").decimalValue() : BigDecimal.ZERO,
                frete,
                textoOuNulo(pedido.path("buyer").path("nickname")),
                idEnvio,
                itens,
                pedido.toString());
    }

    @Override
    public List<String> idsDePedidosRecentes(CredenciaisCanal credenciais, int limite) {
        JsonNode busca = api.get("/orders/search?seller=%s&sort=date_desc&limit=%d"
                .formatted(credenciais.contaExterna(), Math.min(Math.max(limite, 1), LIMITE_MAXIMO)),
                credenciais.accessToken());

        List<String> ids = new ArrayList<>();
        for (JsonNode pedido : busca.path("results")) {
            ids.add(pedido.path("id").asText());
        }
        return ids;
    }

    /**
     * Vocabulário do ML → {@code status_pedido} do ERP.
     *
     * <p>⚠️ O que <b>não</b> é reconhecido cai em {@code RECEBIDO}, e é a escolha segura: um status
     * novo do marketplace (eles acrescentam) não pode fazer o pedido sumir da fila de expedição
     * nem parecer entregue. Recebido é o estado que pede atenção humana.
     */
    private static String traduzirStatus(String doMl) {
        return switch (doMl) {
            case "paid" -> "PAGO";
            case "cancelled" -> "CANCELADO";
            default -> "RECEBIDO";
        };
    }

    /**
     * {@inheritDoc}
     *
     * <h2>⛔ No Mercado Livre isto deliberadamente NÃO faz nada</h2>
     *
     * Em <b>Mercado Envios</b> — que é como a esmagadora maioria das lojas vende no ML — quem
     * controla o estado do envio é o <b>próprio marketplace</b>: o pedido vira "enviado" quando a
     * transportadora bipa a etiqueta, e a etiqueta é liberada pela nota fiscal (§2.6). Não existe
     * "avisar o ML que despachei": a informação vai no sentido contrário.
     *
     * <p>⚠️ E a etiqueta depende da <b>NF-e 55</b>, que é a <b>Opção C</b> — hoje travada no
     * {@code cStat 974}. Enquanto ela não existir, o lojista emite a nota no painel do ML e o
     * despacho acontece lá.
     *
     * <p>⭐ <b>Por que então marcar ENVIADO no ERP importa?</b> Porque a fila de expedição é da
     * <b>loja</b>: é ela que diz o que já foi separado, o que já saiu e quem despachou (P3). Esse
     * trabalho é real mesmo quando o marketplace não precisa saber dele.
     *
     * <p>⚠️ <b>Não fazer nada é melhor que inventar uma chamada.</b> A alternativa seria chutar um
     * endpoint que a documentação não descreve para este caso — e, sem sandbox no ML, o primeiro
     * teste seria em produção, no envio de um lojista de verdade.
     */
    @Override
    public void confirmarEnvio(CredenciaisCanal credenciais, String idExternoPedido,
                               String idExternoEnvio, String codigoRastreio) {
        log.debug("Pedido {} despachado no ERP. O Mercado Livre não é avisado: em Mercado Envios "
                + "quem controla o estado do envio é o próprio marketplace (envio {}).",
                idExternoPedido, idExternoEnvio);
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
