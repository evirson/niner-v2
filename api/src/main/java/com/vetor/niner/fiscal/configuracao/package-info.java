/**
 * Configuração fiscal por empresa — CRT, regime de apuração, série, ambiente, CSC e os gates
 * {@code emite_nfce}/{@code emite_nfe} (docs/telas/fiscal-configuracao.md, bloco B2 de
 * docs/MODULOFISCAL.md §17.1).
 *
 * <p>É a base que todo o resto do módulo fiscal lê: o motor tributário tira daqui o CRT e o
 * regime (DF36 — a alíquota de PIS/COFINS vem do <b>regime da empresa</b>, não do perfil fiscal
 * do produto, porque CRT 3 cobre Lucro Presumido e Lucro Real ao mesmo tempo), e a emissão tira
 * daqui a série, o ambiente e a permissão de emitir.
 *
 * <p>Diferente de {@code configuracao.geral}, que é singleton por <b>tenant</b> e nasce no
 * signup: aqui a unidade é a <b>empresa</b> e a linha só existe depois que alguém configura —
 * ausência significa "fiscal desligado", que é como o F12 se cumpre.
 */
package com.vetor.niner.fiscal.configuracao;
