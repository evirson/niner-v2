package com.vetor.niner.plataforma.tenants;

import com.vetor.niner.plataforma.tenants.TenantAdminDtos.PaginaTenants;
import com.vetor.niner.plataforma.tenants.TenantAdminDtos.TenantDetalhe;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/** Contas assinantes no backoffice (R17) — qualquer papel de staff lê. */
@RestController
@RequestMapping("/api/admin/tenants")
public class TenantAdminController {

    private final TenantAdminService service;

    public TenantAdminController(TenantAdminService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaTenants listar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "50") int limite) {
        exigirStaff(jwt);
        return service.listar(busca, status, pagina, limite);
    }

    @GetMapping("/{id}")
    public TenantDetalhe detalhar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        exigirStaff(jwt);
        return service.detalhar(id);
    }

    private static void exigirStaff(Jwt jwt) {
        if (jwt.getClaimAsString("papel") == null) {
            throw new ResponseStatusException(FORBIDDEN, "Somente o staff da plataforma.");
        }
    }
}
