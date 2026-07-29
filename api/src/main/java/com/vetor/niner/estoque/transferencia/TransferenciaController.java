package com.vetor.niner.estoque.transferencia;

import com.vetor.niner.estoque.transferencia.TransferenciaDtos.CriarTransferenciaRequest;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.PaginaTransferencias;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.TransferenciaResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

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
            @RequestParam(required = false) Long idTransferencia,
            @RequestParam(required = false) Long idEmpresaOrigem,
            @RequestParam(required = false) Long idEmpresaDestino,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(
                idTransferencia, idEmpresaOrigem, idEmpresaDestino, dataInicial, dataFinal, pagina, limite, ordenarPor, direcao);
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

    /**
     * Exclusão completa (pedido direto do dono do produto, 2026-07-29) — desfaz o efeito de
     * estoque nos dois lados e apaga o registro, mesmo que o destino não tenha mais saldo
     * suficiente pra "devolver" (a devolução acontece de qualquer forma, ver
     * {@link TransferenciaService#excluir(long)}).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable long id) {
        service.excluir(id);
    }
}
