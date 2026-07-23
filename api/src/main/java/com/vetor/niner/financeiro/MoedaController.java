package com.vetor.niner.financeiro;

import com.vetor.niner.financeiro.MoedaDtos.ExclusaoMoedaResponse;
import com.vetor.niner.financeiro.MoedaDtos.MoedaRequest;
import com.vetor.niner.financeiro.MoedaDtos.MoedaResponse;
import com.vetor.niner.financeiro.MoedaDtos.PaginaMoedas;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD de moeda (forma de recebimento), superfície do tenant (`/api/v1`, JWT + RLS). Sem
 * restrição de papel — mesma decisão de produto dos demais cadastros.
 */
@RestController
@RequestMapping("/api/v1/moedas")
public class MoedaController {

    private final MoedaService service;

    public MoedaController(MoedaService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaMoedas listar(
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(busca, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{id}")
    public MoedaResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MoedaResponse criar(@Valid @RequestBody MoedaRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public MoedaResponse atualizar(@PathVariable long id, @Valid @RequestBody MoedaRequest req) {
        return service.atualizar(id, req);
    }

    @DeleteMapping("/{id}")
    public ExclusaoMoedaResponse excluir(@PathVariable long id) {
        return service.excluir(id);
    }
}
