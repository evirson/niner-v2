package com.vetor.niner.financeiro.relatoriocontaspagar;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.financeiro.relatoriocontaspagar.RelatorioContasPagarDtos.RelatorioContasPagarResponse;
import com.vetor.niner.financeiro.relatoriocontaspagar.RelatorioContasPagarService.FiltrosPeriodo;
import com.vetor.niner.financeiro.relatoriocontaspagar.RelatorioContasPagarService.SituacaoConta;
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
 * Relatório de Contas a Pagar / Pagas (docs/telas/relatorio-contas-pagar.md), superfície do tenant
 * ({@code /api/v1}, JWT + RLS). Qualquer papel — somente leitura.
 */
@RestController
@RequestMapping("/api/v1/relatorios/contas-pagar")
@Tela("relatorio-contas-pagar")
public class RelatorioContasPagarController {

    private final RelatorioContasPagarService service;

    public RelatorioContasPagarController(RelatorioContasPagarService service) {
        this.service = service;
    }

    @GetMapping
    public RelatorioContasPagarResponse gerar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataLancamentoInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataLancamentoFinal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataVencimentoInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataVencimentoFinal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataPagamentoInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataPagamentoFinal,
            @RequestParam(required = false) List<Long> idsEmpresa,
            @RequestParam(required = false) Long idFornecedor,
            @RequestParam(required = false) String idPlanoContas,
            @RequestParam(required = false) SituacaoConta situacao,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {

        FiltrosPeriodo periodos = new FiltrosPeriodo(
                dataLancamentoInicial, dataLancamentoFinal,
                dataVencimentoInicial, dataVencimentoFinal,
                dataPagamentoInicial, dataPagamentoFinal);

        return service.gerar(jwt, periodos, idsEmpresa, idFornecedor, idPlanoContas,
                situacao, ordenarPor, direcao);
    }
}
