package com.vetor.niner.comum.web;

/**
 * Erro de negócio mapeado para {@code 409 Conflict} (Problem Details) — ex.: violação de
 * unicidade traduzida para uma mensagem amigável, em vez do erro cru de constraint.
 */
public class ConflitoDadosException extends RuntimeException {

    public ConflitoDadosException(String message) {
        super(message);
    }
}
