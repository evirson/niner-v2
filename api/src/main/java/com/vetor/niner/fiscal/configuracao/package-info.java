/**
 * Configuração fiscal por empresa — CRT, série, ambiente, CSC e os gates {@code emite_nfce}/
 * {@code emite_nfe} (docs/telas/fiscal-configuracao.md, bloco B2 de docs/MODULOFISCAL.md §17.1).
 *
 * <p>É a base que todo o resto do módulo fiscal lê: o motor tributário tira daqui o CRT, e a
 * emissão tira daqui a série, o ambiente e a permissão de emitir. <b>DF37 — o Niner atende só
 * MEI e Simples Nacional</b> (CRT 1, 2 e 4): o CRT 3 (Regime Normal) é recusado com 400 de
 * escopo, não "não suportado", e o mesmo domínio está gravado como CHECK no banco (V035).
 *
 * <p>Diferente de {@code configuracao.geral}, que é singleton por <b>tenant</b> e nasce no
 * signup: aqui a unidade é a <b>empresa</b> e a linha só existe depois que alguém configura —
 * ausência significa "fiscal desligado", que é como o F12 se cumpre.
 */
package com.vetor.niner.fiscal.configuracao;
