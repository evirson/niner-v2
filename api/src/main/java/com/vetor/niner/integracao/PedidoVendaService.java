package com.vetor.niner.integracao;

import com.vetor.niner.integracao.PedidoVendaRepositorio.ItemDoPedidoNoErp;
import com.vetor.niner.integracao.PedidoVendaRepositorio.PedidoParaConverter;
import com.vetor.niner.plataforma.uso.LimiteVendasService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pedido de marketplace vira <b>venda</b> do ERP (M6, R5) — a decisão nº 1 da §8 do estudo.
 *
 * <h2>⭐ Por que "virar venda" era a decisão que mais mudava o desenho</h2>
 *
 * Tudo no ERP pendura em {@code venda}: DRE, Lucratividade, Relatório de Vendas, Kardex, cota do
 * plano. Um pedido que <i>não</i> virasse venda precisaria de telas e relatórios próprios, e a
 * venda de marketplace não apareceria em lugar nenhum onde o lojista olha. Virando venda, aparece
 * em tudo — <b>sem código novo nesses relatórios</b>.
 *
 * <h2>O ciclo, e o que acontece em cada estado</h2>
 *
 * <ol>
 *   <li><b>RECEBIDO</b> → <b>reserva</b> o estoque (ADR-004). Não vira venda ainda: o comprador
 *       pode não pagar. ⭐ E a reserva já derruba o saldo publicado no anúncio sozinha — ver a nota
 *       do repositório.</li>
 *   <li><b>PAGO</b> → <b>vira venda</b>: libera a reserva, lança a saída de estoque de verdade,
 *       cria a parcela a receber na carteira do canal e consome cota do plano.</li>
 *   <li><b>CANCELADO</b> → <b>libera a reserva</b>. ⚠️ Se já tiver virado venda, o M6 <b>não</b>
 *       cancela a venda — ver a nota de {@link #aoMudarStatus}.</li>
 * </ol>
 *
 * <h2>⛔ Idempotência é a espinha disto</h2>
 *
 * O mesmo pedido chega muitas vezes de propósito (webhook + reenvio + polling a cada 15 min).
 * Converter duas vezes criaria duas vendas, debitaria estoque duas vezes e consumiria cota duas
 * vezes. As duas travas são {@code UPDATE} condicionais no banco — {@code id_venda IS NULL} e
 * {@code estoque_reservado = false} —, nunca um {@code SELECT} antes do {@code INSERT}.
 */
@Service
public class PedidoVendaService {

    private static final Logger log = LoggerFactory.getLogger(PedidoVendaService.class);

    private final PedidoVendaRepositorio repositorio;
    private final LimiteVendasService limiteVendas;

    public PedidoVendaService(PedidoVendaRepositorio repositorio, LimiteVendasService limiteVendas) {
        this.repositorio = repositorio;
        this.limiteVendas = limiteVendas;
    }

    /** Falha que precisa de ação do lojista — repetir sozinho não resolve hoje. */
    public static class ConversaoBloqueadaException extends RuntimeException {
        public ConversaoBloqueadaException(String mensagem) {
            super(mensagem);
        }
    }

    /**
     * Reage ao estado do pedido — chamado logo depois de cada importação.
     *
     * <p>⚠️ <b>Transacional aqui, e é o ponto:</b> reservar/converter mexe em estoque, venda, conta
     * a receber e cota. Ou tudo acontece, ou nada — um pedido meio convertido (venda criada, conta
     * a receber não) seria dinheiro sumido do financeiro do lojista.
     *
     * <p>⛔ <b>Cancelamento de pedido já convertido NÃO cancela a venda</b>, de propósito. Cancelar
     * venda no Nainer é rotina com regra própria (estorna estoque, mexe em caixa, pede motivo, e
     * quando há nota fiscal fala com a SEFAZ) — disparar isso a partir de uma notificação
     * automática de terceiro seria deixar um marketplace cancelar documento fiscal da loja. O
     * pedido fica marcado CANCELADO e o lojista decide, na tela de Cancelamento de Venda. Isso é
     * dívida consciente do M6, escrita em {@code MODULOMARKETPLACE.md} §10.9.
     */
    @Transactional
    public void aoMudarStatus(long idPedido) {
        PedidoParaConverter pedido = repositorio.buscar(idPedido)
                .orElseThrow(() -> new IllegalStateException("Pedido " + idPedido + " não encontrado."));

        switch (pedido.status()) {
            case "RECEBIDO" -> reservar(pedido);
            case "PAGO" -> converter(pedido);
            case "CANCELADO" -> cancelar(pedido);
            default -> log.debug("Pedido {} em {}: nada a fazer.", idPedido, pedido.status());
        }
    }

    private void reservar(PedidoParaConverter pedido) {
        if (pedido.idVenda() != null) {
            // Já virou venda: o estoque saiu de verdade, reservar de novo subtrairia a mesma peça
            // duas vezes de `disponivel`.
            return;
        }
        if (repositorio.reservar(pedido.idPedido(), pedido.idEmpresa())) {
            log.info("Pedido {} ({}): estoque reservado.", pedido.idPedido(), pedido.idExterno());
        }
    }

    private void converter(PedidoParaConverter pedido) {
        if (pedido.idVenda() != null) {
            return;
        }
        if (pedido.idCarteira() == null) {
            // ⚠️ Sem carteira não há para onde mandar o dinheiro. Recusar é melhor que escolher
            // uma qualquer: a venda entraria no financeiro do lojista pelo lugar errado, e
            // descobrir isso depois é reconciliação manual.
            throw new ConversaoBloqueadaException(
                    "O canal não tem um tipo de carteira definido — sem ele o valor da venda não "
                            + "tem para onde ir no financeiro. Configure em Canais de Venda.");
        }

        List<ItemDoPedidoNoErp> itens = repositorio.itens(pedido.idPedido());
        if (itens.isEmpty()) {
            throw new ConversaoBloqueadaException(
                    "Pedido sem itens no ERP — não há o que dar baixa. Confira o vínculo dos anúncios.");
        }

        // A reserva sai antes da saída real: manter as duas subtrairia a mesma peça duas vezes
        // de `disponivel` (que é `qtd_estoque - reservado`).
        repositorio.liberarReserva(pedido.idPedido(), pedido.idEmpresa());

        long idVenda = repositorio.criarVenda(pedido.idEmpresa());

        // ⛔ A trava de idempotência vem ANTES de qualquer efeito colateral irreversível. Se outra
        // rodada converteu este pedido no meio do caminho, este UPDATE casa 0 linhas e a exceção
        // derruba a transação inteira — inclusive a venda que acabou de nascer.
        if (!repositorio.marcarConvertido(pedido.idPedido(), idVenda)) {
            throw new IllegalStateException(
                    "Pedido " + pedido.idPedido() + " já foi convertido por outra rodada.");
        }

        repositorio.lancarSaidaDeEstoque(idVenda, pedido.idEmpresa(), itens);

        BigDecimal valor = pedido.total() == null ? BigDecimal.ZERO : pedido.total();
        repositorio.lancarContaAReceber(idVenda, pedido.idEmpresa(), pedido.idCarteira(), valor);

        // Cota do plano (ADR-015 + §8.2): marketplace CONTA. Fica por último, depois de toda
        // validação — uma venda que ia falhar por outro motivo não pode consumir cota. Estourado o
        // limite, a exceção derruba a transação inteira, inclusive esta venda.
        limiteVendas.registrarVenda();

        log.info("Pedido {} ({}) virou a venda {} — sem caixa, sem comissão.",
                pedido.idPedido(), pedido.idExterno(), idVenda);
    }

    private void cancelar(PedidoParaConverter pedido) {
        repositorio.liberarReserva(pedido.idPedido(), pedido.idEmpresa());
        if (pedido.idVenda() != null) {
            // Ver a nota de aoMudarStatus: o M6 não cancela venda a partir de notificação de
            // terceiro. Fica registrado para o lojista decidir.
            log.warn("Pedido {} ({}) foi CANCELADO no canal, mas já virou a venda {} — "
                            + "cancele a venda pela tela de Cancelamento de Venda se for o caso.",
                    pedido.idPedido(), pedido.idExterno(), pedido.idVenda());
        }
    }
}
