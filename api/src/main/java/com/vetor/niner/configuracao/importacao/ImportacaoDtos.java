package com.vetor.niner.configuracao.importacao;

import java.util.List;

/** DTOs da Rotina de Importação de Dados (docs/telas/importacao-dados.md). */
public final class ImportacaoDtos {

    private ImportacaoDtos() {
    }

    public record TabelaImportavel(String chave, String titulo, String descricao) {
    }

    public record LinhaErro(int linha, String motivo) {
    }

    /**
     * Resultado de uma execução — prévia (dry-run, {@code confirmado=false}) ou gravação de
     * verdade ({@code confirmado=true}). {@code pendencias} preenchido significa que o
     * importador precisa de mais alguma escolha do usuário antes de conseguir processar (ex.:
     * mapear coluna de estoque para empresa) — nesse caso nenhuma linha foi processada ainda;
     * os demais contadores vêm zerados e o front deve resolver as pendências e reenviar o
     * mesmo arquivo com as escolhas completas.
     */
    public record RelatorioImportacao(
            boolean confirmado,
            int totalLinhas,
            int linhasImportadas,
            int linhasIgnoradas,
            int linhasRejeitadas,
            List<LinhaErro> erros,
            List<String> avisos,
            List<String> pendencias) {

        public static RelatorioImportacao pendente(List<String> pendencias) {
            return new RelatorioImportacao(false, 0, 0, 0, 0, List.of(), List.of(), pendencias);
        }
    }
}
