package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.CorDtos.CorRequest;
import com.vetor.niner.catalogo.CorDtos.CorResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Cor ({@code cfg_cor}) — gerida embutida na Emissão de Etiqueta ("+ Nova cor"), sem tela
 * própria nesta versão (a Entrada de Produtos, onde cor nasceria na prática, ainda não existe).
 */
@RestController
@RequestMapping("/api/v1/cores")
public class CorController {

    private final CorService service;

    public CorController(CorService service) {
        this.service = service;
    }

    @GetMapping
    public List<CorResponse> listar() {
        return service.listar();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CorResponse criar(@Valid @RequestBody CorRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public CorResponse renomear(@PathVariable long id, @Valid @RequestBody CorRequest req) {
        return service.renomear(id, req);
    }
}
