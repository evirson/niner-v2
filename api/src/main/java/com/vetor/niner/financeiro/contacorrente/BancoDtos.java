package com.vetor.niner.financeiro.contacorrente;

/** DTO da referência de bancos brasileiros ({@code cfg_banco}, tabela global — ver package-info). */
public final class BancoDtos {

    private BancoDtos() {
    }

    public record BancoResponse(String codigoBanco, String nomeBanco) {
    }
}
