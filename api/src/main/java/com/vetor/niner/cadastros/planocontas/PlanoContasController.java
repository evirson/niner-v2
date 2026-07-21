package com.vetor.niner.cadastros.planocontas;

import com.vetor.niner.cadastros.planocontas.PlanoContasDtos.ExclusaoPlanoContasResponse;
import com.vetor.niner.cadastros.planocontas.PlanoContasDtos.PaginaPlanosContas;
import com.vetor.niner.cadastros.planocontas.PlanoContasDtos.PlanoContasRequest;
import com.vetor.niner.cadastros.planocontas.PlanoContasDtos.PlanoContasResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD de plano de contas (docs/telas/plano-contas.md), superfície do tenant (`/api/v1`,
 * JWT + RLS). O identificador nos paths é o próprio código contábil ({@code
 * id_plano_contas}, texto — ex. {@code /api/v1/planos-contas/3.1.001}). Sem restrição de
 * papel — mesma decisão de produto dos demais cadastros (R8 não se aplica).
 */
@RestController
@RequestMapping("/api/v1/planos-contas")
public class PlanoContasController {

    private final PlanoContasService service;

    public PlanoContasController(PlanoContasService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaPlanosContas listar(
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(busca, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{codigo}")
    public PlanoContasResponse buscar(@PathVariable String codigo) {
        return service.buscar(codigo);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanoContasResponse criar(@Valid @RequestBody PlanoContasRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{codigo}")
    public PlanoContasResponse atualizar(@PathVariable String codigo, @Valid @RequestBody PlanoContasRequest req) {
        return service.atualizar(codigo, req);
    }

    @DeleteMapping("/{codigo}")
    public ExclusaoPlanoContasResponse excluir(@PathVariable String codigo) {
        return service.excluir(codigo);
    }
}
