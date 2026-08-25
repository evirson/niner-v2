package com.vetor.niner;

import com.vetor.niner.integracao.outbox.BackoffOutbox;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backoff do outbox (spec §3.5: "1 min → 2 → 4… máx 1 h, com dead-letter visível no painel").
 *
 * <p>Teste puro: é a regra que decide quanto tempo o anúncio de um lojista fica dessincronizado,
 * e é aritmética — não precisa de Spring nem de banco para ficar presa.
 */
class BackoffOutboxTest {

    @Test
    void dobraAEsperaACadaFalha() {
        assertThat(BackoffOutbox.proximaEspera(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(BackoffOutbox.proximaEspera(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(BackoffOutbox.proximaEspera(2)).isEqualTo(Duration.ofMinutes(4));
        assertThat(BackoffOutbox.proximaEspera(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(BackoffOutbox.proximaEspera(4)).isEqualTo(Duration.ofMinutes(16));
        assertThat(BackoffOutbox.proximaEspera(5)).isEqualTo(Duration.ofMinutes(32));
    }

    /**
     * ⚠️ O teto importa: sem ele, a 12ª tentativa esperaria mais de um mês. O evento não estaria
     * morto, mas seria indistinguível de morto para quem olha a tela.
     */
    @Test
    void nuncaEsperaMaisQueUmaHora() {
        assertThat(BackoffOutbox.proximaEspera(6)).isEqualTo(Duration.ofHours(1));
        assertThat(BackoffOutbox.proximaEspera(7)).isEqualTo(Duration.ofHours(1));
        assertThat(BackoffOutbox.proximaEspera(8)).isEqualTo(Duration.ofHours(1));
    }

    /** {@code null} = dead-letter. Não é perda: fica visível no painel (R7) para reprocessar. */
    @Test
    void desistePorNullDepoisDoMaximoDeTentativas() {
        assertThat(BackoffOutbox.proximaEspera(BackoffOutbox.MAXIMO_TENTATIVAS - 2))
                .as("a penúltima ainda reagenda").isNotNull();
        assertThat(BackoffOutbox.proximaEspera(BackoffOutbox.MAXIMO_TENTATIVAS - 1))
                .as("a última vira dead-letter").isNull();
        assertThat(BackoffOutbox.proximaEspera(BackoffOutbox.MAXIMO_TENTATIVAS + 50))
                .as("contador estragado não ressuscita o evento").isNull();
    }

    /**
     * A janela total que o backoff cobre. Escrito como teste porque é a única forma de o número
     * não apodrecer: mudar {@code MAXIMO_TENTATIVAS} sem pensar na janela quebra aqui.
     */
    @Test
    void asTentativasCobremSeisHorasDeCanalFora() {
        Duration total = Duration.ZERO;
        for (int i = 0; i < BackoffOutbox.MAXIMO_TENTATIVAS; i++) {
            Duration espera = BackoffOutbox.proximaEspera(i);
            if (espera == null) {
                break;
            }
            total = total.plus(espera);
        }
        assertThat(total)
                .as("folgado para queda de rede, curto para o erro real aparecer no mesmo dia")
                .isEqualTo(Duration.ofMinutes(363));
    }

    /** Contador negativo (não deveria existir) não pode virar espera negativa nem estourar. */
    @Test
    void contadorNegativoNaoQuebra() {
        assertThat(BackoffOutbox.proximaEspera(-5)).isEqualTo(Duration.ofMinutes(1));
    }
}
