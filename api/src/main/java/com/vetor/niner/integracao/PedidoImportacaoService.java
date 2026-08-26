package com.vetor.niner.integracao;

import com.vetor.niner.canais.CanalDeVenda;
import com.vetor.niner.canais.CanalDeVenda.ItemDoPedido;
import com.vetor.niner.canais.CanalDeVenda.PedidoDoCanal;
import com.vetor.niner.canais.CredenciaisCanal;
import com.vetor.niner.canais.CredenciaisCanalRepositorio;
import com.vetor.niner.integracao.PedidoImportacaoRepositorio.VinculoDoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Importa um pedido do marketplace para a fila de expedição do ERP (M5, R5).
 *
 * <h2>⛔ O pedido vem da API do canal, nunca do corpo do webhook</h2>
 *
 * A notificação diz apenas <i>"o pedido X mudou"</i>. Quem conta o que mudou é o marketplace,
 * consultado com a credencial do lojista. É isso que torna um webhook forjado inofensivo (P2).
 *
 * <h2>⚠️ Item sem vínculo RECUSA o pedido inteiro — e isso é deliberado</h2>
 *
 * Se qualquer item do pedido aponta para um anúncio que o lojista ainda não vinculou (R6), a
 * importação <b>não grava nada</b> e devolve uma falha legível. As alternativas eram piores:
 *
 * <ul>
 *   <li><b>Importar só os itens vinculados</b> daria um pedido com metade das linhas — que parece
 *       completo na tela de expedição e faria a loja despachar um pacote faltando produto.</li>
 *   <li><b>Inventar uma variação</b> gravaria a venda de um produto no estoque de outro.</li>
 * </ul>
 *
 * <p>⭐ E o custo de recusar é baixo <b>porque a fila não desiste</b>: a notificação continua
 * pendente e o polling de segurança traz o pedido de novo. No minuto em que o lojista vincular o
 * anúncio, a importação passa sozinha — sem ninguém reprocessar nada à mão.
 */
@Service
public class PedidoImportacaoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoImportacaoService.class);

    private final PedidoImportacaoRepositorio repositorio;
    private final CredenciaisCanalRepositorio credenciais;
    private final List<CanalDeVenda> adapters;

    public PedidoImportacaoService(PedidoImportacaoRepositorio repositorio,
                                   CredenciaisCanalRepositorio credenciais,
                                   List<CanalDeVenda> adapters) {
        this.repositorio = repositorio;
        this.credenciais = credenciais;
        this.adapters = adapters;
    }

    /** Falha de importação que precisa de ação do lojista — não adianta repetir sozinho hoje. */
    public static class PedidoNaoImportavelException extends RuntimeException {
        public PedidoNaoImportavelException(String mensagem) {
            super(mensagem);
        }
    }

    /**
     * Busca o pedido no canal e grava.
     *
     * <p>⚠️ <b>Sem {@code @Transactional} nesta fachada</b>: no meio dela há chamadas HTTP ao
     * marketplace (duas, no ML), e segurar uma conexão do pool esperando terceiro é o defeito
     * apontado na auditoria de 2026-08-21. Quem transaciona é o repositório.
     *
     * @param tipoCanal  valor de {@code tipo_canal} — decide o adapter
     * @param idExterno  id do pedido no marketplace
     */
    public void importar(long idCanal, String tipoCanal, String idExterno) {
        CredenciaisCanal cred = credenciais.carregar(idCanal)
                .orElseThrow(() -> new PedidoNaoImportavelException(
                        "Canal sem credencial. Reconecte o canal em Canais de Venda."));

        PedidoDoCanal doCanal = adapterDe(tipoCanal).buscarPedido(cred, idExterno);

        // ⭐ Resolve TODOS os vínculos antes de gravar qualquer coisa. Resolver enquanto grava
        // deixaria o pedido meio escrito quando o terceiro item fosse o não vinculado — e um
        // pedido pela metade na fila de expedição é pior que pedido nenhum.
        List<Pendencia> pendencias = new ArrayList<>();
        for (ItemDoPedido item : doCanal.itens()) {
            VinculoDoItem vinculo = repositorio.vinculoDoItem(idCanal, item).orElse(null);
            if (vinculo == null) {
                throw new PedidoNaoImportavelException(
                        "O anúncio %s%s (\"%s\") ainda não está vinculado a um produto. "
                                .formatted(item.idExternoItem(),
                                        item.idExternoVariacao() == null ? ""
                                                : " / " + item.idExternoVariacao(),
                                        item.titulo())
                                + "Vincule-o em Canais de Venda → Vincular anúncios; o pedido entra sozinho depois.");
            }
            pendencias.add(new Pendencia(item, vinculo));
        }

        var resultado = repositorio.salvarPedido(idCanal, doCanal);
        if (!resultado.novo()) {
            // Já existia: o status e os totais foram atualizados, os itens não. Ver o javadoc do
            // repositório — regravar item duplicaria movimento quando o M6 chegar.
            log.debug("Pedido {} do canal {} já existia — status atualizado.", idExterno, idCanal);
            return;
        }
        for (Pendencia p : pendencias) {
            repositorio.inserirItem(resultado.idPedido(), p.vinculo(), p.item());
        }
        log.info("Pedido {} do canal {} importado com {} item(ns).",
                idExterno, idCanal, pendencias.size());
    }

    private record Pendencia(ItemDoPedido item, VinculoDoItem vinculo) {
    }

    private CanalDeVenda adapterDe(String tipo) {
        return adapters.stream()
                .filter(a -> a.tipo().name().equals(tipo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Sem adapter para o canal " + tipo + " — pedido não pode ser importado."));
    }
}
