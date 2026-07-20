package com.vetor.niner.cadastros.cliente;

/**
 * Validação de CPF/CNPJ (dígito verificador) para o cadastro de cliente
 * (docs/telas/cliente.md — CPF/CNPJ é opcional, mas quando preenchido precisa ser válido).
 */
final class Documentos {

    private Documentos() {
    }

    /** Remove tudo que não for dígito (máscara, pontuação). {@code null} vira {@code null}. */
    static String somenteDigitos(String valor) {
        return valor == null ? null : valor.replaceAll("\\D", "");
    }

    /**
     * {@code true} se {@code documento} é nulo/vazio (campo opcional) OU se, preenchido,
     * tem 11 dígitos (CPF) ou 14 (CNPJ) com dígito verificador correto.
     */
    static boolean valido(String documento) {
        String d = somenteDigitos(documento);
        if (d == null || d.isBlank()) {
            return true;
        }
        return switch (d.length()) {
            case 11 -> cpfValido(d);
            case 14 -> cnpjValido(d);
            default -> false;
        };
    }

    private static boolean cpfValido(String cpf) {
        if (todosDigitosIguais(cpf)) {
            return false;
        }
        int[] d = digitos(cpf);
        int soma = 0;
        for (int i = 0; i < 9; i++) {
            soma += d[i] * (10 - i);
        }
        int dv1 = digitoVerificador(soma);
        if (d[9] != dv1) {
            return false;
        }
        soma = 0;
        for (int i = 0; i < 10; i++) {
            soma += d[i] * (11 - i);
        }
        int dv2 = digitoVerificador(soma);
        return d[10] == dv2;
    }

    private static boolean cnpjValido(String cnpj) {
        if (todosDigitosIguais(cnpj)) {
            return false;
        }
        int[] d = digitos(cnpj);
        int[] pesos1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int soma = 0;
        for (int i = 0; i < 12; i++) {
            soma += d[i] * pesos1[i];
        }
        int dv1 = digitoVerificador(soma);
        if (d[12] != dv1) {
            return false;
        }
        int[] pesos2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        soma = 0;
        for (int i = 0; i < 13; i++) {
            soma += d[i] * pesos2[i];
        }
        int dv2 = digitoVerificador(soma);
        return d[13] == dv2;
    }

    private static int digitoVerificador(int somaPonderada) {
        int resto = somaPonderada % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private static boolean todosDigitosIguais(String d) {
        return d.chars().distinct().count() == 1;
    }

    private static int[] digitos(String s) {
        return s.chars().map(c -> c - '0').toArray();
    }
}
