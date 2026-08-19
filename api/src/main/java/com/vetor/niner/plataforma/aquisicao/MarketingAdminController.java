package com.vetor.niner.plataforma.aquisicao;

import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.AtualizarLeadRequest;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.ContaPertoDoLimite;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.Funil;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.LeadDetalhe;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.PaginaLeads;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Gerenciador de marketing da plataforma (ADR-017) — superfície {@code /api/admin/**}, staff da
 * Vetor. O app de backoffice ainda não existe; os endpoints vêm antes porque o dado precisa ser
 * consultável desde o primeiro visitante (docs/telas/admin-marketing.md).
 *
 * <p>⚠️ A cadeia de {@code /api/admin/**} ainda é {@code permitAll} (TODO(jwt) em
 * {@code SegurancaConfig}) — quando o JWT de staff existir, estas rotas passam a exigir
 * {@code aud=plataforma}, sem mudança aqui.
 */
@RestController
@RequestMapping("/api/admin/marketing")
public class MarketingAdminController {

    private final MarketingAdminService service;

    public MarketingAdminController(MarketingAdminService service) {
        this.service = service;
    }

    @GetMapping("/funil")
    public Funil funil(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return service.funil(de, ate);
    }

    @GetMapping("/leads")
    public PaginaLeads leads(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String origem,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "50") int limite) {
        return service.listarLeads(status, origem, pagina, limite);
    }

    @GetMapping("/leads/{id}")
    public LeadDetalhe lead(@PathVariable long id) {
        return service.detalharLead(id);
    }

    @PutMapping("/leads/{id}")
    public void atualizar(@PathVariable long id, @RequestBody AtualizarLeadRequest req) {
        service.atualizarLead(id, req);
    }

    /** Contas gratuitas prestes a estourar a cota — a fila do contato comercial (ADR-015). */
    @GetMapping("/contas-perto-do-limite")
    public List<ContaPertoDoLimite> contasPertoDoLimite() {
        return service.contasPertoDoLimite();
    }
}
