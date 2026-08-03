package com.vetor.niner.vendas.devolucao;

import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.DevolucaoEfetivadaResponse;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.EfetivarDevolucaoRequest;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.ValeMercadoriaResponse;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.VendedorDaVendaResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Devolução de Produtos (docs/telas/devolucao-produtos.md), superfície do tenant (`/api/v1`,
 * JWT + RLS). ADMIN e OPERADOR têm acesso completo.
 */
@RestController
@RequestMapping("/api/v1/vendas/devolucao")
public class DevolucaoProdutoController {

    private final DevolucaoProdutoService service;

    public DevolucaoProdutoController(DevolucaoProdutoService service) {
        this.service = service;
    }

    @GetMapping("/vendedor")
    public VendedorDaVendaResponse vendedor(@RequestParam long numeroVenda) {
        return service.buscarVendedorDaVenda(numeroVenda);
    }

    /** Consulta de um vale-mercadoria pelo número — reimpressão e resgate no PDV. */
    @GetMapping("/vale/{idDevolucao}")
    public ValeMercadoriaResponse vale(@PathVariable long idDevolucao) {
        return service.buscarVale(idDevolucao);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DevolucaoEfetivadaResponse efetivar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EfetivarDevolucaoRequest req) {
        return service.efetivar(jwt, req);
    }
}
