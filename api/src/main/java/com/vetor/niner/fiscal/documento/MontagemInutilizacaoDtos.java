package com.vetor.niner.fiscal.documento;

/** DTOs da montagem de inutilização de numeração (§10.4, bloco B8) — {@code inutNFe_v4.00.xsd}. */
public final class MontagemInutilizacaoDtos {

    private MontagemInutilizacaoDtos() {
    }

    /**
     * O que a inutilização precisa para virar XML. {@code ano} é o ano corrente da solicitação
     * (não o ano das notas), como manda o leiaute.
     */
    public record PedidoInutilizacao(
            MontagemNfceDtos.AmbienteSefaz ambiente,
            String uf,
            String cnpjEmitente,
            int ano,
            int modelo,
            int serie,
            int numeroInicial,
            int numeroFinal,
            String justificativa) {
    }

    public record XmlInutilizacaoMontado(String id, String xml) {
    }
}
