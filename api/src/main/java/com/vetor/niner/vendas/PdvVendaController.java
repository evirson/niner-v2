package com.vetor.niner.vendas;

import com.vetor.niner.vendas.PdvDtos.EfetivarVendaRequest;
import com.vetor.niner.vendas.PdvDtos.VendaEfetivadaResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Efetivação da venda do PDV — F5 (docs/telas/pdv.md). */
@RestController
@RequestMapping("/api/v1/pdv/vendas")
public class PdvVendaController {

    private final PdvVendaService service;

    public PdvVendaController(PdvVendaService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<VendaEfetivadaResponse> efetivar(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EfetivarVendaRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.efetivarVenda(jwt, req));
    }
}
