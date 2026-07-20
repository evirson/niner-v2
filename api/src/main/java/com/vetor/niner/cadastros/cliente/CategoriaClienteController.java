package com.vetor.niner.cadastros.cliente;

import com.vetor.niner.cadastros.cliente.CategoriaClienteDtos.CategoriaRequest;
import com.vetor.niner.cadastros.cliente.CategoriaClienteDtos.CategoriaResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Categoria de cliente (docs/telas/cliente.md) — gerida embutida no formulário de Cliente
 * (modal "＋ Nova categoria" / renomear), sem tela própria nesta versão.
 */
@RestController
@RequestMapping("/api/v1/categorias-cliente")
public class CategoriaClienteController {

    private final CategoriaClienteService service;

    public CategoriaClienteController(CategoriaClienteService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoriaResponse> listar() {
        return service.listar();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoriaResponse criar(@Valid @RequestBody CategoriaRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public CategoriaResponse renomear(@PathVariable long id, @Valid @RequestBody CategoriaRequest req) {
        return service.renomear(id, req);
    }
}
