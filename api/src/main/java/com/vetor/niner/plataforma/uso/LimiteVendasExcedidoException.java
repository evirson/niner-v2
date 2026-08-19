package com.vetor.niner.plataforma.uso;

import java.math.BigDecimal;

/**
 * Cota de vendas do mês esgotada, tolerância inclusive (ADR-015) — vira 409 Problem Details com
 * os números e a faixa recomendada, para a tela oferecer o upgrade em vez de só dizer "erro".
 */
public class LimiteVendasExcedidoException extends RuntimeException {

    private final int usadas;
    private final int limite;
    private final int tolerancia;
    private final String faixaRecomendada;
    private final BigDecimal precoMensalRecomendado;

    public LimiteVendasExcedidoException(
            int usadas, int limite, int tolerancia, String faixaRecomendada, BigDecimal precoMensalRecomendado) {
        super("Limite de " + limite + " vendas/mês do seu plano foi atingido"
                + (tolerancia > 0 ? " (mais " + tolerancia + " de tolerância)" : "")
                + ". Assine uma faixa para continuar vendendo — nenhum dado é perdido.");
        this.usadas = usadas;
        this.limite = limite;
        this.tolerancia = tolerancia;
        this.faixaRecomendada = faixaRecomendada;
        this.precoMensalRecomendado = precoMensalRecomendado;
    }

    public int usadas() {
        return usadas;
    }

    public int limite() {
        return limite;
    }

    public int tolerancia() {
        return tolerancia;
    }

    public String faixaRecomendada() {
        return faixaRecomendada;
    }

    public BigDecimal precoMensalRecomendado() {
        return precoMensalRecomendado;
    }
}
