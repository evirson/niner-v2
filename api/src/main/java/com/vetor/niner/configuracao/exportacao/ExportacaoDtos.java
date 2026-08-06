package com.vetor.niner.configuracao.exportacao;

/** DTOs da Rotina de Exportação de Dados (Configurações). */
public final class ExportacaoDtos {

    private ExportacaoDtos() {
    }

    public record TabelaExportavel(String chave, String titulo) {
    }
}
