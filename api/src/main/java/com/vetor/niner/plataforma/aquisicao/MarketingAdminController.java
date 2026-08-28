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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * Gerenciador de marketing da plataforma (ADR-017) — superfície {@code /api/admin/**}, staff da
 * Vetor. O app de backoffice ainda não existe; os endpoints vêm antes porque o dado precisa ser
 * consultável desde o primeiro visitante (docs/telas/admin-marketing.md).
 *
 * <p>⚠️ <b>O javadoc anterior aqui estava obsoleto desde 2026-08-19</b> e dizia que a cadeia de
 * {@code /api/admin/**} ainda era {@code permitAll}. Ela exige {@code aud=plataforma} desde então
 * (R18/ADR-009, dois {@code JwtDecoder} separados) — mas <b>qualquer papel de staff</b> passava,
 * e este era o único controller de {@code /api/admin} sem a checagem que os irmãos fazem
 * (auditoria de segurança, 2026-08-27). Lead é dado pessoal de quem se cadastrou no site.
 */
@RestController
@RequestMapping("/api/admin/marketing")
public class MarketingAdminController {

    private final MarketingAdminService service;

    public MarketingAdminController(MarketingAdminService service) {
        this.service = service;
    }

    /**
     * Só staff da plataforma — mesma checagem de {@code BackupController} e
     * {@code ConfiguracaoPlataformaController}: o claim {@code papel} só existe em token de staff
     * (token de lojista não tem), então a ausência dele é a fronteira.
     */
    private static void exigirStaff(Jwt jwt) {
        if (jwt.getClaimAsString("papel") == null) {
            throw new ResponseStatusException(FORBIDDEN, "Somente o staff da plataforma.");
        }
    }

    @GetMapping("/funil")
    public Funil funil(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        exigirStaff(jwt);
        return service.funil(de, ate);
    }

    @GetMapping("/leads")
    public PaginaLeads leads(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String origem,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "50") int limite) {
        exigirStaff(jwt);
        return service.listarLeads(status, origem, pagina, limite);
    }

    @GetMapping("/leads/{id}")
    public LeadDetalhe lead(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        exigirStaff(jwt);
        return service.detalharLead(id);
    }

    @PutMapping("/leads/{id}")
    public void atualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody AtualizarLeadRequest req) {
        exigirStaff(jwt);
        service.atualizarLead(id, req);
    }

    /** Contas gratuitas prestes a estourar a cota — a fila do contato comercial (ADR-015). */
    @GetMapping("/contas-perto-do-limite")
    public List<ContaPertoDoLimite> contasPertoDoLimite(@AuthenticationPrincipal Jwt jwt) {
        exigirStaff(jwt);
        return service.contasPertoDoLimite();
    }
}
