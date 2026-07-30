/**
 * Conta Corrente (2026-07-30) — cadastro de contas bancárias do lojista (PK de negócio, o
 * próprio número/código da conta) e lançamentos manuais de extrato ({@code
 * conta_corrente_movimento}, débito/crédito por plano de contas). Último módulo do {@code
 * financeiro} do legado a entrar no v1 (§3.3.7) — fecha o conjunto caixa/crediário/contas a
 * pagar/conta corrente.
 *
 * <p>Dados sujeitos ao RLS de tenant (V028, P8).
 */
package com.vetor.niner.financeiro.contacorrente;
