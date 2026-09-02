package com.vetor.niner.plataforma.acesso;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recusa de login que <b>carrega o motivo</b> para o log de acesso.
 *
 * <p><b>Por que não bastava a exceção de sempre</b> (2026-09-01): o controller precisa saber
 * <i>por que</i> o login foi recusado para gravar a linha certa, e a única alternativa seria
 * deduzir isso da <b>mensagem</b> — que é a família da constante literal já catalogada aqui: no dia
 * em que alguém melhorar o texto de "Credenciais inválidas.", a classificação passa a errar
 * <b>em silêncio</b>, e ninguém liga uma coisa à outra.
 *
 * <p>⚠️ Continua sendo uma {@link ResponseStatusException}: o {@code GlobalExceptionHandler}
 * segue tratando igual, o status e a mensagem que chegam ao usuário <b>não mudam</b>, e nenhum
 * comportamento do login foi alterado — só passou a existir um campo a mais para quem grava o log.
 *
 * <p>⚠️ E o motivo <b>não vaza para o cliente</b>: a resposta HTTP continua sendo a mensagem única
 * ("Credenciais inválidas."), justamente para não virar oráculo. O {@link ResultadoAcesso} só
 * atravessa até o log.
 */
public class LoginRecusadoException extends ResponseStatusException {

    private final transient ResultadoAcesso resultado;

    public LoginRecusadoException(HttpStatus status, String mensagem, ResultadoAcesso resultado) {
        super(status, mensagem);
        this.resultado = resultado;
    }

    public ResultadoAcesso resultado() {
        return resultado;
    }
}
