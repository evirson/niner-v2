/**
 * Cadastro de fornecedor (spec §3.3.9, tabela {@code fornecedor} de V016) — quarta tela de
 * domínio do módulo <b>cadastros</b>, no padrão consolidado
 * ({@code cadastros.cliente}/{@code funcionario}/{@code planocontas}).
 *
 * <p>Particularidades: {@code id_plano_contas} é {@code NOT NULL} (FK composta para
 * {@code cfg_plano_contas}, sem linha padrão pré-cadastrada — todo fornecedor exige um plano
 * de contas já criado; a tela oferece criação rápida embutida); {@code cnpj} é único por
 * tenant e segue a convenção do <b>CNPJ alfanumérico</b> (CLAUDE.md); {@code telefone}
 * aceita fixo ou celular (10–11 dígitos — fornecedor costuma ter linha fixa, diferente da
 * regra de celular do cliente).
 *
 * <p>Dados sujeitos ao RLS de tenant (V016/V024, P8).
 */
package com.vetor.niner.cadastros.fornecedor;
