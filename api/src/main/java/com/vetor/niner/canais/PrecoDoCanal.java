package com.vetor.niner.canais;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Como o preço de um anúncio nasce a partir do preço da loja (decisão do dono do produto,
 * 2026-08-25: <i>"às vezes o usuário pode ter um preço diferente para o marketplace, que pode ser
 * maior que o preço de venda na loja física, ou menor"</i>).
 *
 * <p>Ver {@code docs/MODULOMARKETPLACE.md} §8.4. Duas peças:
 *
 * <ul>
 *   <li>{@code canal.perc_preco} — a <b>regra</b> do canal, o gerador. Aceita negativo.</li>
 *   <li>{@code anuncio.preco_manual} — a marca de que o lojista <b>digitou</b> aquele preço, e
 *       portanto reajuste na loja não o sobrescreve.</li>
 * </ul>
 *
 * <p>⚠️ <b>A marca não é enfeite.</b> Sem ela, o dia em que o lojista reajusta a tabela da loja é
 * o dia em que todo preço que ele ajustou à mão no marketplace some, sem aviso. É a lição de
 * {@code feedback_efeito_derivado_congela_valor_parcial}, deste próprio projeto: guardar por
 * <i>"o usuário editou"</i>, nunca por <i>"eu já calculei"</i>.
 */
public final class PrecoDoCanal {

    private PrecoDoCanal() {
    }

    /**
     * Preço a publicar no canal.
     *
     * <p>⛔ <b>{@code precoDeVendaDaLoja} é {@code produto.preco_venda} — NUNCA
     * {@code produto.preco_oferta}.</b> Decisão do dono do produto em 2026-08-25: <i>"preço em
     * oferta não acompanha"</i>. Promoção de fim de semana da loja física <b>não</b> derruba o
     * preço do anúncio no marketplace.
     *
     * <p>⚠️ Está escrito aqui em maiúsculas porque, sem isto, a regra seria "correta por
     * acidente" — resultado de qual coluna alguém escolheu ler, e não de uma decisão. Um
     * desenvolvedor futuro olhando `produto` veria um preço de oferta vigente sendo ignorado e
     * "consertaria", derrubando o preço de todos os anúncios do lojista numa promoção que ele
     * quis fazer só no balcão. É a mesma família de
     * {@code feedback_constante_literal_onde_ha_campo_de_dominio}: correto até alguém melhorar.
     *
     * @param precoDeVendaDaLoja {@code produto.preco_venda} — ver o aviso acima
     * @param percentual   {@code canal.perc_preco} — positivo sobe, negativo desce, zero repete
     * @return o preço derivado, com <b>2 casas</b> e arredondamento {@link RoundingMode#HALF_UP}
     *
     * <p>⚠️ Duas decisões de arredondamento que só aparecem em dinheiro (P7):
     * <ol>
     *   <li>{@code HALF_UP} é o arredondamento comercial que o resto do produto usa. Trocá-lo por
     *       {@code HALF_EVEN} aqui faria o preço do ML divergir do da loja por um centavo em
     *       metade dos casos de empate — divergência que ninguém consegue explicar ao lojista.</li>
     *   <li>Arredonda <b>uma vez, no fim</b>. Arredondar o fator antes de multiplicar espalha o
     *       erro proporcionalmente ao preço, e some num produto de R$ 10 para aparecer num de
     *       R$ 800.</li>
     * </ol>
     */
    public static BigDecimal derivar(BigDecimal precoDeVendaDaLoja, BigDecimal percentual) {
        if (precoDeVendaDaLoja == null) {
            return null;
        }
        BigDecimal perc = percentual == null ? BigDecimal.ZERO : percentual;
        BigDecimal fator = BigDecimal.ONE.add(perc.movePointLeft(2));
        return precoDeVendaDaLoja.multiply(fator).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * O preço deste anúncio deve acompanhar um reajuste na loja?
     *
     * <p>Existe como método, e não como {@code if} espalhado, porque a pergunta vai ser feita em
     * três lugares (ao vincular o anúncio, ao reajustar preço na loja e no painel de saúde) e
     * responder diferente em um deles é o defeito.
     */
    public static boolean acompanhaLoja(boolean precoManual) {
        return !precoManual;
    }

    /**
     * O preço digitado à mão ficou defasado a ponto de merecer aviso?
     *
     * <p>⚠️ Preço manual <b>não</b> é sobrescrito — mas também não pode envelhecer em silêncio. Se
     * a loja reajustou 30% e o anúncio ficou com o preço antigo, o lojista pode estar vendendo
     * <b>abaixo do custo</b> no marketplace sem saber. O painel de saúde (R7) avisa; ninguém
     * muda o preço por ele.
     *
     * @param precoDeVendaDaLoja {@code produto.preco_venda} — pelo mesmo motivo de
     *        {@link #derivar}: <b>nunca</b> {@code preco_oferta}. Comparar contra o preço
     *        promocional acusaria "defasado" em toda promoção da loja física e treinaria o lojista
     *        a ignorar o aviso — que é o pior desfecho possível para um alerta.
     * @param tolerancia diferença percentual aceitável antes de avisar
     */
    public static boolean defasado(BigDecimal precoPublicado, BigDecimal precoDeVendaDaLoja,
                                   BigDecimal percentualDoCanal, BigDecimal tolerancia) {
        BigDecimal esperado = derivar(precoDeVendaDaLoja, percentualDoCanal);
        if (esperado == null || precoPublicado == null || esperado.signum() == 0) {
            return false;
        }
        BigDecimal diferenca = precoPublicado.subtract(esperado).abs()
                .divide(esperado, 4, RoundingMode.HALF_UP)
                .movePointRight(2);
        return diferenca.compareTo(tolerancia) > 0;
    }
}
