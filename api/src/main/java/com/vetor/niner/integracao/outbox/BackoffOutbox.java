package com.vetor.niner.integracao.outbox;

import java.time.Duration;

/**
 * Quanto esperar antes de tentar de novo, e quando desistir (spec §3.5: <i>"retry exponencial
 * (1 min → 2 → 4… máx 1 h) com dead-letter visível no painel"</i>).
 *
 * <p>Classe própria, e não um {@code Math.pow} solto no worker, por dois motivos: é a regra que
 * decide quanto tempo o anúncio de um lojista fica dessincronizado, e é aritmética — dá para
 * prender por teste sem subir Spring nem banco.
 */
public final class BackoffOutbox {

    /** Primeira espera. */
    private static final Duration BASE = Duration.ofMinutes(1);

    /**
     * Teto por tentativa. Sem teto, a 12ª tentativa esperaria 34 dias — o evento não estaria
     * morto, mas seria indistinguível de morto para quem olha a tela.
     */
    private static final Duration TETO = Duration.ofHours(1);

    /**
     * Depois disto, dead-letter.
     *
     * <p>Com 1→2→4→8→16→32→60→60…, <b>12 tentativas cobrem 6h03</b> de indisponibilidade do
     * canal — folgado para queda de rede ou manutenção do marketplace, e curto o bastante para
     * que um erro de verdade apareça no painel no mesmo dia, em vez de girar a semana inteira
     * escondendo a causa atrás de centenas de tentativas idênticas.
     *
     * <p>⚠️ <b>Este número saiu de uma conta que eu errei primeiro.</b> O javadoc dizia "10
     * tentativas ≈ 6 horas"; a soma real de 10 é <b>4h03</b>, e quem pegou foi
     * {@code BackoffOutboxTest.asTentativasCobremSeisHorasDeCanalFora} — teste escrito justamente
     * porque um número desses apodrece calado. Ao mexer aqui, aquele teste reprova: é ele que
     * impede a janela real de divergir da janela documentada.
     */
    public static final int MAXIMO_TENTATIVAS = 12;

    private BackoffOutbox() {
    }

    /**
     * @param tentativasJaFeitas quantas falhas este evento já acumulou ({@code 0} na primeira)
     * @return quanto esperar, ou {@code null} quando é hora de desistir e mandar para o
     *         dead-letter — que <b>não é perda</b>: o evento fica visível no painel de saúde (R7)
     *         para reprocessamento manual
     */
    public static Duration proximaEspera(int tentativasJaFeitas) {
        if (tentativasJaFeitas + 1 >= MAXIMO_TENTATIVAS) {
            return null;
        }
        // Deslocamento de bits em vez de Math.pow: exato, sem ponto flutuante, e o expoente é
        // limitado antes para não estourar o long num contador estragado.
        int expoente = Math.min(Math.max(tentativasJaFeitas, 0), 20);
        Duration espera = BASE.multipliedBy(1L << expoente);
        return espera.compareTo(TETO) > 0 ? TETO : espera;
    }
}
