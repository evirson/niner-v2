/**
 * Contas a Pagar / Pagas (2026-08-12) — tela de gestão das duplicatas de fornecedor
 * ({@code contas_pagar}, V026), CRUD completo (visualizar/editar/excluir/incluir). A tabela já
 * existia e era gravada só internamente pela Entrada de Produtos por Compra ({@link
 * com.vetor.niner.estoque.entrada.ContasPagarService#gravar}, mantido como está); este pacote é
 * a primeira superfície própria da tela, incluindo o "pagamento" (baixa): {@code
 * documento_pago}/{@code data_pagamento}/{@code valor_pago} são editados aqui, não em endpoint
 * separado — a tela só pediu Visualizar/Editar/Excluir, sem uma ação de "Pagar" dedicada.
 *
 * <p><b>A baixa move dinheiro de verdade</b> (2026-08-14): informar {@code data_pagamento} exige
 * dizer de onde saiu (caixa ou conta corrente) e grava o {@code caixa_detalhe} ou o
 * {@code conta_corrente_movimento} correspondente — é o que faz o pagamento aparecer no Fluxo de
 * Caixa realizado. A sincronização é <b>apaga e regrava</b>, o que cobre de uma vez baixa
 * desfeita, troca de origem e correção de valor/data.
 *
 * <p><b>Excluir desfaz o dinheiro junto</b> (bug corrigido em 2026-08-14): antes, {@code excluir()}
 * apagava só a linha de {@code contas_pagar} e deixava o movimento de caixa/banco órfão <i>para
 * sempre</i> — como as colunas de vínculo <b>não têm FK</b> (escolha deliberada de V025/V028), o
 * banco nunca reclamou. Ver a convenção "vínculo sem FK cobra atenção no excluir()" em CLAUDE.md.
 *
 * <p><b>Caixa fechado bloqueia</b>: os três caminhos que apagam movimento (baixa desfeita, troca
 * de origem, exclusão) passam por
 * {@link com.vetor.niner.financeiro.caixa.CaixaService#exigirCaixaAbertoParaDesfazer} e respondem
 * 409 mandando reabrir o caixa, em vez de descasar uma conferência já fechada.
 *
 * <p>Dados sujeitos ao RLS de tenant (V026, P8).
 */
package com.vetor.niner.financeiro.contaspagar;
