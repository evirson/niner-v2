package com.vetor.niner.integracao;

import com.vetor.niner.integracao.ExpedicaoRepositorio.ItemAExpedir;
import com.vetor.niner.integracao.ExpedicaoRepositorio.PedidoNaFila;
import com.vetor.niner.integracao.outbox.OutboxRepositorio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Fila de expedição (R5, M7) — separar e despachar o que foi vendido no marketplace.
 *
 * <h2>O caminho, e por que ele é curto</h2>
 *
 * {@code PAGO → EM_SEPARACAO → ENVIADO}. Três estados, não seis. Cada estado a mais é uma pergunta
 * a mais para quem está com a caixa na mão, e a fila de uma loja pequena não comporta cerimônia.
 *
 * <p>⛔ <b>RECEBIDO não entra na fila</b>, e é decisão: pedido não pago pode não ser pago nunca.
 * Separar mercadoria para ele é trabalho jogado fora — e, pior, a peça sai da prateleira e some do
 * fluxo da loja. A <b>reserva</b> (M6) já segura o estoque; a separação espera o dinheiro.
 *
 * <h2>⚠️ O avanço de estado é um UPDATE condicional, sempre</h2>
 *
 * {@code WHERE status = <o esperado>}. Dois operadores clicando "Separar" no mesmo pedido, ou um
 * clique duplo, fazem o segundo casar 0 linhas — e a mensagem diz o estado real, em vez de um
 * "erro inesperado". Ler-conferir-gravar deixaria janela, e aqui a janela vale um pacote
 * despachado duas vezes.
 */
@Service
public class ExpedicaoService {

    private static final Logger log = LoggerFactory.getLogger(ExpedicaoService.class);

    /** Tipo do evento de outbox que avisa o canal do despacho. */
    public static final String EVENTO_ENVIO = "ENVIO_CONFIRMADO";

    private static final Map<String, String> ROTULO = Map.of(
            "RECEBIDO", "aguardando pagamento",
            "PAGO", "pago, aguardando separação",
            "EM_SEPARACAO", "em separação",
            "ENVIADO", "enviado",
            "ENTREGUE", "entregue",
            "CANCELADO", "cancelado");

    private final ExpedicaoRepositorio repositorio;
    private final OutboxRepositorio outbox;

    public ExpedicaoService(ExpedicaoRepositorio repositorio, OutboxRepositorio outbox) {
        this.repositorio = repositorio;
        this.outbox = outbox;
    }

    public List<PedidoNaFila> fila(Jwt jwt) {
        exigirAcesso(jwt);
        return repositorio.fila(List.of("PAGO", "EM_SEPARACAO"));
    }

    public List<ItemAExpedir> itens(Jwt jwt, long idPedido) {
        exigirAcesso(jwt);
        return repositorio.itens(idPedido);
    }

    /** Começou a separar: tira o pedido da fila de quem ainda não pegou. */
    @Transactional
    public void separar(Jwt jwt, long idPedido) {
        exigirAcesso(jwt);
        if (!repositorio.avancar(idPedido, "PAGO", "EM_SEPARACAO", idUsuario(jwt), null)) {
            throw recusa(idPedido, "separar", "pago");
        }
    }

    /**
     * Despachou.
     *
     * <p>⚠️ O aviso ao canal vai pelo <b>outbox</b> (P2), não daqui: se a chamada ao marketplace
     * fosse feita nesta requisição, uma indisponibilidade dele impediria o lojista de registrar um
     * despacho que <b>já aconteceu no mundo físico</b>. O pacote saiu; o ERP tem que conseguir
     * dizer isso.
     */
    @Transactional
    public void enviar(Jwt jwt, long idPedido, String codigoRastreio) {
        exigirAcesso(jwt);
        String rastreio = codigoRastreio == null || codigoRastreio.isBlank() ? null : codigoRastreio.trim();

        if (!repositorio.avancar(idPedido, "EM_SEPARACAO", "ENVIADO", idUsuario(jwt), rastreio)) {
            throw recusa(idPedido, "enviar", "em separação");
        }

        // Enfileirado na MESMA transação do avanço de estado — ou os dois acontecem, ou nenhum.
        var envio = repositorio.dadosDoEnvio(idPedido);
        outbox.enfileirar(EVENTO_ENVIO, String.valueOf(idPedido),
                Map.of("idPedido", idPedido, "idCanal", envio.idCanal()));

        log.info("Pedido {} ({}) despachado.", idPedido, envio.idExterno());
    }

    /**
     * A recusa que diz <b>o estado real</b>.
     *
     * <p>⚠️ "Não foi possível separar o pedido" mandaria o operador procurar problema no sistema.
     * "Este pedido está enviado" resolve a dúvida dele na mesma frase — quase sempre é um colega
     * que já pegou, ou a própria tela desatualizada.
     */
    private ResponseStatusException recusa(long idPedido, String acao, String esperado) {
        String atual = repositorio.statusAtual(idPedido);
        if (atual == null) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado.");
        }
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Não dá para %s: este pedido está %s, e a ação só vale para pedido %s. "
                        .formatted(acao, ROTULO.getOrDefault(atual, atual.toLowerCase()), esperado)
                        + "Atualize a tela — outra pessoa pode ter avançado o pedido.");
    }

    private static Long idUsuario(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * ⚠️ Expedição <b>não</b> é ADMIN-only, ao contrário do resto do módulo de canais. Quem separa
     * e embala é o operador da loja — exigir ADMIN aqui obrigaria o dono a despachar tudo, ou a
     * dar acesso de administrador a quem só precisa de uma lista de itens.
     */
    private static void exigirAcesso(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão.");
        }
    }
}
