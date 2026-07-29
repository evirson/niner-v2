package com.vetor.niner.estoque.transferencia;

import com.vetor.niner.estoque.transferencia.TransferenciaDtos.CriarTransferenciaRequest;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.PaginaTransferencias;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.TransferenciaResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Transferência de estoque entre empresas (docs/telas/transferencia-estoque.md), superfície do
 * tenant (`/api/v1`, JWT + RLS). ADMIN e OPERADOR têm acesso completo.
 */
@RestController
@RequestMapping("/api/v1/estoque/transferencias")
public class TransferenciaController {

    private final TransferenciaService service;

    public TransferenciaController(TransferenciaService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaTransferencias listar(
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer limite) {
        return service.listar(pagina, limite);
    }

    @GetMapping("/{id}")
    public TransferenciaResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferenciaResponse criar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CriarTransferenciaRequest req) {
        return service.criar(jwt, req);
    }
}
