package com.vetor.niner.financeiro.lucratividade;

import com.vetor.niner.financeiro.lucratividade.LucratividadeDtos.LucratividadeResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Relatório de Lucratividade (docs/telas/relatorio-lucratividade.md), superfície do tenant
 * (`/api/v1`, JWT + RLS). <b>ADMIN-only</b> — a checagem de papel está no serviço, junto da regra,
 * não aqui.
 */
@RestController
@RequestMapping("/api/v1/relatorios/lucratividade")
public class LucratividadeController {

    private final LucratividadeService service;

    public LucratividadeController(LucratividadeService service) {
        this.service = service;
    }

    @GetMapping
    public LucratividadeResponse gerar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) List<Long> idsEmpresa) {
        return service.gerar(jwt, dataInicial, dataFinal, idsEmpresa);
    }
}
