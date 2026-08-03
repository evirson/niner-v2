/**
 * Cancelamento de Venda (docs/telas/cancelamento-venda.md) — estorna uma venda finalizada:
 * devolve o estoque ({@code produto_movimento_mestre/detalhe}, {@code tipo_movimento =
 * 'CANCELAMENTO'}), apaga os lançamentos de {@code caixa_detalhe} e as parcelas em {@code
 * contas_receber} geradas pela venda, e reabre qualquer vale-mercadoria resgatado nela
 * ({@code venda_devolucao.vale_usado/id_venda_debito}, 2026-08-03), numa única transação.
 * ADMIN-only (RN-04, v1 — sem permissão granular ainda). Bloqueio definitivo (RN-03) se houver
 * parcela de crediário já recebida; exige o caixa de hoje aberto (RN-02, simplificado — sem
 * "caixa da data da venda", que exigiria reabrir caixa de dias passados). Comissão, documento
 * fiscal e TEF ficam fora do v1 — nada disso existe no sistema ainda.
 *
 * <p>Dados sujeitos ao RLS de tenant (V018/V024, P8).
 */
package com.vetor.niner.vendas.cancelamento;
