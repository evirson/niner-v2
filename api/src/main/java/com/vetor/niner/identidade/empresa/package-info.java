/**
 * Listagem somente leitura de empresas do tenant (V014) — sem CRUD ainda (spec §3.3.2,
 * Q6: {@code tenant 1:N empresa}, 1:1 no v1, mas o schema já suporta N). Usado hoje pelo
 * seletor "empresas com acesso" da tela {@code identidade.usuario} (docs/telas/usuario.md);
 * qualquer papel pode listar — não é dado sensível.
 *
 * <p>Dados sujeitos ao RLS de tenant (V014/V024, P8).
 */
package com.vetor.niner.identidade.empresa;
