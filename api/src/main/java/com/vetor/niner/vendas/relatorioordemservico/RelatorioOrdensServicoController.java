package com.vetor.niner.vendas.relatorioordemservico;

import com.vetor.niner.identidade.permissao.Tela;
import com.vetor.niner.vendas.relatorioordemservico.RelatorioOrdensServicoDtos.RelatorioOrdensServicoResponse;
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
 * Relatório de Ordens de Serviço (docs/telas/relatorio-ordem-servico.md), superfície do tenant
 * ({@code /api/v1}, JWT + RLS). Qualquer papel — somente leitura.
 */
@RestController
@RequestMapping("/api/v1/relatorios/ordens-servico")
@Tela("relatorio-ordens-servico")
public class RelatorioOrdensServicoController {

    private final RelatorioOrdensServicoService service;

    public RelatorioOrdensServicoController(RelatorioOrdensServicoService service) {
        this.service = service;
    }

    @GetMapping
    public RelatorioOrdensServicoResponse gerar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) List<Long> idsEmpresa,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.gerar(jwt, dataInicial, dataFinal, idsEmpresa, ordenarPor, direcao);
    }
}
