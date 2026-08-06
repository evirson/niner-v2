package com.vetor.niner.configuracao.importacao;

import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;

/**
 * Sinaliza o fim de uma execução em modo prévia ({@code confirmar=false}) — o relatório já foi
 * todo montado dentro da transação, mas nada deve ser persistido. Lançada de propósito no fim
 * do método {@code @Transactional} de cada {@link ImportadorDeTabela} para acionar o rollback
 * automático do Spring (equivalente a rodar a importação de verdade e desfazer no fim), e
 * capturada por {@link ImportacaoService} — nunca deve "vazar" como erro real para o usuário.
 */
final class SimulacaoConcluidaException extends RuntimeException {

    private final RelatorioImportacao relatorio;

    SimulacaoConcluidaException(RelatorioImportacao relatorio) {
        super("Simulação concluída (rollback intencional, não é um erro).");
        this.relatorio = relatorio;
    }

    RelatorioImportacao relatorio() {
        return relatorio;
    }
}
