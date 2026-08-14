/**
 * Contas a Pagar / Pagas (2026-08-12) — tela de gestão das duplicatas de fornecedor
 * ({@code contas_pagar}, V026), CRUD completo (visualizar/editar/excluir/incluir). A tabela já
 * existia e era gravada só internamente pela Entrada de Produtos por Compra ({@link
 * com.vetor.niner.estoque.entrada.ContasPagarService#gravar}, mantido como está); este pacote é
 * a primeira superfície própria da tela, incluindo o "pagamento" (baixa): {@code
 * documento_pago}/{@code data_pagamento}/{@code valor_pago} são editados aqui, não em endpoint
 * separado — a tela só pediu Visualizar/Editar/Excluir, sem uma ação de "Pagar" dedicada.
 *
 * <p>Dados sujeitos ao RLS de tenant (V026, P8).
 */
package com.vetor.niner.financeiro.contaspagar;
