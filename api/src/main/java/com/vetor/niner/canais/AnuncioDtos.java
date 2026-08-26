package com.vetor.niner.canais;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** DTOs da tela de vínculo anúncio ↔ produto (R6, bloco M2). */
public final class AnuncioDtos {

    private AnuncioDtos() {
    }

    /** Uma variação do ERP, do jeito que o lojista a reconhece na tela. */
    public record VariacaoDoErp(long idVariacao, String sku, String descricao) {
    }

    /**
     * Uma <b>linha</b> da tela de vínculo.
     *
     * <p>⚠️ Linha ≠ anúncio. Um anúncio com variações vira <b>várias</b> linhas, uma por variação
     * do canal — porque é a variação que se vincula, e é o saldo dela que será publicado. Um
     * anúncio simples vira uma linha só, com {@code idExternoVariacao} nulo.
     *
     * @param idAnuncio          id do vínculo no ERP; {@code null} = ainda não vinculada
     * @param idVariacao         variação do ERP já vinculada; {@code null} = ainda não vinculada
     * @param idVariacaoSugerida ⭐ sugestão por casamento de SKU. <b>Sugestão, não vínculo</b>:
     *                           quem confirma é o lojista, porque um vínculo criado por
     *                           coincidência de texto publicaria saldo errado sem nada na tela
     *                           dizendo de onde veio
     */
    public record LinhaParaVincular(
            String idExterno, String idExternoVariacao, String titulo, String descricaoVariacao,
            String sku, BigDecimal precoNoCanal, int quantidadeNoCanal, String statusNoCanal,
            Long idAnuncio, Long idVariacao, String descricaoVariacaoErp,
            Long idVariacaoSugerida, String descricaoSugerida) {

        public LinhaParaVincular comSugestao(long idSugerida, String descricao) {
            return new LinhaParaVincular(idExterno, idExternoVariacao, titulo, descricaoVariacao,
                    sku, precoNoCanal, quantidadeNoCanal, statusNoCanal,
                    idAnuncio, idVariacao, descricaoVariacaoErp, idSugerida, descricao);
        }
    }

    /**
     * A página de anúncios do canal.
     *
     * <p>⚠️ Sem total de registros, ao contrário do resto do produto: a origem é o marketplace, e
     * o {@code /users/{id}/items/search} não devolve uma contagem confiável. Mostrar um total
     * inventado seria pior que não mostrar — a tela navega por "próxima/anterior".
     */
    public record AnuncioParaVincular(long idCanal, String nomeCanal, int pagina,
                                      List<LinhaParaVincular> linhas) {
    }

    /**
     * Um vínculo já gravado, lido <b>sem falar com o marketplace</b>.
     *
     * <p>⭐ Existe separado da listagem por um motivo prático: se o ML estiver fora do ar, o
     * lojista ainda precisa conseguir ver e desfazer o que vinculou. Uma tela que só sabe se
     * mostrar consultando o terceiro fica inútil exatamente no dia em que ele falha.
     *
     * @param preco       o que o ERP publica neste canal — derivado de {@code produto.preco_venda}
     *                    pela regra do canal, ou digitado pelo lojista
     * @param precoManual {@code true} = digitado, e reajuste da loja <b>não</b> o sobrescreve
     * @param ultimoErro  o que o canal respondeu na última tentativa, em texto cru. É feio de
     *                    propósito: é o que o suporte pesquisa
     */
    public record VinculoGravado(long idAnuncio, String idExterno, String idExternoVariacao,
                                 long idVariacao, String sku, String descricaoVariacaoErp,
                                 BigDecimal preco, boolean precoManual, String statusSync,
                                 String ultimoErro) {
    }

    /**
     * O pedido de vínculo.
     *
     * <p>⚠️ {@code idExternoVariacao} é <b>opcional</b> de propósito: anúncio simples não tem
     * variação, e exigir o campo tornaria impossível vincular o caso mais comum de uma loja
     * pequena.
     */
    public record VinculoRequest(
            @NotBlank @Size(max = 60) String idExterno,
            @Size(max = 60) String idExternoVariacao,
            @NotNull @Positive Long idVariacao) {
    }
}
