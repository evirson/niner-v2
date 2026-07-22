/**
 * Módulo <b>catalogo</b> — produtos, variações e SKU do lojista. Ver spec §3.3.3.
 * Depende de Q7 (SKU × EAN). Primeira tela implementada: produto (docs/telas/produto.md),
 * com categoria ({@code cfg_categoria_produto}, N:N ordenada via {@code produto_categoria.indice})
 * gerida embutida. Variação/SKU ({@code produto_barra}) ainda não tem CRUD (fica para o
 * próximo corte vertical, junto de estoque).
 *
 * <p>Dados sujeitos ao RLS de tenant (V017/V024, P8).
 */
package com.vetor.niner.catalogo;
