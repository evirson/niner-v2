package com.vetor.niner.configuracao.importacao;

/**
 * Erro de conversão de um campo específico do CSV (data/decimal/inteiro inválidos) — carrega o
 * nome da coluna e o valor cru recebido separados da mensagem, pra a tela de prévia mostrar
 * "Campo" e "Valor recebido" em colunas próprias, não só embutidos numa frase (2026-08-06,
 * pedido do dono do produto). {@code getMessage()} continua com o texto completo (defesa em
 * profundidade — qualquer código que capture isto genericamente como {@code RuntimeException}
 * e só use {@code getMessage()} ainda vê a informação toda).
 */
final class CampoInvalidoException extends IllegalArgumentException {

    private final String campo;
    private final String valor;

    CampoInvalidoException(String campo, String valor, String mensagem) {
        super(campo + ": " + mensagem + " — valor recebido: \"" + valor + "\".");
        this.campo = campo;
        this.valor = valor;
    }

    String campo() {
        return campo;
    }

    String valor() {
        return valor;
    }
}
