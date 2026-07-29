/**
 * Cadastro de usuários do tenant (spec §3.3.2, docs/telas/usuario.md) — mesmo padrão
 * consolidado em {@code cadastros.funcionario} (paginação por página, ordenação por coluna,
 * exclusão com fallback para inativar), mas restrito a {@code ADMIN} (mesmo mecanismo de
 * {@code configuracao.geral} — gerenciar quem acessa o sistema é sensível o bastante pra não
 * cair na regra geral de "OPERADOR também tem acesso" do resto de {@code cadastros}).
 *
 * <p>Além do CRUD, a tela seleciona em quais empresas ({@code usuario_empresa}, V015) o
 * usuário pode operar. Permissão fina por rotina ({@code usuario_rotina}, R8) ainda não tem
 * UI — ver "Non-goals" em docs/telas/usuario.md.
 *
 * <p>Dados sujeitos ao RLS de tenant (V015/V024, P8).
 */
package com.vetor.niner.identidade.usuario;
