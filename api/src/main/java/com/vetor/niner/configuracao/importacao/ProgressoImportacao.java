package com.vetor.niner.configuracao.importacao;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Estado mutável e thread-safe do progresso de UMA importação em andamento (2026-08-11) —
 * escrito pela thread que processa o arquivo (via {@link ImportacaoProgressoContext}), lido pela
 * thread do polling do frontend (via {@link ImportacaoProgressoRegistry}, que segura a mesma
 * instância sob o {@code idProgresso}). {@code etapa}/{@code total} são {@code volatile} e
 * {@code atual} é {@link AtomicInteger} — dá visibilidade entre as duas threads sem precisar de
 * lock (o padrão de leitura é "melhor esforço": um polling levemente atrasado não é um problema).
 */
public final class ProgressoImportacao {

    private volatile String etapa = "leitura";
    private volatile int total;
    private final AtomicInteger atual = new AtomicInteger(0);

    void etapa(String etapa, int total) {
        this.atual.set(0);
        this.total = total;
        this.etapa = etapa;
    }

    void avancar() {
        atual.incrementAndGet();
    }

    public String etapa() {
        return etapa;
    }

    public int total() {
        return total;
    }

    public int atual() {
        return atual.get();
    }
}
