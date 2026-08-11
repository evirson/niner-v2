/**
 * Devolução de Produtos (docs/telas/devolucao-produtos.md) — devolve ao estoque itens apontados
 * por código de barras e emite um vale-mercadoria ({@code venda_devolucao}). O número da venda de
 * origem é opcional: serve para resolver o vendedor (gravado em cada linha do movimento,
 * {@code produto_movimento_detalhe.id_funcionario}, pra uma futura comissão) e fica persistido em
 * {@code venda_devolucao.id_venda_credito}. Desde 2026-08-11, quando informado, **também restringe
 * o que pode ser devolvido**: só produtos vendidos naquela venda, até a quantidade ainda não
 * devolvida dela (validado no servidor, {@code DevolucaoProdutoService.efetivar}, não só na tela —
 * P4). Sem venda informada, qualquer produto pode ser devolvido livremente, como antes. ADMIN e
 * OPERADOR têm acesso (mexe só em estoque, não em dinheiro). Sem efeito em caixa/contas a receber.
 *
 * <p>Dados sujeitos ao RLS de tenant (P8).
 */
package com.vetor.niner.vendas.devolucao;
