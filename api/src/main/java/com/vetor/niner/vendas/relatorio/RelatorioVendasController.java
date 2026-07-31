package com.vetor.niner.vendas.relatorio;

import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.DetalheTotalizadorResponse;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.RelatorioVendasResponse;
import com.vetor.niner.vendas.relatorio.RelatorioVendasDtos.TotalizarPor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Relatório de Vendas (docs/telas/relatorio-vendas.md), superfície do tenant (`/api/v1`, JWT +
 * RLS). Qualquer papel — somente leitura, sem escrita nenhuma neste pacote.
 */
@RestController
@RequestMapping("/api/v1/relatorios/vendas")
public class RelatorioVendasController {

    private final RelatorioVendasService service;

    public RelatorioVendasController(RelatorioVendasService service) {
        this.service = service;
    }

    @GetMapping
    public RelatorioVendasResponse gerar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) List<Long> idsEmpresa,
            @RequestParam(required = false) Long idFuncionario,
            @RequestParam(required = false) TotalizarPor totalizarPor) {
        return service.gerar(jwt, dataInicial, dataFinal, idsEmpresa, idFuncionario, totalizarPor);
    }

    @GetMapping("/detalhe")
    public DetalheTotalizadorResponse detalhe(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) List<Long> idsEmpresa,
            @RequestParam(required = false) Long idFuncionario,
            @RequestParam TotalizarPor totalizarPor,
            @RequestParam String chave) {
        return service.buscarDetalheGrupo(jwt, dataInicial, dataFinal, idsEmpresa, idFuncionario, totalizarPor, chave);
    }
}
