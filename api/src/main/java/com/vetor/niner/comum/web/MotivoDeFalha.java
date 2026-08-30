package com.vetor.niner.comum.web;

import org.springframework.web.server.ResponseStatusException;

/**
 * A mensagem de uma exceção como o <b>operador</b> deve lê-la.
 *
 * <p>⛔ <b>{@code ResponseStatusException.getMessage()} NÃO é a mensagem</b>: ele concatena o status
 * e põe o motivo entre aspas, devolvendo algo como
 * {@code 409 CONFLICT "A NF-e de devolução precisa do endereço completo do cliente…"}. Quando esse
 * texto vai parar num campo que a tela pinta em vermelho, o operador lê um código HTTP no meio de
 * uma frase sobre cadastro de cliente.
 *
 * <p>Isso passou despercebido na correção de 2026-08-30 que preservou o vale-mercadoria quando a
 * NF-e falha — a correção estava certa no que importava (a resposta deixou de se perder) e errada
 * no texto que ela mesma criou. Quem lê é `getReason()`; `getMessage()` é para log.
 *
 * <p>⚠️ Só use onde a mensagem vai para o <b>corpo de uma resposta de sucesso</b> (um campo do tipo
 * "a operação foi feita, mas o efeito secundário falhou"). Quando a exceção deve virar a resposta
 * inteira, quem traduz é o {@link GlobalExceptionHandler}.
 */
public final class MotivoDeFalha {

    private MotivoDeFalha() {
    }

    public static String legivel(Throwable e, String padrao) {
        if (e instanceof ResponseStatusException rse && rse.getReason() != null && !rse.getReason().isBlank()) {
            return rse.getReason();
        }
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? padrao : msg;
    }
}
