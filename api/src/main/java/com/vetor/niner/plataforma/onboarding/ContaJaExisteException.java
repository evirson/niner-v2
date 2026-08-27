package com.vetor.niner.plataforma.onboarding;

/**
 * O e-mail informado no cadastro já tem conta no Nainer.
 *
 * <p><b>Por que virou exceção própria em 2026-08-27.</b> Antes era um 409 genérico que só dizia
 * "já existe uma conta com este e-mail" e encerrava o assunto. Mas esse é justamente o momento em
 * que o cliente precisa decidir alguma coisa — ele pode estar contratando uma <b>segunda empresa</b>,
 * e aí a pergunta certa é se ela entra no grupo que ele já tem ou num grupo separado. Um tipo
 * próprio deixa a tela de contratação reconhecer a situação e fazer a pergunta, em vez de mostrar
 * um erro e deixar a pessoa sem saída.
 *
 * <p>⚠️ <b>A resposta não diz o NOME da conta existente nem quantas são.</b> Nesse ponto do fluxo
 * ninguém provou ser dono do e-mail — quem digitasse o e-mail de terceiros descobriria onde a
 * pessoa é cliente. O que a tela mostra depois disso só aparece <b>após o login</b>.
 */
public class ContaJaExisteException extends RuntimeException {

    public ContaJaExisteException(String mensagem) {
        super(mensagem);
    }
}
