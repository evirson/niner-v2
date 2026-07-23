/**
 * Módulo <b>financeiro</b> — crediário, caixa e contas do lojista (Q5/ADR-010/ADR-012,
 * spec §3.3.7). Primeiras telas: {@code Moeda} (forma de recebimento — já semeada por tenant
 * no signup, {@code SignupService}) e {@code Tipo de Carteira} (prazo/parcelas/taxa do
 * crediário/cartão); o N:N entre as duas ({@code moeda_detalhe}) é gerido embutido no
 * formulário de Tipo de Carteira (2026-07-23) — Moeda tem tela própria só para seus campos.
 *
 * <p>Dados sujeitos ao RLS de tenant (V025/V024, P8).
 */
package com.vetor.niner.financeiro;
