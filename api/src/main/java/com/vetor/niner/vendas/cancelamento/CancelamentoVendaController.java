package com.vetor.niner.vendas.cancelamento;

import com.vetor.niner.identidade.permissao.Acao;
import com.vetor.niner.identidade.permissao.PermissaoService;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.CancelamentoEfetivadoResponse;
import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.CancelarVendaRequest;
import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.PaginaVendasCancelamento;
import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.VendaDetalheCancelamentoResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Cancelamento de Venda (docs/telas/cancelamento-venda.md), superfície do tenant (`/api/v1`,
 * JWT + RLS). ADMIN-only — {@link CancelamentoVendaService} rejeita OPERADOR com 403.
 */
@RestController
@RequestMapping("/api/v1/vendas/cancelamento")
@Tela("pesquisa-vendas")
public class CancelamentoVendaController {

    private final CancelamentoVendaService service;

    public CancelamentoVendaController(CancelamentoVendaService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaVendasCancelamento listar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long numeroVenda,
            @RequestParam(required = false) Long idEmpresa,
            @RequestParam(required = false) Long idCliente,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) Long idFuncionario,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(jwt, numeroVenda, idEmpresa, idCliente, dataInicial, dataFinal, idFuncionario,
                pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{idVenda}")
    public VendaDetalheCancelamentoResponse buscarDetalhe(@AuthenticationPrincipal Jwt jwt, @PathVariable long idVenda) {
        return service.buscarDetalhe(jwt, idVenda);
    }

    @Acao(PermissaoService.Acao.EXCLUIR)   // desfazer, não incluir
    @PostMapping("/{idVenda}")
    public CancelamentoEfetivadoResponse cancelar(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long idVenda, @Valid @RequestBody CancelarVendaRequest req) {
        return service.cancelar(jwt, idVenda, req);
    }
}
