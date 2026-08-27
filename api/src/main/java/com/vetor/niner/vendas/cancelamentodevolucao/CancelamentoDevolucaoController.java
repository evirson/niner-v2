package com.vetor.niner.vendas.cancelamentodevolucao;

import com.vetor.niner.identidade.permissao.Acao;
import com.vetor.niner.identidade.permissao.PermissaoService;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.vendas.cancelamentodevolucao.CancelamentoDevolucaoDtos.CancelamentoDevolucaoEfetivadoResponse;
import com.vetor.niner.vendas.cancelamentodevolucao.CancelamentoDevolucaoDtos.CancelarDevolucaoRequest;
import com.vetor.niner.vendas.cancelamentodevolucao.CancelamentoDevolucaoDtos.DevolucaoDetalheCancelamentoResponse;
import com.vetor.niner.vendas.cancelamentodevolucao.CancelamentoDevolucaoDtos.PaginaDevolucoesCancelamento;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Cancelamento de Devolução de Produtos (docs/telas/cancelamento-devolucao-produtos.md),
 * superfície do tenant (`/api/v1`, JWT + RLS). ADMIN e OPERADOR têm acesso — {@link
 * CancelamentoDevolucaoService} restringe OPERADOR à empresa em que está logado.
 */
@RestController
@RequestMapping("/api/v1/vendas/cancelamento-devolucao")
@Tela("cancelamento-devolucao-produtos")
public class CancelamentoDevolucaoController {

    private final CancelamentoDevolucaoService service;

    public CancelamentoDevolucaoController(CancelamentoDevolucaoService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaDevolucoesCancelamento listar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long idDevolucao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(jwt, idDevolucao, dataInicial, dataFinal, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{idDevolucao}")
    public DevolucaoDetalheCancelamentoResponse buscarDetalhe(@AuthenticationPrincipal Jwt jwt, @PathVariable long idDevolucao) {
        return service.buscarDetalhe(jwt, idDevolucao);
    }

    @Acao(PermissaoService.Acao.EXCLUIR)   // desfazer, não incluir
    @PostMapping("/{idDevolucao}")
    public CancelamentoDevolucaoEfetivadoResponse cancelar(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long idDevolucao, @Valid @RequestBody CancelarDevolucaoRequest req) {
        return service.cancelar(jwt, idDevolucao, req);
    }
}
