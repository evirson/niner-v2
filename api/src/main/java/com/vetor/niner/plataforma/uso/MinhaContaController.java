package com.vetor.niner.plataforma.uso;

import com.vetor.niner.plataforma.uso.MinhaContaDtos.MinhaContaResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Painel do assinante (ADMIN-only) — plano, cota do mês, histórico e empresas do tenant.
 * Superfície do tenant ({@code /api/v1}), tenant sempre vindo do claim {@code tid} do JWT.
 */
@RestController
@RequestMapping("/api/v1/minha-conta")
public class MinhaContaController {

    private final MinhaContaService service;

    public MinhaContaController(MinhaContaService service) {
        this.service = service;
    }

    @GetMapping
    public MinhaContaResponse consultar(@AuthenticationPrincipal Jwt jwt) {
        return service.consultar(jwt);
    }
}
