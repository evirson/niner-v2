package com.vetor.niner.financeiro.contacorrente;

import com.vetor.niner.financeiro.contacorrente.ContaCorrenteDtos.ContaCorrenteOpcaoResponse;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteDtos.ContaCorrenteRequest;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteDtos.ContaCorrenteResponse;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteDtos.ExclusaoContaCorrenteResponse;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteDtos.PaginaContasCorrente;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD de conta corrente, superfície do tenant (`/api/v1`, JWT + RLS). O identificador nos
 * paths é o próprio código da conta ({@code id_conta_corrente}, texto). Sem restrição de papel
 * — mesma decisão de produto dos demais cadastros.
 */
@RestController
@RequestMapping("/api/v1/contas-corrente")
public class ContaCorrenteController {

    private final ContaCorrenteService service;

    public ContaCorrenteController(ContaCorrenteService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaContasCorrente listar(
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(busca, status, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/opcoes")
    public List<ContaCorrenteOpcaoResponse> listarOpcoes() {
        return service.listarOpcoes();
    }

    @GetMapping("/{idContaCorrente}")
    public ContaCorrenteResponse buscar(@PathVariable String idContaCorrente) {
        return service.buscar(idContaCorrente);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContaCorrenteResponse criar(@Valid @RequestBody ContaCorrenteRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{idContaCorrente}")
    public ContaCorrenteResponse atualizar(@PathVariable String idContaCorrente, @Valid @RequestBody ContaCorrenteRequest req) {
        return service.atualizar(idContaCorrente, req);
    }

    @DeleteMapping("/{idContaCorrente}")
    public ExclusaoContaCorrenteResponse excluir(@PathVariable String idContaCorrente) {
        return service.excluir(idContaCorrente);
    }
}
