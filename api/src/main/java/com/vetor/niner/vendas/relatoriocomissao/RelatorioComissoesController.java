package com.vetor.niner.vendas.relatoriocomissao;

import com.vetor.niner.vendas.relatoriocomissao.RelatorioComissoesDtos.RelatorioComissoesResponse;
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
 * Relatório de Comissões (docs/telas/relatorio-comissoes.md), superfície do tenant (`/api/v1`,
 * JWT + RLS). Qualquer papel — somente leitura.
 */
@RestController
@RequestMapping("/api/v1/relatorios/comissoes")
public class RelatorioComissoesController {

    private final RelatorioComissoesService service;

    public RelatorioComissoesController(RelatorioComissoesService service) {
        this.service = service;
    }

    @GetMapping
    public RelatorioComissoesResponse gerar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) List<Long> idsEmpresa,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.gerar(jwt, dataInicial, dataFinal, idsEmpresa, ordenarPor, direcao);
    }
}
