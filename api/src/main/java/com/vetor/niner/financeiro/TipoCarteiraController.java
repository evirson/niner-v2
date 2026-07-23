package com.vetor.niner.financeiro;

import com.vetor.niner.financeiro.TipoCarteiraDtos.ExclusaoTipoCarteiraResponse;
import com.vetor.niner.financeiro.TipoCarteiraDtos.PaginaTiposCarteira;
import com.vetor.niner.financeiro.TipoCarteiraDtos.TipoCarteiraRequest;
import com.vetor.niner.financeiro.TipoCarteiraDtos.TipoCarteiraResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD de tipo de carteira, superfície do tenant (`/api/v1`, JWT + RLS). Sem restrição de
 * papel — mesma decisão de produto dos demais cadastros.
 */
@RestController
@RequestMapping("/api/v1/tipos-carteira")
public class TipoCarteiraController {

    private final TipoCarteiraService service;

    public TipoCarteiraController(TipoCarteiraService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaTiposCarteira listar(
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(busca, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{id}")
    public TipoCarteiraResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TipoCarteiraResponse criar(@Valid @RequestBody TipoCarteiraRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public TipoCarteiraResponse atualizar(@PathVariable long id, @Valid @RequestBody TipoCarteiraRequest req) {
        return service.atualizar(id, req);
    }

    @DeleteMapping("/{id}")
    public ExclusaoTipoCarteiraResponse excluir(@PathVariable long id) {
        return service.excluir(id);
    }
}
