/**
 * Uso e cota do tenant no control-plane (ADR-015): quantas vendas o tenant emitiu na competência,
 * qual o limite do plano e o painel <i>Minha Conta</i> que o lojista enxerga.
 *
 * <p><b>Única travessia domínio → plataforma permitida (P9).</b> O domínio (venda, no PDV)
 * escreve num contador que é de plataforma; isso fica confinado aqui, em
 * {@link com.vetor.niner.plataforma.uso.LimiteVendasService}. Nenhum outro serviço de domínio
 * deve tocar em {@code plataforma.*}.
 */
package com.vetor.niner.plataforma.uso;
