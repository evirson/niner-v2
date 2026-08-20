package com.vetor.niner.plataforma.cobranca;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Adapter de gateway de cobrança (ADR-008; provedor decidido no ADR-016). O domínio de cobrança
 * fala só com esta interface — trocar de provedor é trocar a implementação, não o serviço.
 *
 * <p>Nenhum método recebe o valor do cliente: quem chama já resolveu preço a partir do plano.
 */
public interface GatewayCobranca {

    /** Nome do gateway como gravado em {@code pagamento.gateway}/{@code webhook_gateway.gateway}. */
    String nome();

    /** {@code false} quando falta credencial — a API sobe igual, e a cobrança responde 503. */
    boolean configurado();

    /**
     * Cria uma cobrança PIX avulsa.
     *
     * @param referencia identificador nosso (vai como {@code external_reference}), usado para
     *                   conciliar a notificação com a fatura sem depender de estado em memória
     * @param chaveIdempotencia repetir a mesma chave não cria uma segunda cobrança (P2)
     */
    CobrancaPix criarPix(String referencia, BigDecimal valor, String descricao, String emailPagador,
            String chaveIdempotencia);

    /** Consulta a situação real de um pagamento no gateway — a fonte de verdade (nunca o webhook). */
    SituacaoPagamento consultarPagamento(String idTransacao);

    /** PIX criado: o que a tela precisa mostrar. */
    record CobrancaPix(
            String idTransacao, String copiaECola, String qrCodeBase64, String linkPagamento,
            OffsetDateTime expiraEm) {
    }

    /** Situação normalizada — cada gateway tem seu vocabulário; o domínio só conhece este. */
    record SituacaoPagamento(String idTransacao, Situacao situacao, BigDecimal valor, String referencia) {
    }

    enum Situacao {
        PENDENTE, CONFIRMADO, FALHOU, ESTORNADO
    }
}
