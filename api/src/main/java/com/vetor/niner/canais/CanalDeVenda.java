package com.vetor.niner.canais;

import java.math.BigDecimal;
import java.util.List;

/**
 * Interface comum a todo marketplace (spec §3.1, P1/P2) — a <b>anti-corruption layer</b> do
 * produto. O domínio fala nesta linguagem; quem traduz para o vocabulário do Mercado Livre, da
 * Shopee ou da Amazon é o adapter, e <b>o domínio nunca vê payload de canal</b>.
 *
 * <p>A prova desta interface não é o Mercado Livre — é a <b>segunda</b> integração (Shopee,
 * spec §Fase 3). Se acrescentar o segundo canal exigir mudar esta interface, ela estava errada.
 * Por isso ela é deliberadamente pobre: só o que <i>todo</i> marketplace faz.
 *
 * <h2>⚠️ Três regras que a implementação NÃO pode violar</h2>
 *
 * <ol>
 *   <li><b>Nenhum método aqui é chamado de dentro de uma requisição de usuário.</b> Toda escrita
 *       no canal passa pelo outbox (P2): a transação de domínio grava o evento, e um worker
 *       despacha depois, com retry e dead-letter. Chamar o marketplace no meio de uma venda
 *       amarraria o PDV à latência (e à indisponibilidade) de um terceiro.</li>
 *   <li><b>Idempotência é obrigação do chamador e do implementador.</b> Reprocessar um evento não
 *       pode duplicar anúncio, pedido nem movimento de estoque (P2).</li>
 *   <li><b>O adapter roda fora de requisição, logo fora do RLS.</b> Quem o chama estabelece o
 *       {@code TenantContext} antes (P8) — job sem contexto lê zero linha <b>em silêncio</b>,
 *       não dá erro.</li>
 * </ol>
 *
 * <h2>⚠️ Por que {@link #atualizarEstoque} recebe a lista inteira do anúncio</h2>
 *
 * Não é capricho de assinatura: no Mercado Livre, atualizar variações <b>apaga as que não forem
 * enviadas</b>. Um anúncio com 12 tamanhos onde se manda só o tamanho que mudou perde os outros
 * 11 — sem erro nenhum, e desfazer é republicar à mão. Uma assinatura que aceitasse "variação X
 * agora tem N" convidaria exatamente esse defeito em todo adapter futuro. Ver
 * {@code docs/MODULOMARKETPLACE.md} §2.4.
 */
public interface CanalDeVenda {

    /** Qual canal este adapter atende. Um adapter por valor de {@code tipo_canal}. */
    TipoCanal tipo();

    /**
     * Saldo de uma variação a publicar no canal.
     *
     * @param idVariacao      variação do ERP (chave do de-para com {@code anuncio})
     * @param idExternoItem   id do anúncio no marketplace
     * @param idExternoVariacao id da variação dentro do anúncio; {@code null} em anúncio sem
     *                          variação
     * @param quantidade      saldo <b>disponível</b> (estoque − reservado), nunca o saldo bruto
     */
    record SaldoAnuncio(long idVariacao, String idExternoItem, String idExternoVariacao,
                        int quantidade) {
    }

    /**
     * Uma variação <b>dentro</b> de um anúncio do canal (tamanho, cor…).
     *
     * <p>⚠️ Não confundir com a variação do ERP: esta é a do <i>marketplace</i>, e o trabalho do
     * R6 é justamente ligar uma na outra.
     *
     * @param idExterno  id da variação no canal — é ele que vai em {@link SaldoAnuncio}, e é o que
     *                   impede o {@code PUT} de apagar a variação (§2.4)
     * @param descricao  como o canal a descreve ("Cor: Azul, Tamanho: M"), para o lojista
     *                   reconhecer qual é na hora de vincular
     * @param sku        o SKU que o lojista digitou no canal, quando digitou. ⭐ É a pista que
     *                   permite <b>sugerir</b> o vínculo automaticamente
     */
    record VariacaoDoCanal(String idExterno, String descricao, String sku, BigDecimal preco,
                           int quantidadeDisponivel) {
    }

    /**
     * Um anúncio como o canal o descreve — o mínimo para a tela de vínculo (R6).
     *
     * <p>⚠️ {@code variacoes} vazia significa <b>anúncio simples</b> (um saldo só). Antes de
     * 2026-08-26 isto era um booleano {@code temVariacoes}, que bastava para <i>avisar</i> mas não
     * para <b>vincular</b>: o lojista precisa escolher a variação do ERP para <i>cada</i> variação
     * do canal, e um booleano não diz quais são. Numa loja de roupa — que é o alvo deste ERP,
     * com cor e grade — o anúncio com variações é a regra, não a exceção.
     */
    record AnuncioDoCanal(String idExterno, String titulo, String sku, BigDecimal preco,
                          int quantidadeDisponivel, String status,
                          List<VariacaoDoCanal> variacoes) {

        public boolean temVariacoes() {
            return variacoes != null && !variacoes.isEmpty();
        }
    }

    /**
     * Lista os anúncios da conta conectada, para o lojista vincular a SKUs do ERP (R6).
     *
     * @param pagina página 1-based; o adapter traduz para a paginação do canal
     */
    List<AnuncioDoCanal> listarAnuncios(CredenciaisCanal credenciais, int pagina, int limite);

    /**
     * Publica o saldo no canal.
     *
     * <p>⚠️ {@code saldos} tem de conter <b>todas</b> as variações do anúncio, não só as que
     * mudaram — ver a nota da interface. O adapter que precisar de dado que não está aqui
     * (atributos, título) <b>lê o anúncio no canal antes de escrever</b>; nunca monta um PUT
     * apenas com o que recebeu.
     *
     * @throws CanalIndisponivelException quando o canal recusou por motivo transitório (rede,
     *         429, 5xx) — o outbox reagenda. Erro definitivo é exceção de negócio, que vira
     *         dead-letter em vez de girar para sempre.
     */
    void atualizarEstoque(CredenciaisCanal credenciais, String idExternoItem,
                          List<SaldoAnuncio> saldos);

    /**
     * Publica preço no canal.
     *
     * <p>✅ <b>Liberada em 2026-08-25</b>, quando o dono do produto fechou a pergunta 4: o preço do
     * marketplace é <b>próprio</b>, e pode ser maior ou menor que o da loja física. O que chega
     * aqui é sempre {@code anuncio.preco} — nunca {@code produto.preco_venda}. Empurrar o preço de
     * balcão para um canal que cobra 11–19% de comissão destruiria a margem do lojista, e é
     * exatamente o que a existência de {@link PrecoDoCanal} evita.
     *
     * <p>⚠️ Quem chama <b>não decide</b> o preço: ele já foi decidido (derivado da regra do canal
     * ou digitado pelo lojista, ver {@link PrecoDoCanal}). Um adapter que recalcule preço aqui
     * dentro é defeito.
     */
    void atualizarPreco(CredenciaisCanal credenciais, String idExternoItem,
                        String idExternoVariacao, BigDecimal preco);

    /** Falha transitória: o outbox reagenda com backoff. Não confundir com erro de negócio. */
    class CanalIndisponivelException extends RuntimeException {
        public CanalIndisponivelException(String mensagem) {
            super(mensagem);
        }

        public CanalIndisponivelException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }
}
