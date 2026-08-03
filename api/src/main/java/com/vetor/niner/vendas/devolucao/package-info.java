/**
 * Devolução de Produtos (docs/telas/devolucao-produtos.md) — devolve ao estoque itens
 * apontados por código de barras, sem vínculo com nenhuma venda em particular. O número da
 * venda é opcional e serve só para resolver o vendedor (gravado em cada linha do movimento,
 * {@code produto_movimento_detalhe.id_funcionario}) — não fica persistido em lugar nenhum,
 * então não há validação cruzada contra o que foi vendido naquela venda. ADMIN e OPERADOR têm
 * acesso (mexe só em estoque, não em dinheiro). Sem efeito em caixa/contas a receber e sem uso
 * de {@code venda_devolucao} (reservada para uma futura feature de troca/vale-mercadoria) — só
 * {@code produto_movimento_mestre/detalhe} ({@code tipo_movimento = 'DEVOLUCAO'}, já existente
 * desde V013, nunca usado até aqui).
 *
 * <p>Dados sujeitos ao RLS de tenant (P8).
 */
package com.vetor.niner.vendas.devolucao;
