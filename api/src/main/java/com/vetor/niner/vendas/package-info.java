/**
 * Módulo <b>vendas</b> — PDV (frente de caixa, {@code docs/telas/pdv.md}). Busca/leitura de
 * produto por descrição ou código de barras (com estoque real por empresa) e efetivação da
 * venda: grava {@code venda} + ledger de estoque ({@code produto_movimento_mestre/detalhe} —
 * a baixa em {@code produto_estoque} é feita pela trigger já existente, {@code
 * fn_atualiza_estoque_movimento}, V019, P1) e a(s) parcela(s) em {@code contas_receber} a
 * partir do {@code tipo_carteira} escolhido.
 *
 * <p>v1 sem cliente/vendedor vinculado, sem seleção de empresa (tenant 1:1 empresa hoje — a
 * única empresa é resolvida automaticamente), sem desconto/oferta — ver "Escopo desta versão"
 * em {@code docs/telas/pdv.md}. Dados sujeitos ao RLS de tenant (P8).
 */
package com.vetor.niner.vendas;
