/**
 * Cancelamento de Devolução de Produtos (2026-08-11) — desfaz um vale-mercadoria ainda não
 * usado: tira do estoque a quantidade que a devolução original tinha colocado de volta
 * ({@code produto_movimento_mestre/detalhe}, {@code tipo_movimento = 'CANCELAMENTO_DEVOLUCAO'})
 * e marca {@code venda_devolucao.cancelada/data_cancelamento/id_usuario_cancelamento/
 * motivo_cancelamento}. Só é permitido cancelar um vale com {@code vale_usado = false} — um
 * vale já resgatado numa venda não pode mais ser cancelado (a venda que o consumiu teria que
 * ser desfeita primeiro, fora de escopo aqui). ADMIN e OPERADOR têm acesso; OPERADOR só enxerga
 * e cancela devoluções da empresa em que está logado (claim {@code eid}), ADMIN não tem essa
 * restrição.
 *
 * <p>Dados sujeitos ao RLS de tenant (V018, P8).
 */
package com.vetor.niner.vendas.cancelamentodevolucao;
