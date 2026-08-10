package com.vetor.niner.configuracao.importacao;

/**
 * Progresso "ao vivo" (etapa/registro atual/total) da importação em andamento no thread atual
 * (2026-08-11) — mesmo espírito de {@code TenantContext} (P8): um {@link ScopedValue} ligado só
 * durante a execução de {@link ImportacaoService#processar}, lido por código profundo
 * ({@link ImportacaoPlanilha}, cada {@link ImportadorDeTabela}) sem precisar passar o objeto por
 * parâmetro em toda assinatura. O {@link ProgressoImportacao} em si também fica registrado em
 * {@link ImportacaoProgressoRegistry} sob o mesmo id — é isso que permite uma requisição
 * diferente (o polling do frontend) ler o progresso a partir de outra thread. Todo método aqui é
 * um no-op se não há progresso ligado ao contexto (ex.: chamada sem {@code idProgresso}).
 */
public final class ImportacaoProgressoContext {

    private static final ScopedValue<ProgressoImportacao> PROGRESSO = ScopedValue.newInstance();

    private ImportacaoProgressoContext() {
    }

    public static <R, X extends Throwable> R comProgresso(ProgressoImportacao progresso,
                                                            ScopedValue.CallableOp<? extends R, X> op) throws X {
        return ScopedValue.where(PROGRESSO, progresso).call(op);
    }

    /** Marca o início de uma etapa ({@code "leitura"}/{@code "validando"}/{@code "importando"})
     *  com o total de registros já conhecido, zerando o contador de "atual". */
    public static void etapa(String etapa, int total) {
        if (PROGRESSO.isBound()) {
            PROGRESSO.get().etapa(etapa, total);
        }
    }

    /** Avança uma unidade — um registro processado (lido, validado ou importado). */
    public static void avancar() {
        if (PROGRESSO.isBound()) {
            PROGRESSO.get().avancar();
        }
    }
}
