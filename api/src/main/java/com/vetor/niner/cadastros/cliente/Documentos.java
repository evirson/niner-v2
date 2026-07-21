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
     * Mantém dígitos e letras (maiúsculas), removendo máscara/pontuação — usado pelo CNPJ
     * alfanumérico (Receita Federal, IN RFB 2.229/2024, vigente a partir de julho/2026): as
     * 12 primeiras posições (raiz+ordem) podem ser letras A-Z ou dígitos; só os 2 dígitos
     * verificadores finais continuam numéricos. CPF não entra nessa mudança.
     */
    static String somenteAlfanumerico(String valor) {
        return valor == null ? null : valor.toUpperCase(java.util.Locale.ROOT).replaceAll("[^0-9A-Z]", "");
    }

    /**
     * {@code true} se {@code documento} é nulo/vazio (campo opcional) OU se, preenchido,
     * tem 11 dígitos (CPF) ou 14 caracteres (CNPJ, alfanumérico nas 12 primeiras posições)
     * com dígito verificador correto.
     */
    static boolean valido(String documento) {
        String d = somenteAlfanumerico(documento);
        if (d == null || d.isBlank()) {
            return true;
        }
        if (d.length() == 11 && d.chars().allMatch(Character::isDigit)) {
            return cpfValido(d);
        }
        if (d.length() == 14) {
            return cnpjValido(d);
        }
        return false;
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

    /**
     * CNPJ alfanumérico (Receita Federal, a partir de julho/2026): as 12 primeiras posições
     * podem ser 0-9 ou A-Z; os 2 dígitos verificadores finais (posições 13-14) continuam
     * sempre numéricos. Algoritmo (pesos e módulo 11) não mudou — só a tabela de valor por
     * caractere ficou mais ampla ({@link #digitos}); CNPJs só-numéricos (formato antigo)
     * continuam válidos.
     */
    private static boolean cnpjValido(String cnpj) {
        if (!cnpj.substring(12).chars().allMatch(Character::isDigit)) {
            return false;
        }
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

    /**
     * Valor de cada caractere: código ASCII menos 48 — dígitos '0'-'9' viram 0-9 (o próprio
     * valor, já que '0' é ASCII 48) e letras 'A'-'Z' viram 17-42. Fórmula oficial do CNPJ
     * alfanumérico; confirmada com o exemplo da Receita Federal "12.ABC.345/01DE-35" (soma
     * ponderada bate com DV 35). Também é exatamente o que o CPF/CNPJ numérico já fazia
     * (dígito '0'-'9' menos '0' = 0-9), então não muda nada para documentos antigos.
     */
    private static int[] digitos(String s) {
        return s.chars().map(c -> c - '0').toArray();
    }
}
