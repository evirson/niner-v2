/**
 * Perfis Fiscais — cadastro reutilizável de CFOP/CST/CSOSN/alíquotas por contexto (CRT × UF ×
 * destinatário × operação), que o produto referencia em vez de carregar tributação própria
 * (docs/telas/fiscal-perfil.md, bloco B2 de docs/MODULOFISCAL.md §17.1).
 *
 * <p>DF37 — só CRT 1, 2 e 4 são aceitos (Simples Nacional e MEI); CST de ICMS só é válido no
 * CRT 2, por causa de uma divergência de mercado ainda em aberto sobre se essa empresa emite com
 * CSOSN ou CST. O motor tributário ({@code fiscal.motor}) consome as regras daqui já resolvidas
 * — esta camada só cadastra, nunca calcula.
 */
package com.vetor.niner.fiscal.perfil;
