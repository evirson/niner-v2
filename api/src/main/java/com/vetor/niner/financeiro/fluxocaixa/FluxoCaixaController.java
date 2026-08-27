package com.vetor.niner.financeiro.fluxocaixa;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.financeiro.fluxocaixa.FluxoCaixaDtos.Agrupamento;
import com.vetor.niner.financeiro.fluxocaixa.FluxoCaixaDtos.FluxoProjecaoResponse;
import com.vetor.niner.financeiro.fluxocaixa.FluxoCaixaDtos.FluxoRealizadoResponse;
import com.vetor.niner.financeiro.fluxocaixa.FluxoCaixaDtos.OrigemDinheiro;
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
 * Fluxo de Caixa (docs/telas/fluxo-caixa.md) — duas visões da mesma tela: realizado e projeção.
 * Aberto a qualquer papel (OPERADOR fica restrito à empresa ativa da sessão, no serviço) — ao
 * contrário da DRE, que é ADMIN-only: aqui não aparece lucro nem pró-labore, só entrada e saída
 * de dinheiro, que é informação de operação.
 */
@RestController
@RequestMapping("/api/v1/relatorios/fluxo-caixa")
@Tela("fluxo-caixa")
public class FluxoCaixaController {

    private final FluxoCaixaService service;

    public FluxoCaixaController(FluxoCaixaService service) {
        this.service = service;
    }

    @GetMapping("/realizado")
    public FluxoRealizadoResponse realizado(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) List<Long> idsEmpresa,
            @RequestParam(required = false) OrigemDinheiro origem) {
        return service.realizado(jwt, dataInicial, dataFinal, idsEmpresa, origem);
    }

    @GetMapping("/projecao")
    public FluxoProjecaoResponse projecao(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) List<Long> idsEmpresa,
            @RequestParam(required = false) Agrupamento agrupamento) {
        return service.projecao(jwt, dataInicial, dataFinal, idsEmpresa, agrupamento);
    }
}
