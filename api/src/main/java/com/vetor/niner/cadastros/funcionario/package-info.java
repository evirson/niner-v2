/**
 * Cadastro de funcionário (spec §3.3.9, docs/telas/funcionario.md) — segunda tela de
 * domínio do módulo <b>cadastros</b>, seguindo o mesmo padrão consolidado em
 * {@code cadastros.cliente} (paginação por página, ordenação por coluna, validação de
 * formato + obrigatoriedade configurável via {@code cfg_tela_campo}, exclusão com fallback
 * para inativar quando há vínculo).
 *
 * <p>Dados sujeitos ao RLS de tenant (V016/V024, P8).
 */
package com.vetor.niner.cadastros.funcionario;
