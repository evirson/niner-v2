/**
 * Entrada de Produtos por Compra (docs/telas/entrada-mercadoria.md) — recebe mercadoria de
 * fornecedor por 3 fluxos convergentes (Manual, XML de NF-e, Planilha Excel), todos terminando
 * na mesma confirmação ({@link com.vetor.niner.estoque.entrada.EntradaMercadoriaService#efetivar}):
 * 1 {@code produto_movimento_mestre} (`tipo_movimento = 'COMPRA'`, enum já existia desde V013,
 * nunca usado até aqui) + N {@code produto_movimento_detalhe} (`credito_debito = 'C'`), com
 * geração opcional de {@code contas_pagar} (duplicatas) e rateio/reajuste de preço conforme
 * {@code cfg_geral}. Deliberadamente sem motor de heurísticas pra extrair cor/tamanho de texto
 * livre do XML — o operador sempre confirma cor/tamanho na grade do produto (ver decisão
 * registrada na spec, 2026-08-11).
 *
 * <p>Dados sujeitos ao RLS de tenant (P8).
 */
package com.vetor.niner.estoque.entrada;
