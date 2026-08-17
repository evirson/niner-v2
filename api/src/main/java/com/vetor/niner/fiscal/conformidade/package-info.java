/**
 * Conformidade Fiscal — painel de diagnóstico somente-leitura, por empresa: o que impede ligar o
 * fiscal e emitir nota, antes que o lojista descubra no caixa com cliente na frente (F11)
 * (docs/telas/fiscal-conformidade.md, bloco B3 de docs/MODULOFISCAL.md §17.1).
 *
 * <p>Não é tela de lista comum: sem popup de filtros (um parâmetro só, a empresa, com default),
 * sem paginação no topo (é um painel de contagens por categoria) — a paginação existe só dentro
 * do drill-down de cada categoria, onde as muitas linhas de fato estão. Nada é corrigido aqui.
 */
package com.vetor.niner.fiscal.conformidade;
