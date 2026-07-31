/**
 * Pesquisa de Vendas (docs/telas/pesquisa-vendas.md) — tela de consulta, qualquer papel:
 * localiza vendas por número ou por período/empresa/cliente/vendedor/situação e mostra, ao
 * selecionar uma, o detalhamento completo (itens do ledger de estoque, movimentação de
 * {@code caixa_detalhe} e parcelas de {@code contas_receber}). Somente leitura — nenhuma rota
 * de escrita aqui; alterações continuam no PDV ou em {@code vendas.cancelamento}.
 *
 * <p>Dados sujeitos ao RLS de tenant (V018/V024, P8).
 */
package com.vetor.niner.vendas.pesquisa;
