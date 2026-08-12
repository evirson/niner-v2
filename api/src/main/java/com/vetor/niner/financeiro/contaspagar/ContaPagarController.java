package com.vetor.niner.financeiro.contaspagar;

import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.ContaPagarRequest;
import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.ContaPagarResponse;
import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.ExclusaoContaPagarResponse;
import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.PaginaContasPagar;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
    public ContaPagarResponse criar(@Valid @RequestBody ContaPagarRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public ContaPagarResponse atualizar(@PathVariable long id, @Valid @RequestBody ContaPagarRequest req) {
        return service.atualizar(id, req);
    }

    @DeleteMapping("/{id}")
    public ExclusaoContaPagarResponse excluir(@PathVariable long id) {
        return service.excluir(id);
    }
}
