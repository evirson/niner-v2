package com.vetor.niner.plataforma.aquisicao;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** DTOs do funil de aquisição (ADR-017). */
public final class AquisicaoDtos {

    private AquisicaoDtos() {
    }

    /** Origem da primeira visita, mandada pelo beacon em todo lote. */
    public record Origem(
            @Size(max = 120) String utmSource,
            @Size(max = 120) String utmMedium,
            @Size(max = 160) String utmCampaign,
            @Size(max = 160) String utmContent,
            @Size(max = 160) String utmTerm,
            @Size(max = 500) String referrer,
            @Size(max = 300) String paginaEntrada) {
    }

    public record EventoBeacon(
            @Size(max = 40) String tipo,
            @Size(max = 300) String rotulo,
            @Size(max = 300) String caminho,
            java.math.BigDecimal valor) {
    }

    /** Lote do beacon. {@code visitanteId} é o UUID do cookie first-party. */
    public record LoteEventosRequest(
            String visitanteId, String sessaoId, Origem origem, List<EventoBeacon> eventos) {
    }

    /** Formulário do site ("quero saber mais") — cria/atualiza o lead com consentimento. */
    public record LeadRequest(
            @NotBlank @Size(max = 120) String nome,
            @NotBlank @Email @Size(max = 160) String email,
            @Size(max = 20) String telefoneWhatsapp,
            @Size(max = 120) String nomeLoja,
            String visitanteId) {
    }
}
