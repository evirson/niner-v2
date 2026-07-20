/**
 * Módulo <b>telaconfig</b> — configuração por tenant de quais campos aparecem e quais são
 * obrigatórios em cada tela do produto (V027, {@code cfg_tela_campo}). Reutilizável por
 * qualquer tela via {@code chave_tela} (mesma convenção do catálogo de ajuda, R22/§3.7.1).
 * Só ADMIN configura; qualquer usuário do tenant pode ler (a tela precisa saber como se
 * renderizar). Sujeito ao RLS de tenant (P8).
 */
package com.vetor.niner.comum.telaconfig;
