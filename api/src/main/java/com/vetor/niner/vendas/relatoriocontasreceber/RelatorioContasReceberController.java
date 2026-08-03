package com.vetor.niner.vendas.relatoriocontasreceber;

import com.vetor.niner.vendas.relatoriocontasreceber.RelatorioContasReceberDtos.RelatorioContasReceberResponse;
import com.vetor.niner.vendas.relatoriocontasreceber.RelatorioContasReceberService.CategoriaParcela;
import com.vetor.niner.vendas.relatoriocontasreceber.RelatorioContasReceberService.FiltrosPeriodo;
import com.vetor.niner.vendas.relatoriocontasreceber.RelatorioContasReceberService.StatusParcela;
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
 * Relatório de Contas a Receber / Recebidas (docs/telas/relatorio-contas-receber.md), superfície
 * do tenant (`/api/v1`, JWT + RLS). Qualquer papel — somente leitura.
 */
@RestController
@RequestMapping("/api/v1/relatorios/contas-receber")
public class RelatorioContasReceberController {

    private final RelatorioContasReceberService service;

    public RelatorioContasReceberController(RelatorioContasReceberService service) {
        this.service = service;
    }

    @GetMapping
    public RelatorioContasReceberResponse gerar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataVendaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataVendaFinal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataVencimentoInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataVencimentoFinal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataRecebimentoInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataRecebimentoFinal,
            @RequestParam(required = false) List<Long> idsEmpresa,
            @RequestParam(required = false) StatusParcela status,
            @RequestParam(required = false) CategoriaParcela categoria,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        FiltrosPeriodo periodos = new FiltrosPeriodo(
                dataVendaInicial, dataVendaFinal, dataVencimentoInicial, dataVencimentoFinal,
                dataRecebimentoInicial, dataRecebimentoFinal);
        return service.gerar(jwt, periodos, idsEmpresa, status, categoria, ordenarPor, direcao);
    }
}
