package com.vetor.niner.plataforma.aquisicao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do gerenciador de marketing (backoffice da Vetor) — docs/telas/admin-marketing.md. */
public final class MarketingAdminDtos {

    private MarketingAdminDtos() {
    }

    /**
     * Escada do funil no período. Cada degrau é uma contagem de <b>visitantes distintos</b>, não
     * de eventos — senão quem recarrega a página vira "10 visitantes".
     */
    public record Funil(
            LocalDate de, LocalDate ate,
            long visitas, long visitantes, long leads, long contas, long contasComVenda, long pagantes,
            BigDecimal mrr, List<LinhaOrigem> porOrigem) {
    }

    /** Uma origem/campanha e o que ela produziu — inclusive receita, que é o ponto. */
    public record LinhaOrigem(
            String origem, String campanha, long visitantes, long leads, long contas, long pagantes, BigDecimal mrr) {
    }

    public record LeadResumo(
            long idLead, String nome, String email, String telefoneWhatsapp, String nomeLoja,
            String origem, String campanha, String status, Long idTenant, String nomeConta,
            OffsetDateTime criadoEm) {
    }

    public record PaginaLeads(List<LeadResumo> itens, long total, int pagina, int limite) {
    }

    /** Linha do tempo de um visitante: o que ele viu e onde clicou, em ordem. */
    public record MomentoLead(OffsetDateTime quando, String tipo, String detalhe) {
    }

    public record LeadDetalhe(LeadResumo lead, List<MomentoLead> linhaDoTempo) {
    }

    public record AtualizarLeadRequest(String status, String anotacao) {
    }

    /** Conta gratuita perto de estourar a cota — a lista de quem está prestes a precisar pagar. */
    public record ContaPertoDoLimite(
            long idTenant, String nomeConta, String emailContato, int qtdVendas, int limite, int percentual) {
    }
}
