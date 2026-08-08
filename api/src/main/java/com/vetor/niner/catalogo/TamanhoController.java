package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.TamanhoDtos.TamanhoRequest;
import com.vetor.niner.catalogo.TamanhoDtos.TamanhoResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Tamanho ({@code cfg_tamanho}) — gerido embutido no modal "Gerenciar Grades" da tela de
 * Produto, sem tela própria.
 */
@RestController
@RequestMapping("/api/v1/tamanhos")
public class TamanhoController {

    private final TamanhoService service;

    public TamanhoController(TamanhoService service) {
        this.service = service;
    }

    @GetMapping
    public List<TamanhoResponse> listar() {
        return service.listar();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TamanhoResponse criar(@Valid @RequestBody TamanhoRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public TamanhoResponse renomear(@PathVariable long id, @Valid @RequestBody TamanhoRequest req) {
        return service.renomear(id, req);
    }
}
