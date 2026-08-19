/**
 * Cobrança da assinatura (ADR-016) — Mercado Pago atrás da interface
 * {@link com.vetor.niner.plataforma.cobranca.GatewayCobranca} (ADR-008).
 *
 * <p>Duas regras estruturam tudo aqui:
 * <ol>
 *   <li><b>O webhook não decide nada.</b> A notificação só é gravada
 *       ({@code plataforma.webhook_gateway}, única por gateway+evento); quem aplica efeito é o
 *       worker, e <b>consultando o gateway</b> — nunca acreditando no corpo recebido (P2).</li>
 *   <li><b>Valor nunca vem do cliente.</b> O preço é relido de {@code plataforma.plano} no
 *       servidor, a partir da faixa e do ciclo escolhidos.</li>
 * </ol>
 */
package com.vetor.niner.plataforma.cobranca;
