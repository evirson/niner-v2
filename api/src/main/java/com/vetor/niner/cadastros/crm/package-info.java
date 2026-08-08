/**
 * CRM — segmentação de clientes para campanhas (2026-08-05), primeira implementação real da área
 * "CRM" (até então só um placeholder "Em construção" no menu, ver {@code docs/PROGRESSO.md}). Uma
 * tela só: filtros de cliente + filtros de produtos comprados (ambos opcionais e combináveis) →
 * lista de clientes com dados de contato e histórico de compras → exportação em planilha Excel
 * (`.xlsx`, gerada no frontend com a lib {@code write-excel-file} — não {@code xlsx}/SheetJS, que
 * tem vulnerabilidade alta sem correção disponível via npm; mesmo espírito do PDF client-side já
 * usado nos relatórios com jsPDF, não passa por lógica de negócio no backend).
 *
 * <p><b>Filtros de cliente</b> (todos opcionais e combináveis com AND): faixa alfabética pela
 * PRIMEIRA LETRA do nome ({@code cliente inicial/final} — dado o exemplo do dono do produto ser
 * literalmente "A"/"Z", não um prefixo lexicográfico completo), gênero (multi-seleção), idade
 * De/Até (calculada de {@code data_nascimento}, exclui quem não tem data cadastrada), aniversário
 * De/Até por mês/dia (ignora o ano, com suporte a intervalo que cruza a virada do ano, ex.:
 * 20/12 a 10/01), categoria do cliente (multi-seleção, {@code cfg_categoria_cliente}), período de
 * cadastro ({@code cliente.criado_em}) e nº mínimo de dias sem comprar (cliente que nunca comprou
 * conta como "sempre" sem comprar — sempre atende esse filtro quando informado).
 *
 * <p><b>Filtros de produtos comprados</b> (opcionais; quando qualquer um está ativo, vira um
 * `EXISTS` exigindo que TODOS os ativos batam na MESMA linha de venda — mesmo item, não compras
 * diferentes): período de compra, categoria de produto, cor, tamanho.
 * "Compra" = {@code produto_movimento_detalhe} de uma venda não cancelada
 * ({@code tipo_movimento='VENDA'}, {@code credito_debito='D'}, {@code venda.cancelada=false}) —
 * mesma definição de {@code ClienteHistoricoService.buscarCompras}, exceto que aqui vendas
 * canceladas são excluídas de propósito (relevância pra campanha, não auditoria histórica).
 *
 * <p><b>Escopo por empresa</b> (silencioso, sem seletor na tela — não pedido, só aplica a regra
 * já padrão do resto do sistema): OPERADOR só enxerga compras da própria empresa ativa (claim
 * {@code eid}) nos filtros/dados de produtos comprados; ADMIN vê todas as empresas do tenant.
 * {@code cliente} em si não é por empresa (compartilhado no tenant), só as vendas são.
 *
 * <p><b>Dados para geração</b> (colunas de saída, todas sempre calculadas pelo backend — o
 * frontend decide quais aparecem na planilha via checkbox, sem reconsultar): nome, data de
 * nascimento, gênero, e-mail, celular ({@code cliente.telefone}, renomeado na UI desde
 * 2026-07-21), primeira compra, última compra, nº de compras — as 3 últimas sempre pelo
 * HISTÓRICO COMPLETO do cliente (não limitadas pelo filtro "Período de Compras", que só serve
 * pra SELECIONAR quais clientes entram na lista).
 */
package com.vetor.niner.cadastros.crm;
