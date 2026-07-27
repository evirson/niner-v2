package com.vetor.niner.comum.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Tradução de exceções para Problem Details (RFC 9457), convenção de erro da API
 * (spec §3.4). O tratamento das exceções padrão de MVC já vem do Spring
 * ({@code spring.mvc.problemdetails.enabled=true}); aqui ficam os casos de domínio.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail tratarEstadoInvalido(IllegalStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Estado inválido");
        pd.setType(URI.create("urn:niner:erro:estado-invalido"));
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail tratarArgumentoInvalido(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Dado inválido");
        pd.setType(URI.create("urn:niner:erro:validacao"));
        return pd;
    }

    @ExceptionHandler(ConflitoDadosException.class)
    public ProblemDetail tratarConflito(ConflitoDadosException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Conflito de dados");
        pd.setType(URI.create("urn:niner:erro:conflito"));
        return pd;
    }

    /**
     * Rede de segurança: se algum serviço deixar de checar manualmente uma FK antes de excluir
     * (a checagem de {@code ConflitoDadosException} é sempre uma pré-verificação enumerada à
     * mão — ver {@code PlanoContasService}), o próprio banco rejeita e o JDBC lança esta
     * exceção. Sem este handler ela caía no 500 genérico do Spring, sem {@code detail}/{@code
     * title} — o front então mostrava só "Ocorreu um erro.", indistinguível de uma falha real.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail tratarViolacaoDeIntegridade(DataIntegrityViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Registro em uso por outro cadastro — não pode ser excluído.");
        pd.setTitle("Conflito de dados");
        pd.setType(URI.create("urn:niner:erro:conflito"));
        return pd;
    }
}
