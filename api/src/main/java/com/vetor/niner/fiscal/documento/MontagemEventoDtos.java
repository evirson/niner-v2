package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.MontagemNfceDtos.AmbienteSefaz;

import java.time.OffsetDateTime;

/** DTOs da montagem de eventos (§10.1, bloco B8) — hoje só cancelamento (110111). */
public final class MontagemEventoDtos {

    private MontagemEventoDtos() {
    }

    /**
     * Tudo que {@link MontadorEventoCancelamento} precisa. {@code cnpjAutor}/{@code uf} são do
     * <b>emitente</b> — o evento é sempre autorado por quem emitiu a nota, nunca pelo
     * destinatário (não existe "consumidor cancela sua própria compra" na NFC-e).
     */
    public record EventoCancelamento(
            AmbienteSefaz ambiente,
            String chaveAcesso,
            String cnpjAutor,
            String uf,
            OffsetDateTime dataEvento,
            String protocoloAutorizacao,
            String justificativa) {
    }

    public record XmlEventoMontado(String id, String xml) {
    }
}
