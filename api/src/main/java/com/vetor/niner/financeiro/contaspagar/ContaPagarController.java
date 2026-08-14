package com.vetor.niner.financeiro.contaspagar;

import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.ContaPagarRequest;
import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.ContaPagarResponse;
import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.ExclusaoContaPagarResponse;
import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.PaginaContasPagar;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * CRUD de Contas a Pagar / Pagas, superfície do tenant (`/api/v1`, JWT + RLS). PK surrogate
 * ({@code idContaPagar}) no path. Sem restrição de papel — mesma decisão de produto dos demais
 * cadastros financeiros (Conta Corrente/Movimentação).
 */
@RestController
@RequestMapping("/api/v1/contas-pagar")
public class ContaPagarController {

    private final ContaPagarService service;

    public ContaPagarController(ContaPagarService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaContasPagar listar(
            @RequestParam(required = false) Long idFornecedor,
            @RequestParam(required = false) Long idEmpresa,
            @RequestParam(required = false) Integer notaFiscal,
            @RequestParam(required = false) String numeroDuplicata,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataVencimentoInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataVencimentoFinal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataPagamentoInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataPagamentoFinal,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(idFornecedor, idEmpresa, notaFiscal, numeroDuplicata, dataVencimentoInicial,
                dataVencimentoFinal, dataPagamentoInicial, dataPagamentoFinal, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{id}")
    public ContaPagarResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContaPagarResponse criar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ContaPagarRequest req) {
        return service.criar(jwt, req);
    }

    // O JWT entra aqui por causa da baixa em dinheiro: o movimento vai pro caixa ABERTO do usuário
    // (2026-08-14, docs/telas/fluxo-caixa.md).
    @PutMapping("/{id}")
    public ContaPagarResponse atualizar(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody ContaPagarRequest req) {
        return service.atualizar(jwt, id, req);
    }

    /** Precisa do JWT desde 2026-08-14: a exclusão desfaz o movimento de caixa/banco da baixa,
     *  e recusa quando o caixa envolvido já está fechado. */
    @DeleteMapping("/{id}")
    public ExclusaoContaPagarResponse excluir(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.excluir(jwt, id);
    }
}
