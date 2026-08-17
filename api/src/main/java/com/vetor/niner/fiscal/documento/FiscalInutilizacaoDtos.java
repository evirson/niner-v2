package com.vetor.niner.fiscal.documento;

import java.time.OffsetDateTime;

/** DTOs da API de inutilização de numeração (§10.4/§12, bloco B8). */
public final class FiscalInutilizacaoDtos {

    private FiscalInutilizacaoDtos() {
    }

    public record InutilizacaoRequest(long idEmpresa, int modelo, int serie,
                                      int numeroInicial, int numeroFinal, String justificativa) {
    }

    public record InutilizacaoResponse(String protocolo, int ano) {
    }

    public record FaixaBuracoResponse(int numeroInicial, int numeroFinal) {
    }

    public record InutilizacaoItemResponse(int modelo, int serie, int ano, int numeroInicial,
                                           int numeroFinal, String justificativa, boolean autorizado,
                                           String protocolo, String motivoSefaz, OffsetDateTime criadoEm) {
    }
}
