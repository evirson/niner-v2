package com.vetor.niner.fiscal.perfil;

import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.ExclusaoPerfilFiscalResponse;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.PaginaPerfisFiscais;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.PerfilFiscalOpcaoResponse;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.PerfilFiscalRequest;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.PerfilFiscalResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Perfis Fiscais (docs/telas/fiscal-perfil.md), superfície do tenant (`/api/v1`, JWT + RLS).
 * Todas as rotas ADMIN-only, exceto {@code /opcoes} — verificado no serviço.
 */
@RestController
@RequestMapping("/api/v1/fiscal/perfis")
public class PerfilFiscalController {

    private final PerfilFiscalService service;

    public PerfilFiscalController(PerfilFiscalService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaPerfisFiscais listar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(jwt, busca, pagina, limite, ordenarPor, direcao);
    }

    /** Sem checagem de papel — alimenta o `<select>` de Produto, operado por OPERADOR. */
    @GetMapping("/opcoes")
    public List<PerfilFiscalOpcaoResponse> listarOpcoes() {
        return service.listarOpcoes();
    }

    @GetMapping("/{id}")
    public PerfilFiscalResponse buscar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.buscar(jwt, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PerfilFiscalResponse criar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PerfilFiscalRequest req) {
        return service.criar(jwt, req);
    }

    @PutMapping("/{id}")
    public PerfilFiscalResponse atualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                          @Valid @RequestBody PerfilFiscalRequest req) {
        return service.atualizar(jwt, id, req);
    }

    @DeleteMapping("/{id}")
    public ExclusaoPerfilFiscalResponse excluir(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.excluir(jwt, id);
    }
}
