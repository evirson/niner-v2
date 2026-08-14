package com.vetor.niner.financeiro.dre;

import com.vetor.niner.financeiro.dre.DreDtos.Comparacao;
import com.vetor.niner.financeiro.dre.DreDtos.DreResponse;
import com.vetor.niner.financeiro.dre.DreDtos.Regime;
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
 * Relatório de DRE (docs/telas/relatorio-dre.md), superfície do tenant (`/api/v1`, JWT + RLS).
 * <b>ADMIN-only</b> — a checagem de papel está no serviço, junto da regra, não aqui.
 */
@RestController
@RequestMapping("/api/v1/relatorios/dre")
public class DreController {

    private final DreService service;

    public DreController(DreService service) {
        this.service = service;
    }

    @GetMapping
    public DreResponse gerar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) Regime regime,
            @RequestParam(required = false) List<Long> idsEmpresa,
            @RequestParam(required = false) Comparacao comparar) {
        return service.gerar(jwt, dataInicial, dataFinal, regime, idsEmpresa, comparar);
    }
}
