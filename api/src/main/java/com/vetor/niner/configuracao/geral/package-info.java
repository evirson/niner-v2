/**
 * Configuração geral do tenant (spec §3.5.1, tabela {@code cfg_geral} de V023) — parâmetros
 * de sistema (percentual máximo de desconto em venda, uso de variantes de produto, taxas de
 * crediário) editáveis pelo lojista.
 *
 * <p>Particularidades estruturais, diferentes de toda tela de {@code cadastros}: a tabela é
 * um <b>singleton por tenant</b> ({@code id_tenant} é a própria PK, sem surrogate) —
 * inserida com valores padrão no signup ({@code SignupService.assinar}), então **sempre
 * existe** uma linha por tenant; não há criação nem exclusão nesta tela, só
 * {@code GET}/{@code PUT}. Diferente das demais telas de cadastro, o acesso aqui é
 * <b>somente ADMIN</b> (leitura e escrita) — decisão de produto, dada a sensibilidade dos
 * parâmetros (taxas financeiras, regras de venda que afetam o tenant inteiro).
 *
 * <p>Os campos de crediário ({@code juros_crediario*}/{@code multa_crediario*}) já existem
 * no banco desde V023 mas o módulo de crediário em si é Fase 2 (Q5) — a tela já os
 * apresenta e permite editar, para não exigir retrabalho de UI quando o crediário for
 * implementado.
 */
package com.vetor.niner.configuracao.geral;
