package com.vetor.niner.vendas.pesquisa;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.vendas.pesquisa.PesquisaVendaDtos.PaginaVendasPesquisa;
import com.vetor.niner.vendas.pesquisa.PesquisaVendaDtos.VendaDetalhePesquisaResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Pesquisa de Venda (docs/telas/pesquisa-vendas.md), superfície do tenant (`/api/v1`, JWT + RLS).
 * Qualquer papel — sem {@code RequireAdmin}, ao contrário de {@code vendas.cancelamento}.
 */
@RestController
@RequestMapping("/api/v1/vendas/pesquisa")
@Tela("pesquisa-vendas")
public class PesquisaVendaController {

    private final PesquisaVendaService service;

    public PesquisaVendaController(PesquisaVendaService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaVendasPesquisa listar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long numeroVenda,
            @RequestParam(required = false) Long idEmpresa,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) Long idCliente,
            @RequestParam(required = false) Long idFuncionario,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(jwt, numeroVenda, idEmpresa, situacao, idCliente, idFuncionario, dataInicial, dataFinal,
                pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{idVenda}")
    public VendaDetalhePesquisaResponse buscarDetalhe(@AuthenticationPrincipal Jwt jwt, @PathVariable long idVenda) {
        return service.buscarDetalhe(jwt, idVenda);
    }
}
